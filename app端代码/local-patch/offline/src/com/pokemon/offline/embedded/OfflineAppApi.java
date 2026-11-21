package com.pokemon.offline.embedded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.account.api.vo.LoginVO;
import com.pokemon.offline.bootstrap.BootstrapService;
import com.pokemon.offline.bootstrap.api.vo.RoleHistoryVO;
import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.gateway.GameTicketService;
import com.pokemon.offline.inventory.ProfileMailService;
import com.pokemon.offline.inventory.api.vo.ProfileMailVO;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.payment.PaymentService;
import com.pokemon.offline.payment.api.vo.ConfirmGameOrderVO;
import com.pokemon.offline.payment.api.vo.CreateOrderVO;
import com.pokemon.offline.payment.api.vo.GetOrderIdVO;
import com.qk.local.LocalApi;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * App 离线启动、资源、支付及进程内业务分派，不监听网络端口
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:47
 */
public final class OfflineAppApi implements LocalApi.InProcessTransport {
    private static final Logger LOG = Logger.getLogger(OfflineAppApi.class.getName());
    private static final String LOOPBACK = "127.0.0.1";
    private static final String JSON = "application/json;charset=UTF-8";
    private static final String TEXT = "text/plain;charset=UTF-8";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final int MAX_BODY = 65_536;
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final BootstrapService bootstrap;
    private final AccountService accounts;
    private final PaymentService payments;
    private final GamePaymentService gamePayments;
    private final ProfileMailService profileMail;
    private final com.pokemon.offline.home.PetCustomizationService petCustomization;
    private final GameTicketService tickets;
    private final ObjectMapper json;
    private final EmbeddedStore store;
    /**
     * 绑定已初始化的本地业务服务
     *
     * @param bootstrap 游戏启动参数与资源
     * @param accounts 本地账号和 Android 会话
     * @param payments 工作台模拟钱包
     * @param gamePayments 原游戏角色订单
     * @param profileMail 个人物品邮件
     * @param tickets 本地游戏登录票据
     * @param petCustomization 当前宠物定制
     * @param json VO 和业务应答的 JSON 编解码
     * @param store 与游戏网关共用的安卓数据库事务边界
     */
    public OfflineAppApi(BootstrapService bootstrap, AccountService accounts, PaymentService payments,
                        GamePaymentService gamePayments, ProfileMailService profileMail, GameTicketService tickets,
                        com.pokemon.offline.home.PetCustomizationService petCustomization, ObjectMapper json,
                        EmbeddedStore store) {
        this.bootstrap = bootstrap;
        this.accounts = accounts;
        this.payments = payments;
        this.gamePayments = gamePayments;
        this.profileMail = profileMail;
        this.tickets = tickets;
        this.petCustomization = petCustomization;
        this.json = json;
        this.store = store;
    }

    /**
     * 直接分派 Android 的 JSON API，共用回环 HTTP 的校验和路由
     *
     * @param method HTTP 方法
     * @param path 本地 API 相对路径
     * @param jsonBody JSON 正文，没有正文时为空
     * @param authorization 完整 Bearer 请求头
     * @return 与 HTTP 等价的 JSON 状态和正文
     */
    @Override
    public LocalApi.LocalResponse request(String method, String path, String jsonBody, String authorization) {
        return request(method, path, jsonBody, authorization, JSON);
    }

    /**
     * 分派 Unity 直接提交的 JSON 或兼容表单请求。
     * @param method 操作方法
     * @param path 操作路径
     * @param body 操作参数
     * @param authorization 本地会话凭据
     * @param contentType 参数编码
     * @return 操作结果
     */
    public LocalApi.LocalResponse request(String method, String path, String body, String authorization, String contentType) {
        Reply reply = route(method, path, body, authorization, contentType);
        byte[] resultBody = reply.body == null ? new byte[0] : reply.body;
        return new LocalApi.LocalResponse(reply.status, new String(resultBody, StandardCharsets.UTF_8));
    }

    /**
     * 将输入路由到对应应用内服务，并统一业务错误格式
     *
     * @param method 请求方法
     * @param target 包含可选查询参数的相对路径
     * @param body UTF-8 请求正文
     * @param authorization Bearer 请求头
     * @param contentType 请求正文类型
     * @return HTTP 语义的应答
     */
    private Reply route(String method, String target, String body, String authorization, String contentType) {
        try {
            URI uri = new URI(target);
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getFragment() != null
                    || path == null || !path.startsWith("/")) {
                return error(400, "INVALID_PATH", "本地请求路径无效");
            }
            if ((path.startsWith("/api/") && !"/api/payments/products".equals(path))
                    || "/getorderid".equals(path) || "/get_my_plyer".equals(path)) {
                return store.write(() -> dispatch(method, path, uri.getRawQuery(), body, authorization, contentType));
            }
            return dispatch(method, path, uri.getRawQuery(), body, authorization, contentType);
        } catch (ApiException failure) {
            return error(failure.getStatus().value(), failure.getCode(), failure.getMessage());
        } catch (JsonProcessingException invalid) {
            return error(400, "INVALID_JSON", "请提供有效的 JSON 请求体");
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", "本地请求参数无效");
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "App 离线操作失败", failure);
            return error(500, "LOCAL_SERVICE_ERROR", "App 离线操作失败");
        }
    }

    /**
     * 分派已经验证的路径，业务异常由外层连接在事务回滚后转换
     *
     * @param method HTTP 方法
     * @param path 已解码路径
     * @param query 原始查询参数
     * @param body UTF-8 请求正文
     * @param authorization Bearer 请求头
     * @param contentType 请求正文类型
     * @return 本地应答
     * @throws IOException 读取或编码失败
     */
    private Reply dispatch(String method, String path, String query, String body,
                           String authorization, String contentType) throws IOException {
        switch (method) {
            case "GET":
                return get(path, query, authorization);
            case "POST":
                return post(path, body, authorization, contentType);
            default:
                return error(405, "INVALID_METHOD", "不支持该请求方法");
        }
    }

    /**
     * 处理游戏启动配置、资源、区服和无需正文的 API
     *
     * @param path 已解码路径
     * @param query 原始查询参数
     * @param authorization Bearer 请求头
     * @return 本地应答
     * @throws IOException 无法读取或编码应答
     */
    private Reply get(String path, String query, String authorization) throws IOException {
        switch (path) {
            case "/api/game/pet/customize":
                Map<String,String> petParams=parameters(query);
                return json(200,petCustomization.read(authorization,petParams.get("roleId"),petParams.get("petId")));
            case "/health":
                return json(200, java.util.Collections.singletonMap("status", "UP"));
            case "/api/account/me":
                return json(200, accounts.me(authorization));
            case "/api/payments/products":
                return json(200, payments.products());
            case "/1.0.1/config.txt":
                return text(bootstrap.configuration());
            case "/realmlist":
                return text(bootstrap.realms());
            case "/notice.txt":
                return text(bootstrap.notice());
            case "/get_my_plyer":
                Map<String, String> params = parameters(query);
                RoleHistoryVO role = new RoleHistoryVO();
                role.setAccount(params.get("account"));
                role.setChannel(params.get("channel"));
                return json(200, bootstrap.roleHistory(role));
            default:
                if (path.startsWith("/resources/android/")) {
                    return resource(false, path.substring("/resources/android/".length()));
                }
                if (path.startsWith("/data/")) {
                    return resource(true, path.substring("/data/".length()));
                }
                return error(404, "NOT_FOUND", "未找到本地接口");
        }
    }

    /**
     * 处理账号、支付、票据及原版 WWWForm
     *
     * @param path 已解码路径
     * @param body UTF-8 请求正文
     * @param authorization Bearer 请求头
     * @param contentType 请求正文类型
     * @return 本地应答
     * @throws IOException 无法解析或编码正文
     */
    private Reply post(String path, String body, String authorization, String contentType) throws IOException {
        switch (path) {
            case "/api/game/pet/customize":
                return json(200,petCustomization.save(authorization,
                        decode(body,com.pokemon.offline.home.api.vo.PetCustomizeVO.class)));
            case "/api/auth/login":
                return json(200, accounts.login(decode(body, LoginVO.class)));
            case "/api/auth/logout":
                return json(200, accounts.logout(authorization));
            case "/api/game/profile/item-mail":
                return json(200, profileMail.send(authorization, decode(body, ProfileMailVO.class)));
            case "/api/game/ticket":
                return json(200, tickets.issue(authorization));
            case "/api/payments/orders":
                return json(200, payments.createOrder(authorization, decode(body, CreateOrderVO.class)));
            case "/getorderid":
                if (contentType == null || !FORM.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
                    return error(415, "INVALID_FORM", "请求应为 Unity 表单");
                }
                Map<String, String> params = parameters(body);
                return text(gamePayments.legacyOrder(new GetOrderIdVO(params.get("userid"), params.get("serverid"),
                        params.get("uid"), params.get("appname"), params.get("channel"), params.get("product_id")),
                        LOOPBACK));
            default:
                String[] segments = path.split("/", -1);
                if (segments.length == 7 && path.startsWith("/api/game/payments/orders/")
                        && ORDER_ID.matcher(segments[5]).matches()) {
                    if ("quote".equals(segments[6])) {
                        return json(200, gamePayments.quote(authorization, segments[5],
                                decode(body, ConfirmGameOrderVO.class)));
                    }
                    if ("confirm".equals(segments[6])) {
                        return json(200, gamePayments.confirm(authorization, segments[5],
                                decode(body, ConfirmGameOrderVO.class)));
                    }
                }
                if (segments.length == 6 && path.startsWith("/api/payments/orders/")
                        && ORDER_ID.matcher(segments[4]).matches() && "confirm".equals(segments[5])) {
                    return json(200, payments.confirmOrder(authorization, segments[4]));
                }
                return error(404, "NOT_FOUND", "未找到本地接口");
        }
    }

    /**
     * 读取底包已校验的逻辑资源并标注文本或二进制类型
     *
     * @param data 是否属于游戏数据区
     * @param name 来自清单的逻辑文件名
     * @return 原文件字节及对应 Content-Type
     */
    private Reply resource(boolean data, String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            return error(404, "NOT_FOUND", "未找到本地资源");
        }
        String type = "application/octet-stream";
        if (name.endsWith(".txt")) {
            type = TEXT;
        } else if (name.endsWith(".xml")) {
            type = "application/xml";
        } else if (name.endsWith(".json")) {
            type = JSON;
        }
        return new Reply(200, type, bootstrap.resource(data, name));
    }

    /**
     * 解码原工作台使用的请求对象
     *
     * @param body JSON 正文
     * @param form 目标 VO 类型
     * @param <T> 请求对象类型
     * @return 已解码的请求对象
     * @throws JsonProcessingException JSON 结构无效
     */
    private <T> T decode(String body, Class<T> form) throws JsonProcessingException {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少请求正文");
        }
        T data = json.readValue(body, form);
        if (data == null) {
            throw new IllegalArgumentException("缺少请求正文");
        }
        return data;
    }

    /**
     * 按表单规则解码查询串，不接收歧义重复项
     *
     * @param body 原始表单或查询串
     * @return 字段名和 UTF-8 值
     * @throws IOException 解码失败
     */
    private Map<String, String> parameters(String body) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (body != null && !body.isEmpty()) {
            for (String field : body.split("&")) {
                String[] pair = field.split("=", 2);
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", "UTF-8");
                if (result.put(key, value) != null) {
                    throw new IllegalArgumentException("重复的请求字段");
                }
            }
        }
        return result;
    }

    /**
     * 创建原 Unity 文本响应
     *
     * @param content UTF-8 正文
     * @return HTTP 200 文本
     */
    private Reply text(String content) {
        return new Reply(200, TEXT, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将业务结果编码为统一 JSON 应答
     *
     * @param status HTTP 状态
     * @param value 业务结果
     * @return JSON 应答
     * @throws JsonProcessingException 无法编码结果
     */
    private Reply json(int status, Object value) throws JsonProcessingException {
        return new Reply(status, JSON, json.writeValueAsBytes(value));
    }

    /**
     * 按既有 REST 契约生成错误
     *
     * @param status HTTP 状态
     * @param code 本地业务错误码
     * @param message 中文错误信息
     * @return JSON 错误响应
     */
    private Reply error(int status, String code, String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", message);
        try {
            return json(status, result);
        } catch (JsonProcessingException failure) {
            return new Reply(status, JSON, "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 本地路由返回的原始字节响应
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:47
     */
    private static final class Reply {
        private final int status;
        private final String type;
        private final byte[] body;

        /**
         * 创建类型明确的应答
         *
         * @param status HTTP 状态
         * @param type 媒体类型
         * @param body 原始响应正文
         */
        private Reply(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }
    }
}
