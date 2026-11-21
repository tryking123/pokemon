package com.qk.local;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地游戏联调服务客户端
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:00
 */
public final class LocalApi {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile Session session;
    private static volatile InProcessTransport inProcess;

    private LocalApi() {
    }

    /**
     * 使用应用内与回环 HTTP 端共用的业务路由
     *
     * @param transport 进程内本地服务分派器
     */
    public static synchronized void installInProcessTransport(InProcessTransport transport) {
        if (transport == null || (inProcess != null && inProcess != transport)) {
            throw new IllegalStateException("本地业务路由无效或已初始化");
        }
        inProcess = transport;
    }

    /**
     * Android 工作台和 SDK 登录支付使用的应用内业务路由接口
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:44
     */
    public interface InProcessTransport {
        /**
         * 在后台线程分派与 Unity 回环 HTTP 相同的本地 API
         *
         * @param method HTTP 方法
         * @param path 以斜杠开始的本地相对 API 路径
         * @param jsonBody JSON 请求正文，没有正文时为空
         * @param authorization 完整 Bearer 授权头，没有会话时为空
         * @return 业务应答状态和 JSON 正文
         * @throws Exception 本地路由无法处理该请求
         */
        LocalResponse request(String method, String path, String jsonBody, String authorization) throws Exception;
    }

    /**
     * 进程内路由与回环 HTTP 共用的应答形式
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:44
     */
    public static final class LocalResponse {
        public final int status;
        public final String json;

        /**
         * 创建与现有 SDK API 兼容的状态和 JSON 应答
         *
         * @param status HTTP 语义的状态码
         * @param json JSON 正文
         */
        public LocalResponse(int status, String json) {
            this.status = status;
            this.json = json == null ? "" : json;
        }
    }

    /** 当前登录会话，仅保存在应用内存中。 */
    static final class Session {
        final String token;
        final String accountId;
        final String username;

        Session(JSONObject result) throws Exception {
            token = result.getString("token");
            accountId = result.getString("accountId");
            username = result.getString("username");
            if (token.isEmpty() || accountId.isEmpty()) {
                throw new IOException("服务端返回的登录凭据无效");
            }
        }
    }

    /** 交给 Unity 网关登录的短期票据。 */
    static final class GameTicket {
        final String ticket;
        final String accountId;
        final String username;

        GameTicket(JSONObject result, Session current) throws Exception {
            ticket = result.getString("ticket");
            accountId = result.getString("accountId");
            username = current.username;
            if (!current.accountId.equals(accountId) || !ticket.matches("[A-Za-z0-9_-]{16,256}")) {
                throw new IOException("本地服务返回的游戏票据无效");
            }
        }
    }

    /** HTTP 业务响应错误，保留状态以识别过期会话。 */
    static final class ResponseException extends IOException {
        final int status;
        final String code;

        ResponseException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** 后台工作。 */
    interface Work<T> {
        T run() throws Exception;
    }

    /** 主线程结果回调。 */
    interface Result<T> {
        void accept(T value, Exception failure);
    }

    /** 在后台执行 HTTP，活动仍有效时在主线程提交结果。 */
    static <T> void run(Activity activity, Work<T> work, Result<T> result) {
        WORKER.execute(() -> {
            try {
                T value = work.run();
                deliver(activity, result, value, null);
            } catch (Exception failure) {
                deliver(activity, result, null, failure);
            }
        });
    }

    private static <T> void deliver(Activity activity, Result<T> result, T value, Exception failure) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                result.accept(value, failure);
            }
        });
    }

    /** 获取当前会话标识。 */
    static Session currentSession() {
        return session;
    }

    /** 校验账号密码并更新本地会话。 */
    static JSONObject login(String username, String password) throws Exception {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        JSONObject result = (JSONObject) request("POST", "/api/auth/login", body, null);
        session = new Session(result);
        return result;
    }

    /** 结束服务端会话。 */
    static void logout() throws Exception {
        Session current = session;
        if (current != null) {
            request("POST", "/api/auth/logout", null, current);
            session = null;
        }
    }

    /** 读取账号与余额。 */
    static JSONObject account() throws Exception {
        return (JSONObject) request("GET", "/api/account/me", null, requireSession());
    }

    /** 使用本地 HTTP 会话签发一张可交给游戏网关的票据。 */
    static GameTicket gameTicket() throws Exception {
        Session current = requireSession();
        JSONObject result = (JSONObject) request("POST", "/api/game/ticket", null, current);
        return new GameTicket(result, current);
    }

    /** 读取服务端模拟充值目录。 */
    static JSONArray products() throws Exception {
        return (JSONArray) request("GET", "/api/payments/products", null, null);
    }

    /** 用客户端订单号重试创建，并确认同一服务端订单。 */
    static JSONObject simulatePurchase(Session expected, String productId, String clientOrderId) throws Exception {
        Session current = requireSameSession(expected);
        JSONObject body = new JSONObject().put("productId", productId).put("clientOrderId", clientOrderId);
        JSONObject created = (JSONObject) request("POST", "/api/payments/orders", body, current);
        String orderId = created.getString("orderId");
        if (!orderId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IOException("服务端返回的订单编号无效");
        }
        JSONObject confirmed = (JSONObject) request(
                "POST", "/api/payments/orders/" + orderId + "/confirm", null, current);
        if (!"CONFIRMED".equals(confirmed.getString("status"))) {
            throw new IOException("服务端尚未确认模拟订单");
        }
        return confirmed;
    }

    /** 询价及确认均使用服务端返回的同一个 Unity 角色订单号。 */
    static JSONObject gameOrder(Session expected, String orderId, int paysid, int realmId, String roleId,
                                boolean confirm) throws Exception {
        Session current = requireSameSession(expected);
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("服务端返回的游戏订单编号无效");
        }
        JSONObject body = new JSONObject().put("roleId", roleId).put("realmId", realmId).put("paysid", paysid);
        String suffix = confirm ? "/confirm" : "/quote";
        JSONObject order = (JSONObject) request("POST", "/api/game/payments/orders/" + orderId + suffix, body, current);
        if (!orderId.equals(order.optString("orderId")) || !roleId.equals(order.optString("roleId"))
                || order.optInt("paysid") != paysid) {
            throw new IOException("服务端返回的角色订单不匹配");
        }
        return order;
    }

    /**
     * 确认个人信息页的物品发放，将附件保存到该登录账号的角色邮箱。
     * @param expected 打开弹窗时的本地会话
     * @param roleId 当前角色
     * @param requestId 本次发放回执
     * @param type 邮件附件类别
     * @param itemId 配置编号
     * @param quantity 发放件数
     * @return 邮件编号
     * @throws Exception 登录失效或邮件写入失败
     */
    static String sendItemMail(Session expected, String roleId, String requestId, int type, int itemId, long quantity) throws Exception {
        JSONObject body = new JSONObject().put("roleId", roleId).put("requestId", requestId)
                .put("type", type).put("itemId", itemId).put("quantity", quantity);
        JSONObject result = (JSONObject) request("POST", "/api/game/profile/item-mail", body, requireSameSession(expected));
        return result.getString("mailId");
    }

    /** 展示可读错误，网络失败时给出本地连接提示。 */
    static String errorText(Exception failure) {
        if (failure instanceof java.net.ConnectException || failure instanceof java.net.SocketTimeoutException) {
            return "游戏的本地服务未就绪，请退出游戏后重试";
        }
        return failure.getMessage() != null ? failure.getMessage() : "请求失败，请重试";
    }

    private static Session requireSession() throws IOException {
        Session current = session;
        if (current == null) {
            throw new IOException("请先登录本地账号");
        }
        return current;
    }

    private static Session requireSameSession(Session expected) throws IOException {
        Session current = requireSession();
        if (current != expected) {
            throw new IOException("登录会话已变更，请重新发起充值");
        }
        return current;
    }

    private static Object request(String method, String path, JSONObject body, Session auth) throws Exception {
        InProcessTransport direct = inProcess;
        if (direct == null) {
            throw new IOException("应用内单机模块尚未初始化");
        }
        String jsonBody = body == null ? null : body.toString();
        String authorization = auth == null ? null : "Bearer " + auth.token;
        LocalResponse response = direct.request(method, path, jsonBody, authorization);
        if (response == null || response.json.length() > 65536) {
            throw new IOException("本地服务应答无效");
        }
        if (response.status != 200) {
            if (response.status == 401 && auth != null && session == auth) {
                session = null;
            }
            String message = "本地服务请求失败（HTTP " + response.status + "）";
            String code = "";
            try {
                JSONObject error = new JSONObject(response.json);
                message = error.optString("message", message);
                code = error.optString("code");
            } catch (Exception ignored) {
                // 本地服务启动期间可能没有 JSON 应答。
            }
            throw new ResponseException(response.status, code, message);
        }
        return new JSONTokener(response.json).nextValue();
    }

}
