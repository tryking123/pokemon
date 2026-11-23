package com.qk.local;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.pokemon.offline.embedded.EmbeddedRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 守护小精灵本地账号、余额与模拟充值入口
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:00
 */
public final class LocalHomeActivity extends Activity {
    private static final int DARK = Color.rgb(24, 38, 59);
    private static final int MUTED = Color.rgb(80, 96, 113);
    private final List<Product> catalog = new ArrayList<>();
    private LinearLayout form;
    private LinearLayout wallet;
    private TextView balance;
    private TextView status;
    private EditText username;
    private EditText password;
    private Spinner products;
    private Button login;
    private Button purchase;
    private Button refresh;
    private Button logout;
    private Button game;
    private Button exportSave;
    private Button importSave;
    private static final int EXPORT_FOLDER = 70;
    private static final int IMPORT_FILE = 71;
    private Uri importFile;
    private boolean regenerateId;
    private boolean busy;

    /** 构建本地入口页面。 */
    @Override
    protected void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(243, 247, 252));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(36), dp(24), dp(28));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = text("守护小精灵", 30, DARK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(content, title, 0);
        add(content, text("单机存档保存在本设备", 14, MUTED), 6);

        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        add(content, form, 24);
        add(form, text("本地账号", 18, DARK), 0);
        username = edit("账号", InputType.TYPE_CLASS_TEXT);
        username.setText("admin");
        add(form, username, 8);
        password = edit("密码（默认 admin）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        add(form, password, 8);
        login = button("登录本地账号", this::login);
        add(form, login, 12);

        wallet = new LinearLayout(this);
        wallet.setOrientation(LinearLayout.VERTICAL);
        add(content, wallet, 24);
        balance = text("", 22, DARK);
        add(wallet, balance, 0);
        add(wallet, text("模拟钻石记入本地账户，仅在此页面显示。", 14, MUTED), 6);
        products = new Spinner(this);
        products.setContentDescription("模拟充值商品");
        add(wallet, products, 16);
        purchase = button("模拟充值", this::promptPurchase);
        add(wallet, purchase, 10);
        refresh = button("刷新余额与商品", this::loadWallet);
        add(wallet, refresh, 6);
        game = button("启动 Unity 游戏", this::launchGame);
        add(wallet, game, 18);
        add(wallet, text("Unity 启动后会准备游戏资源。游戏进服仍取决于原游戏协议。", 14, MUTED), 4);
        exportSave = button("导出存档", () -> chooseDocument(EXPORT_FOLDER));
        add(wallet, exportSave, 12);
        importSave = button("导入存档", () -> chooseDocument(IMPORT_FILE));
        add(wallet, importSave, 4);
        logout = button("退出本地账号", this::logout);
        add(wallet, logout, 12);

        status = text("", 14, DARK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 16);
        renderControls();
        if (state != null && state.getString("importFile") != null && LocalApi.currentSession() != null) {
            importFile = Uri.parse(state.getString("importFile"));
            regenerateId = state.getBoolean("regenerateId");
            confirmImport();
        }
    }

    /** 保留系统文件选择后的待确认导入项。 */
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (importFile != null) {
            state.putString("importFile", importFile.toString());
            state.putBoolean("regenerateId", regenerateId);
        }
    }

    /**
     * 打开系统目录选择器或存档文件选择器。
     * @param request 导出目录或导入文件请求编号
     */
    private void chooseDocument(int request) {
        Intent picker = request == EXPORT_FOLDER ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(picker, request);
        } catch (android.content.ActivityNotFoundException unavailable) {
            status.setText("本机没有可用的系统文件选择器");
        }
    }

    /** 接收用户选择的目录或文件。 */
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || LocalApi.currentSession() == null) return;
        if (request == EXPORT_FOLDER) {
            exportToFolder(data.getData());
        } else if (request == IMPORT_FILE) {
            importFile = data.getData();
            regenerateId = false;
            confirmImport();
        }
    }

    /**
     * 在指定目录新建文件，并在写入失败时清理本次未完成的文件。
     * @param tree 系统选择器授予写入权限的目录
     */
    private void exportToFolder(Uri tree) {
        String auth = "Bearer " + LocalApi.currentSession().token;
        setBusy("正在导出存档…");
        LocalApi.run(this, () -> {
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            String name = "守护小精灵-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".pksave";
            Uri file = DocumentsContract.createDocument(getContentResolver(), folder, "application/octet-stream", name);
            if (file == null) throw new IOException("无法在所选目录中新建存档");
            try (OutputStream output = getContentResolver().openOutputStream(file, "w")) {
                if (output == null) throw new IOException("无法写入所选目录");
                EmbeddedRuntime.get(this).exportSave(this, auth, output);
            } catch (Exception failed) {
                try { DocumentsContract.deleteDocument(getContentResolver(), file); }
                catch (Exception cleanup) { failed.addSuppressed(cleanup); }
                throw failed;
            }
            return name;
        }, (name, failure) -> complete(failure, "已导出到所选目录：" + name));
    }

    /** 导入前说明替换范围，并让玩家选择身份编号处理方式。 */
    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("替换 admin 的存档？")
                .setSingleChoiceItems(new String[]{"保留存档中的账号和角色 ID", "重新生成账号和角色 ID，并迁移关联数据"},
                        regenerateId ? 1 : 0, (dialog, index) -> regenerateId = index == 1)
                .setNegativeButton("取消", (dialog, which) -> importFile = null)
                .setOnCancelListener(dialog -> importFile = null)
                .setPositiveButton("替换并导入", (dialog, which) -> {
                    Uri file = importFile;
                    importFile = null;
                    restoreFile(file, regenerateId);
                }).show();
    }

    /**
     * 恢复用户选定的存档并返回登录界面。
     * @param file 系统选择器返回的文件
     * @param regenerate 是否重新生成账号和角色编号
     */
    private void restoreFile(Uri file, boolean regenerate) {
        LocalApi.Session session = LocalApi.currentSession();
        if (session == null) return;
        setBusy("正在校验并导入存档…");
        LocalApi.run(this, () -> {
            try (InputStream input = getContentResolver().openInputStream(file)) {
                if (input == null) throw new IOException("无法读取所选存档");
                EmbeddedRuntime.importSave(this, "Bearer " + session.token, input, regenerate);
            } catch (android.database.sqlite.SQLiteConstraintException conflict) {
                throw new IOException("存档与现有数据冲突，已保留导入前的存档。", conflict);
            } catch (java.util.zip.ZipException | java.io.EOFException invalid) {
                throw new IOException("存档文件损坏或不完整，已保留导入前的存档。", invalid);
            }
            LocalApi.forgetSession();
            getSharedPreferences("local_admin_save_settings", MODE_PRIVATE).edit()
                    .putString("pendingNamespace", UUID.randomUUID().toString()).commit();
            return null;
        }, (result, failure) -> complete(failure, "admin 存档已导入，请用存档中的密码重新登录"));
    }

    /** 返回工作台时刷新App余额。 */
    @Override
    protected void onResume() {
        super.onResume();
        if (LocalApi.currentSession() != null && !busy) {
            loadWallet();
        }
    }

    private void login() {
        String name = username.getText().toString().trim();
        String secret = password.getText().toString();
        if (name.isEmpty() || secret.isEmpty()) {
            status.setText("请输入本地账号和密码");
            return;
        }
        setBusy("正在登录…");
        LocalApi.run(this, () -> LocalApi.login(name, secret), (result, failure) -> {
            complete(failure, "已登录");
            if (failure == null) {
                password.setText("");
                showBalance(result);
                loadWallet();
            }
        });
    }

    private void logout() {
        setBusy("正在退出…");
        LocalApi.run(this, () -> {
            LocalApi.logout();
            return null;
        }, (result, failure) -> complete(failure, "已退出本地账号"));
    }

    private void loadWallet() {
        setBusy("正在刷新余额与商品…");
        LocalApi.run(this, () -> new Object[]{LocalApi.account(), LocalApi.products()}, (result, failure) -> {
            if (failure == null) {
                showBalance((JSONObject) result[0]);
                showProducts((JSONArray) result[1]);
            }
            complete(failure, "");
        });
    }

    private void launchGame() {
        setBusy("正在核对 Unity 配置…");
        LocalApi.run(this, () -> {
            UnityMetadataCache.prepare(this);
            return null;
        }, (result, failure) -> {
            complete(failure, "");
            if (failure == null) {
                startActivity(new Intent(this, LocalGameActivity.class));
            }
        });
    }

    private void showBalance(JSONObject account) {
        LocalApi.Session session = LocalApi.currentSession();
        if (session != null && account != null) {
            balance.setText(session.username + "\n本地模拟钻石  " + account.optLong("balance"));
        }
    }

    private void showProducts(JSONArray result) {
        catalog.clear();
        List<String> labels = new ArrayList<>();
        if (result == null) {
            products.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
            return;
        }
        for (int index = 0; index < result.length(); index++) {
            JSONObject item = result.optJSONObject(index);
            if (item != null) {
                Product product = new Product(item);
                catalog.add(product);
                labels.add(String.format(Locale.CHINA, "%d 模拟钻石  ·  ¥%.2f", product.reward, product.amount / 100.0));
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        products.setAdapter(adapter);
    }

    private void promptPurchase() {
        LocalApi.Session session = LocalApi.currentSession();
        if (session == null || busy) {
            return;
        }
        SharedPreferences orders = pendingOrders(session);
        String pendingId = orders.getString("clientOrderId", "");
        String pendingProduct = orders.getString("productId", "");
        if (!pendingId.isEmpty() && !pendingProduct.isEmpty()) {
            purchase(session, pendingProduct, pendingId);
            return;
        }
        int selection = products.getSelectedItemPosition();
        if (selection < 0 || selection >= catalog.size()) {
            return;
        }
        Product product = catalog.get(selection);
        String clientOrderId = "android-" + UUID.randomUUID();
        boolean saved = orders.edit().putString("clientOrderId", clientOrderId)
                .putString("productId", product.id).commit();
        if (saved) {
            purchase(session, product.id, clientOrderId);
        } else {
            status.setText("无法保存订单编号，请重试");
        }
    }

    private void purchase(LocalApi.Session session, String productId, String clientOrderId) {
        setBusy("正在确认本地模拟订单…");
        LocalApi.run(this, () -> LocalApi.simulatePurchase(session, productId, clientOrderId), (order, failure) -> {
            if (failure == null) {
                pendingOrders(session).edit().clear().apply();
                showBalance(order);
                complete(null, "模拟充值到账，订单号 " + order.optString("orderId"));
            } else {
                complete(failure, "");
            }
        });
    }

    private SharedPreferences pendingOrders(LocalApi.Session session) {
        String key = Base64.encodeToString(session.accountId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String namespace = !"admin".equals(session.username) ? "" : getSharedPreferences("local_admin_save_settings", MODE_PRIVATE).getString("pendingNamespace", "");
        return getSharedPreferences("local_pending_" + namespace + key, MODE_PRIVATE);
    }

    private void setBusy(String message) {
        busy = true;
        status.setText(message);
        renderControls();
    }

    private void complete(Exception failure, String message) {
        busy = false;
        status.setText(failure == null ? message : LocalApi.errorText(failure));
        renderControls();
    }

    private void renderControls() {
        LocalApi.Session session = LocalApi.currentSession();
        boolean loggedIn = session != null;
        boolean pending = loggedIn && pendingOrders(session).contains("clientOrderId");
        form.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        wallet.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        login.setEnabled(!busy);
        products.setEnabled(!busy && !pending);
        purchase.setEnabled(!busy && (pending || !catalog.isEmpty()));
        purchase.setText(pending ? "重试同一模拟订单" : "模拟充值");
        refresh.setEnabled(!busy);
        boolean admin = loggedIn && "admin".equals(session.username);
        exportSave.setVisibility(admin ? View.VISIBLE : View.GONE);
        importSave.setVisibility(admin ? View.VISIBLE : View.GONE);
        exportSave.setEnabled(!busy);
        importSave.setEnabled(!busy);
        game.setEnabled(!busy);
        logout.setEnabled(!busy);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText edit(String hint, int type) {
        EditText view = new EditText(this);
        view.setSingleLine();
        view.setInputType(type);
        view.setHint(hint);
        view.setTextColor(DARK);
        view.setHintTextColor(MUTED);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private void add(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        parent.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 本地模拟商品展示项。 */
    private static final class Product {
        final String id;
        final long amount;
        final long reward;

        Product(JSONObject product) {
            id = product.optString("productId");
            amount = product.optLong("amount");
            reward = product.optLong("reward");
        }
    }

}
