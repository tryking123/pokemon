package com.qk.local;

import android.app.Activity;
import android.app.AlertDialog;
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
        logout = button("退出本地账号", this::logout);
        add(wallet, logout, 12);

        status = text("", 14, DARK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 16);
        renderControls();
    }

    /** 返回工作台时刷新服务端余额。 */
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
        if (session != null) {
            balance.setText(session.username + "\n本地模拟钻石  " + account.optLong("balance"));
        }
    }

    private void showProducts(JSONArray result) {
        catalog.clear();
        List<String> labels = new ArrayList<>();
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
        new AlertDialog.Builder(this)
                .setTitle("确认模拟充值")
                .setMessage("发放 " + product.reward + " 本地模拟钻石。本订单不会扣款。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认模拟", (dialog, which) -> {
                    if (LocalApi.currentSession() != session) {
                        status.setText("登录会话已变更，请重新发起充值");
                        return;
                    }
                    String clientOrderId = "android-" + UUID.randomUUID();
                    boolean saved = orders.edit().putString("clientOrderId", clientOrderId)
                            .putString("productId", product.id).commit();
                    if (saved) {
                        purchase(session, product.id, clientOrderId);
                    } else {
                        status.setText("无法保存订单编号，请重试");
                    }
                })
                .show();
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
        return getSharedPreferences("local_pending_" + key, MODE_PRIVATE);
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
