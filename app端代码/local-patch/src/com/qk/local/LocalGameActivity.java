package com.qk.local;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.unity3d.player.UnityPlayer;
import com.unity3d.player.UnityPlayerActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Unity 游戏入口和本地账号、模拟充值桥
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:00
 */
public final class LocalGameActivity extends UnityPlayerActivity {
    private static final String TAG = "PokemonLocalBridge";
    private String unityObject;
    private boolean initPending;
    private boolean loginShowing;
    private boolean ticketInProgress;
    private boolean paymentInProgress;

    /** 记录 Unity 启动边界，用于识别游戏自己的资源准备阶段。 */
    @Override
    protected void onCreate(Bundle state) {
        try {
            UnityMetadataCache.prepare(this);
        } catch (IOException failure) {
            throw new IllegalStateException("无法校验 Unity 启动配置缓存", failure);
        }
        Log.i(TAG, "Starting Unity player");
        super.onCreate(state);
        Log.i(TAG, "Unity player activity initialized");
    }

    /** 注册 Unity 游戏对象并补发可能早到的初始化通知。 */
    public void setUnityGameObjectName(String gameObjectName) {
        runOnUiThread(() -> {
            unityObject = gameObjectName;
            Log.i(TAG, "Unity callback object registered");
            if (initPending) {
                requestInit();
            }
        });
    }

    /** 通知 Unity 本地 Android 桥可用。 */
    public void requestInit() {
        runOnUiThread(() -> {
            initPending = !ready();
            if (!initPending) {
                callUnityFunc("onInitSuccess", "{}");
                Log.i(TAG, "Local bridge initialized");
            }
        });
    }

    /** 兼容旧版 Unity 初始化入口。 */
    public void doInit() {
        requestInit();
    }

    /** 复用本地工作台会话签发游戏票据，或显示本地账号表单。 */
    public void requestLogin() {
        runOnUiThread(() -> {
            if (loginShowing || ticketInProgress) {
                return;
            }
            Log.i(TAG, "Unity requested local login");
            if (LocalApi.currentSession() == null) {
                showLoginDialog();
                return;
            }
            ticketInProgress = true;
            LocalApi.run(this, LocalApi::gameTicket, (ticket, failure) -> {
                ticketInProgress = false;
                if (failure == null) {
                    loginSuccess(ticket);
                } else if (LocalApi.currentSession() == null) {
                    showLoginDialog();
                } else {
                    loginFailure(LocalApi.errorText(failure));
                }
            });
        });
    }

    private void showLoginDialog() {
        if (loginShowing || isFinishing()) {
            return;
        }
        loginShowing = true;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText username = new EditText(this);
        username.setSingleLine();
        username.setHint("本地账号");
        username.setText("admin");
        EditText password = new EditText(this);
        password.setSingleLine();
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("本地密码（默认 admin）");
        fields.addView(username);
        fields.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("本地账号登录")
                .setView(fields)
                .setPositiveButton("登录", null)
                .setNegativeButton("取消", (window, which) -> loginFailure("cancel"))
                .setOnCancelListener(window -> loginFailure("cancel"))
                .create();
        dialog.setOnDismissListener(window -> loginShowing = false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String name = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (name.isEmpty() || secret.isEmpty()) {
                password.setError("请填写账号和密码");
                return;
            }
            button.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false);
            LocalApi.run(this, () -> {
                LocalApi.login(name, secret);
                return LocalApi.gameTicket();
            }, (ticket, failure) -> {
                if (failure == null) {
                    dialog.dismiss();
                    loginSuccess(ticket);
                } else {
                    password.setError(LocalApi.errorText(failure));
                    button.setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.setCancelable(true);
                }
            });
        });
    }

    private void loginSuccess(LocalApi.GameTicket ticket) {
        LocalApi.Session session = LocalApi.currentSession();
        if (session == null || !session.accountId.equals(ticket.accountId)) {
            loginFailure("本地登录会话已结束");
            return;
        }
        // Unity 从 userToken 读取网关票据。本地 HTTP 的 Bearer 留在 Android。
        callUnityFunc("onLoginSuccess", payload(
                "userName", ticket.username, "userId", ticket.accountId, "userToken", ticket.ticket,
                "channelToken", "", "msg", "success"));
    }

    private void loginFailure(String reason) {
        callUnityFunc("onLoginFailed", payload("msg", reason));
        if (!"cancel".equals(reason)) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        }
    }

    /** 注销 Android 本地会话。 */
    public void requestLogout() {
        LocalApi.run(this, () -> {
            LocalApi.logout();
            return null;
        }, (result, failure) -> {
            if (failure == null || LocalApi.currentSession() == null) {
                callUnityFunc("onLogoutSuccess", "success");
            } else {
                Toast.makeText(this, LocalApi.errorText(failure), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 与原 QuickSDK 代理一致的 Unity→Android 消息号。 */
    private static final int MSG_INIT = 120;
    private static final int MSG_LOGIN = 101;
    private static final int MSG_LOGOUT = 102;
    private static final int MSG_PAY = 103;
    private static final int MSG_EXIT = 104;
    private static final int MSG_ROLEINFO = 105;

    /** 与原版代理一致：不声明 callFunc 能力，Unity 走既有本地桥路径（旧版本实测可正常进主城）。 */
    public boolean isFuncSupport(int type) {
        return false;
    }

    /**
     * 原 QuickSDK 代理的 {@code callFunc(msgType, data)} 入口。
     * 旧版本此实现为桩（返回 0、不触发任何逻辑），Unity 在 {@code isFuncSupport=false} 时走既有本地桥路径，
     * 实测可正常登录进主城；此处保持原语义，避免改变 Unity 侧的能力探测分支。
     */
    public int callFunc(int type, String argument) {
        Log.i(TAG, "Unity callFunc type=" + type);
        return 0;
    }

    /** 原代理的无参能力查询；与原版一致，非扩展能力统一返回 "0"。 */
    public String callFunc(int type) {
        return "0";
    }

    /** 原代理的结果查询入口，本地版按不支持扩展返回。 */
    public String callFuncWithResult(int type) {
        return callFunc(type);
    }

    /**
     * 生命周期只交给父类 {@link UnityPlayerActivity} 处理，再显式转发 UnityPlayer 的 pause/resume。
     * 原 APK 中 pause()/resume() 为实例方法（作用于 UnityPlayerActivity 的 mUnityPlayer），
     * 若按静态方式调用会抛 IncompatibleClassChangeError；因此这里用反射按“实例方法”调用，
     * 找不到方法时安全跳过（支付弹窗期间心跳由本地服务 250ms pump 兜底）。
     */
    @Override
    protected void onPause() {
        super.onPause();
        dispatchUnityPlayer("pause");
    }

    /** 见 {@link #onPause()} 的说明。 */
    @Override
    protected void onResume() {
        super.onResume();
        dispatchUnityPlayer("resume");
    }

    /** 反射调用 UnityPlayer 的实例方法 pause()/resume()；方法或实例缺失时静默跳过，不改变原有生命周期行为。 */
    private void dispatchUnityPlayer(String method) {
        try {
            Object player = getFieldSafely("mUnityPlayer");
            if (player == null) {
                return;
            }
            java.lang.reflect.Method target = player.getClass().getMethod(method);
            target.invoke(player);
        } catch (Throwable ignored) {
            Log.w(TAG, "UnityPlayer." + method + " 转发失败，已跳过", ignored);
        }
    }

    /** 读取父类 UnityPlayerActivity 的 mUnityPlayer 字段，字段缺失时返回 null。 */
    private Object getFieldSafely(String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = UnityPlayerActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(this);
    }

    /**
     * 保留原 Unity 支付入口的十九个字符串参数，读取已由本地服务创建的游戏角色订单。
     * 结算只使用App保存的价格及发放数量。
     */
    public void requestPay(String productId, String productName, String description, String unit,
                           String cpOrderId, String callbackUrl, String extraParam, String price,
                           String amount, String count, String serverName, String serverId,
                           String roleName, String roleId, String gameBalance, String vip,
                           String level, String party, String roleCreatedAt) {
        runOnUiThread(() -> preparePayment(productId, cpOrderId, extraParam, serverId, roleId));
    }

    private void preparePayment(String productId, String cpOrderId, String extraParam, String serverId, String roleId) {
        Log.i(TAG, "Unity requested local simulated purchase");
        LocalApi.Session session = LocalApi.currentSession();
        if (session == null || cpOrderId == null || cpOrderId.trim().isEmpty()
                || roleId == null || roleId.isEmpty() || productId == null || serverId == null) {
            payFailure(cpOrderId, extraParam, "请先登录，并使用有效的商品和订单编号");
            return;
        }
        if (paymentInProgress) {
            return;
        }
        final int paysid;
        final int realmId;
        try {
            paysid = Integer.parseInt(productId.trim());
            realmId = Integer.parseInt(serverId.trim());
        } catch (NumberFormatException invalid) {
            payFailure(cpOrderId, extraParam, "充值档位或区服编号无效");
            return;
        }
        paymentInProgress = true;
        LocalApi.run(this, () -> LocalApi.gameOrder(session, cpOrderId, paysid, realmId, roleId, false), (order, failure) -> {
            if (failure != null) {
                paymentInProgress = false;
                payFailure(cpOrderId, extraParam, LocalApi.errorText(failure));
                return;
            }
            if (order == null) {
                paymentInProgress = false;
                payFailure(cpOrderId, extraParam, "App返回的模拟订单无效");
                return;
            }
            if ("CONFIRMED".equals(order.optString("status"))) {
                paymentInProgress = false;
                paySuccess(cpOrderId, extraParam, order);
                return;
            }
            long reward = order.optLong("reward");
            long amount = order.optLong("amount");
            JSONObject items = order.optJSONObject("items");
            boolean hasItems = items != null && items.length() > 0;
            boolean activatesFund = order.optBoolean("activatesFund");
            boolean claimInTimedShop = order.optBoolean("claimInTimedShop");
            boolean claimInFestival = order.optBoolean("claimInFestival");
            boolean hasVoucher = order.optLong("voucherReward", 0) > 0;
            boolean blindBox = order.optInt("blindBoxIndex", 0) > 0;
            if (!"PENDING".equals(order.optString("status")) || reward < 0
                    || (reward == 0 && !hasItems && !activatesFund && !claimInTimedShop && !claimInFestival && !blindBox && !hasVoucher) || amount <= 0) {
                paymentInProgress = false;
                payFailure(cpOrderId, extraParam, "App返回的模拟订单无效");
                return;
            }
            confirmPayment(session, cpOrderId, extraParam, paysid, realmId, roleId);
        });
    }

    private void confirmPayment(LocalApi.Session session, String cpOrderId, String extraParam,
                                int paysid, int realmId, String roleId) {
        LocalApi.run(this,
                () -> LocalApi.gameOrder(session, cpOrderId, paysid, realmId, roleId, true),
                (order, failure) -> {
                    paymentInProgress = false;
                    if (failure != null) {
                        payFailure(cpOrderId, extraParam, LocalApi.errorText(failure));
                    } else if (order == null || !"CONFIRMED".equals(order.optString("status"))) {
                        payFailure(cpOrderId, extraParam, "App尚未确认游戏订单");
                    } else {
                        paySuccess(cpOrderId, extraParam, order);
                    }
                });
    }

    private void paySuccess(String cpOrderId, String extraParam, JSONObject order) {
        callUnityFunc("onPaySuccess", payload(
                "orderId", order.optString("orderId"), "cpOrderId", cpOrderId, "extraParam", extraParam));
        if (order.optBoolean("claimInFestival")) {
            String message = order.optBoolean("festivalCollected") ? "该订单的宝可梦节日礼包已领取" : "模拟支付已确认，可在宝可梦节日页面领取";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (order.optBoolean("claimInTimedShop")) {
            String message = order.optBoolean("shopCollected") ? "该订单的限时商城礼包已领取" : "模拟支付已确认，可在限时商城领取";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (order.optBoolean("activatesFund")) {
            Toast.makeText(this, "成长基金已激活，可按等级领取奖励", Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject items = order.optJSONObject("items");
        String detail = items != null && items.length() > 0 ? "，礼包道具已发放" : "";
        Toast.makeText(this, "模拟充值已到账" + detail + "。当前角色钻石 " + order.optLong("balance"),
                Toast.LENGTH_LONG).show();
    }

    private void payCancel(String cpOrderId) {
        paymentInProgress = false;
        callUnityFunc("onPayCancel", payload("orderId", "", "cpOrderId", cpOrderId, "extraParam", ""));
    }

    private void payFailure(String cpOrderId, String extraParam, String reason) {
        callUnityFunc("onPayFailed", payload(
                "orderId", "", "cpOrderId", cpOrderId, "extraParam", extraParam, "msg", reason));
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
    }

    /** 发送原桥 ABI 的消息。 */
    public void callUnityFunc(String method, String payload) {
        if (ready()) {
            UnityPlayer.UnitySendMessage(unityObject, method, payload);
        }
    }

    private boolean ready() {
        return unityObject != null && !unityObject.isEmpty();
    }

    private static String payload(String... fields) {
        JSONObject value = new JSONObject();
        try {
            for (int index = 0; index < fields.length; index += 2) {
                value.put(fields[index], fields[index + 1] == null ? "" : fields[index + 1]);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return value.toString();
    }

    /** 保留原扩展入口，避免 Unity 反射查找失败。 */
    public void requestExit() { runOnUiThread(() -> callUnityFunc("onExitSuccess", "success")); }
    public void exitGame() { runOnUiThread(this::finish); }
    public void showPrivace() { runOnUiThread(() -> callUnityFunc("onPrivaceAgree", "agree")); }
    public String getUserId() {
        LocalApi.Session session = LocalApi.currentSession();
        return session == null ? null : session.accountId;
    }
    public String getProductCode() { return ""; }
    public String getProductKey() { return ""; }
    public String getChannelName() { return "local"; }
    public String getChannelVersion() { return "local"; }
    public int getChannelType() { return 0; }
    public String getParentChannelType() { return "0"; }
    public String getSDKVersion() { return "local"; }
    public String getConfigValue(String key) { return "quicksdk_channel_name".equals(key) ? "local" : ""; }
    public boolean isChannelHasExitDialog() { return false; }
    public String getDeviceID() { return ""; }
    public String getOaid() { return ""; }
    public int callTest(Object value) { return 1; }
    /**
     * 转发带参数的扩展调用，未提供参数时按无参数处理。
     *
     * @param type 扩展调用类型
     * @param arguments 扩展参数，可为空数组
     * @return 扩展调用结果
     */
    public int callFunctionWithParams(int type, String[] arguments) {
        return callFunc(type, arguments == null || arguments.length == 0 ? null : arguments[0]);
    }
    public void requestExtendFunc(int type) { }
    public void requestExtendCallPlugin(int type, String[] arguments) { }
    public void requestCallCustomPlugin(String a, String b, String c, String d) { }
    public void requestCallFunctionWithParamsCallBack(int type, String[] arguments) {
        runOnUiThread(() -> callUnityFunc("onBaseCallbackFaild", "本地版不支持该扩展功能"));
    }
    public void requestCallSDKShare(String a, String b, String c, String d, String e, String f, String g, String h) { }
    public void requestUpdateRole(String serverId, String serverName, String roleName, String roleId,
                                  String balance, String vip, String level, String party, String created,
                                  String gender, String power, String partyId, String professionId,
                                  String profession, String secondaryPartyId, String partyRole,
                                  String friends, String newRole) { }
    public void showMsg(String value) { }
}
