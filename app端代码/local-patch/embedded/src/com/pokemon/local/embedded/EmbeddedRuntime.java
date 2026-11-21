package com.pokemon.local.embedded;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.account.AccountService;
import com.pokemon.local.bootstrap.BootstrapService;
import com.pokemon.local.bootstrap.BootstrapServiceImpl;
import com.pokemon.local.gateway.GameTicketService;
import com.pokemon.local.gateway.LocalGameGateway;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.PaymentService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 应用私有存档、Unity 本地引导和 TCP 协议的组合入口。 */
public final class EmbeddedRuntime {
    private static final String TAG = "PokemonOffline";
    private static EmbeddedHttp instance;

    private EmbeddedRuntime() {
    }

    /** 初始化一次。调用方已在本地工作台的 I/O 执行器上。 */
    public static synchronized EmbeddedHttp get(Context context) throws IOException {
        if (instance != null) {
            return instance;
        }
        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException("单机存档需要 Android 8.0 或更新版本");
        }
        ClassPathResource.install(context.getAssets());
        SQLiteDatabase db = context.openOrCreateDatabase("pokemon-offline.db", Context.MODE_PRIVATE, null);
        SQLiteDataSource source = new SQLiteDataSource(db);
        EmbeddedStore store = new EmbeddedStore(db);
        LocalGameGateway game = null;
        EmbeddedHttp http = null;
        try {
            db.setForeignKeyConstraintsEnabled(true);
            ComponentGraph graph = store.write(() -> initialize(source));
            ObjectMapper json = graph.get(ObjectMapper.class);
            BootstrapService bootstrap = BootstrapServiceImpl.forInstalledApk(context.getApplicationInfo().sourceDir,
                    graph.get(RoleService.class), json);
            game = graph.get(LocalGameGateway.class);
            game.useTransaction(request -> {
                try {
                    store.write(() -> {
                        request.run();
                        return null;
                    });
                } catch (IOException | RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException("无法保存单机游戏操作", failure);
                }
            });
            http = new EmbeddedHttp(bootstrap, graph.get(AccountService.class), graph.get(PaymentService.class),
                    graph.get(GamePaymentService.class), graph.get(com.pokemon.local.inventory.ProfileMailService.class), graph.get(GameTicketService.class), json, store);
            game.start();
            http.start(BootstrapServiceImpl.EMBEDDED_HTTP_PORT);
            instance = http;
            Log.i(TAG, "Offline gameplay initialized with local SQLite");
            return instance;
        } catch (Exception | LinkageError failure) {
            if (http != null) {
                http.close();
            }
            if (game != null) {
                game.stop();
            }
            db.close();
            Log.e(TAG, "Unable to start offline gameplay", failure);
            throw new IOException("无法启动应用内单机运行环境，请查看 PokemonOffline 日志", failure);
        }
    }

    private static ComponentGraph initialize(SQLiteDataSource source) throws Exception {
        for (String name : new String[]{"schema.sql", "schema-game.sql", "schema-payment.sql",
                "schema-inventory.sql", "schema-activity.sql"}) {
            new ResourceDatabasePopulator(new ClassPathResource(name)).execute(source);
        }
        ComponentGraph graph = new ComponentGraph();
        graph.bind(DataSource.class, source);
        graph.bind(JdbcTemplate.class, new JdbcTemplate(source));
        graph.bind(ObjectMapper.class, new ObjectMapper());
        graph.get(AccountService.class);
        graph.get(LocalGameGateway.class);
        return graph;
    }
}
