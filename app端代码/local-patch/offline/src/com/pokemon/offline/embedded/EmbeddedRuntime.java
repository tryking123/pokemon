package com.pokemon.offline.embedded;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import com.pokemon.offline.save.SaveArchive;
import java.io.InputStream;
import java.io.OutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.bootstrap.BootstrapServiceImpl;
import com.pokemon.offline.gateway.GameTicketService;
import com.pokemon.offline.gateway.OfflineGameSession;
import com.pokemon.offline.inventory.ProfileMailService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.payment.PaymentService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.IOException;
import javax.sql.DataSource;

/**
 * App 独立的离线业务、私有存档与 Unity 直接调用运行环境。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
public final class EmbeddedRuntime {
    private static EmbeddedRuntime instance;
    private final EmbeddedStore store;
    private final SQLiteDatabase database;
    private final AccountService accounts;
    private final OfflineAppApi api;
    private final OfflineGameSession game;
    private final BootstrapServiceImpl bootstrap;

    /**
     * 保存同一次装配得到的离线组件。
     * @param store 事务入口
     * @param database 当前存档库
     * @param accounts 本地账号会话服务
     * @param api App 内操作分派器
     * @param game Unity 内存会话
     * @param bootstrap 本地文件资源准备器
     */
    private EmbeddedRuntime(OfflineAppApi api, OfflineGameSession game, BootstrapServiceImpl bootstrap,
                            EmbeddedStore store, SQLiteDatabase database, AccountService accounts) {
        this.store = store;
        this.database = database;
        this.accounts = accounts;
        this.api = api;
        this.game = game;
        this.bootstrap = bootstrap;
    }

    /**
     * 初始化唯一的 SQLite 离线运行环境，不启动服务或监听端口。
     * @param context 应用上下文
     * @return 离线运行环境
     * @throws IOException 装配或存档初始化失败
     */
    public static synchronized EmbeddedRuntime get(Context context) throws IOException {
        if (instance != null) return instance;
        if (Build.VERSION.SDK_INT < 26) throw new IOException("离线存档需要 Android 8.0 或更新版本");
        ClassPathResource.install(context.getAssets());
        SQLiteDatabase db = context.openOrCreateDatabase("pokemon-offline.db", Context.MODE_PRIVATE, null);
        SQLiteDataSource source = new SQLiteDataSource(db);
        EmbeddedStore store = new EmbeddedStore(db);
        OfflineGameSession game = null;
        try {
            db.setForeignKeyConstraintsEnabled(true);
            ComponentGraph graph = store.write(() -> initialize(source));
            ObjectMapper json = graph.get(ObjectMapper.class);
            BootstrapServiceImpl bootstrap = (BootstrapServiceImpl) BootstrapServiceImpl.forInstalledApk(
                    context.getApplicationInfo().sourceDir, graph.get(RoleService.class), json);
            game = graph.get(OfflineGameSession.class);
            game.useTransaction(request -> {
                try {
                    store.write(() -> { request.run(); return null; });
                } catch (IOException | RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException("无法保存离线操作", failure);
                }
            });
            OfflineAppApi api = new OfflineAppApi(bootstrap, graph.get(AccountService.class),
                    graph.get(PaymentService.class), graph.get(GamePaymentService.class),
                    graph.get(ProfileMailService.class), graph.get(GameTicketService.class),
                    graph.get(com.pokemon.offline.home.PetCustomizationService.class), json, store);
            instance = new EmbeddedRuntime(api, game, bootstrap, store, db, graph.get(AccountService.class));
            return instance;
        } catch (Exception | LinkageError failure) {
            if (game != null) game.stop();
            db.close();
            throw new IOException("无法初始化 App 离线存档", failure);
        }
    }

    /**
     * 通过与游戏写入相同的事务入口导出一致的快照。
     * @param context 应用上下文
     * @param auth 当前账号令牌
     * @param destination 用户选择的输出文件流
     * @throws Exception 登录失效或写入失败
     */
    public void exportSave(Context context, String auth, OutputStream destination) throws Exception {
        store.write(() -> {
            requireAdmin(auth);
            SaveArchive.exportAdmin(database, context.getCacheDir(), destination);
            return null;
        });
    }

    /**
     * 导入成功后释放旧数据库和会话缓存，下一次登录重新装配组件。
     * @param context 应用上下文
     * @param auth 当前账号令牌
     * @param source 用户选择的存档文件流
     * @param regenerate 是否生成新的身份编号
     * @throws Exception 导入或完整性检查失败
     */
    public static synchronized void importSave(Context context, String auth, InputStream source, boolean regenerate)
            throws Exception {
        EmbeddedRuntime runtime = get(context);
        synchronized (runtime.game) {
            runtime.store.write(() -> {
                runtime.requireAdmin(auth);
                SaveArchive.importAdmin(runtime.database, context.getCacheDir(), source, regenerate);
                return null;
            });
            runtime.game.stop();
            runtime.database.close();
            instance = null;
        }
    }

    /** 核对本地管理员会话。 */
    private void requireAdmin(String auth) throws IOException {
        if (!"admin".equals(accounts.requireAccount(auth).username)) throw new IOException("仅 admin 账号可操作存档");
    }

    /**
     * 获取 Android 和 Unity 共用的进程内操作入口。
     * @return 离线操作入口
     */
    public OfflineAppApi api() { return api; }

    /**
     * 获取 Unity 直接调用的内存会话。
     * @return 内存会话
     */
    public OfflineGameSession game() { return game; }

    /**
     * 在 Unity 启动前准备本地文件资源，资源路径位于 App 私有目录。
     * @param context 应用上下文
     * @throws IOException 文件准备失败
     */
    public void prepareUnity(Context context) throws IOException {
        java.io.File internal = new java.io.File(context.getFilesDir(), "offline-bootstrap");
        bootstrap.prepareFiles(internal);
        java.io.File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Unity 私有引导目录不可用");
        java.io.File folder = new java.io.File(external, "offline-bootstrap/1.0.1");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建 Unity 文件引导目录");
        java.nio.file.Files.copy(new java.io.File(internal, "1.0.1/config.txt").toPath(),
                new java.io.File(folder, "config.txt").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 从 App 自身资源初始化数据库及业务组件。
     * @param source App 私有 SQLite 数据源
     * @return 组件容器
     * @throws Exception 迁移或装配失败
     */
    private static ComponentGraph initialize(SQLiteDataSource source) throws Exception {
        for (String name : new String[]{"schema.sql", "schema-game.sql", "schema-payment.sql", "schema-inventory.sql", "schema-activity.sql"}) {
            new ResourceDatabasePopulator(new ClassPathResource(name)).execute(source);
        }
        ComponentGraph graph = new ComponentGraph();
        graph.bind(DataSource.class, source);
        graph.bind(JdbcTemplate.class, new JdbcTemplate(source));
        graph.bind(ObjectMapper.class, new ObjectMapper());
        graph.get(AccountService.class);
        graph.get(OfflineGameSession.class);
        return graph;
    }
}
