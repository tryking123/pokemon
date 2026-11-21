package com.pokemon.local.embedded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.account.AccountService;
import com.pokemon.local.account.api.vo.LoginVO;
import com.pokemon.local.bootstrap.BootstrapService;
import com.pokemon.local.bootstrap.api.vo.RoleHistoryVO;
import com.pokemon.local.common.ApiException;
import com.pokemon.local.gateway.GameTicketService;
import com.pokemon.local.inventory.ProfileMailService;
import com.pokemon.local.inventory.api.vo.ProfileMailVO;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.PaymentService;
import com.pokemon.local.payment.api.vo.ConfirmGameOrderVO;
import com.pokemon.local.payment.api.vo.CreateOrderVO;
import com.pokemon.local.payment.api.vo.GetOrderIdVO;
import com.qk.local.LocalApi;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Unity 回环启动、资源、支付及 Android 进程内 API 分派
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:47
 */
public final class EmbeddedHttp implements LocalApi.InProcessTransport, Closeable {
    private static final Logger LOG = Logger.getLogger(EmbeddedHttp.class.getName());
    private static final String LOOPBACK = "127.0.0.1";
    private static final String JSON = "application/json;charset=UTF-8";
    private static final String TEXT = "text/plain;charset=UTF-8";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final int MAX_BODY = 65_536;
    private static final int MAX_HEADER = 16_384;
    private static final int MAX_LINE = 8_192;
    private static final int SOCKET_TIMEOUT = 12_000;
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final BootstrapService bootstrap;
    private final AccountService accounts;
    private final PaymentService payments;
    private final GamePaymentService gamePayments;
    private final ProfileMailService profileMail;
    private final GameTicketService tickets;
    private final ObjectMapper json;
    private final EmbeddedStore store;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread worker = new Thread(runnable, "pokemon-http-request");
                worker.setDaemon(true);
                return worker;
            });
    private volatile ServerSocket listener;

    /**
     * 绑定已初始化的本地业务服务
     *
     * @param bootstrap 游戏启动参数与资源
     * @param accounts 本地账号和 Android 会话
     * @param payments 工作台模拟钱包
     * @param gamePayments 原游戏角色订单
     * @param profileMail 个人物品邮件
     * @param tickets 本地游戏登录票据
     * @param json VO 和业务应答的 JSON 编解码
     * @param store 与游戏网关共用的安卓数据库事务边界
     */
    public EmbeddedHttp(BootstrapService bootstrap, AccountService accounts, PaymentService payments,
                        GamePaymentService gamePayments, ProfileMailService profileMail, GameTicketService tickets, ObjectMapper json,
                        EmbeddedStore store) {
        this.bootstrap = bootstrap;
        this.accounts = accounts;
        this.payments = payments;
        this.gamePayments = gamePayments;
        this.profileMail = profileMail;
        this.tickets = tickets;
        this.json = json;
        this.store = store;
    }

    /**
     * 在固定回环地址打开监听并启动接收线程
     *
     * @param port 单机安装包配置中的回环端口
     * @throws IOException 监听端口不可用
     */
    public synchronized void start(int port) throws IOException {
        if (listener != null || workers.isShutdown()) {
            throw new IllegalStateException("本地 HTTP 已启动或已关闭");
        }
        ServerSocket server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 32);
        } catch (IOException failure) {
            server.close();
            throw failure;
        }
        listener = server;
        Thread acceptor = new Thread(() -> accept(server), "pokemon-http-listener");
        acceptor.setDaemon(true);
        acceptor.start();
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
        Reply reply = route(method, path, jsonBody, authorization, JSON);
        return new LocalApi.LocalResponse(reply.status, new String(reply.body, StandardCharsets.UTF_8));
    }

    /**
     * 关闭监听，已分派的短连接自行结束
     *
     * @throws IOException 关闭监听失败
     */
    @Override
    public synchronized void close() throws IOException {
        workers.shutdown();
        ServerSocket current = listener;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 接收本地客户端，使用有界队列交给工作线程
     *
     * @param server 已绑定回环的监听套接字
     */
    private void accept(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                try {
                    workers.execute(() -> serve(socket));
                } catch (RejectedExecutionException busy) {
                    socket.close();
                }
            } catch (IOException closed) {
                return;
            }
        }
    }

    /**
     * 解析和处理同一条 HTTP 短连接
     *
     * @param socket 客户端套接字
     */
    private void serve(Socket socket) {
        try (Socket link = socket) {
            link.setSoTimeout(SOCKET_TIMEOUT);
            InputStream input = new BufferedInputStream(link.getInputStream());
            OutputStream output = new BufferedOutputStream(link.getOutputStream());
            String method = "";
            Reply reply;
            try {
                String line = readLine(input);
                if (line.isEmpty()) {
                    return;
                }
                String[] requestLine = line.split(" ", -1);
                if (requestLine.length != 3 || !requestLine[2].matches("HTTP/1\\.[01]")) {
                    throw new IllegalArgumentException("请求行无效");
                }
                method = requestLine[0];
                Map<String, String> headers = headers(input);
                int size = contentLength(headers);
                if ("100-continue".equalsIgnoreCase(headers.get("expect")) && size > 0) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                }
                String body = size == 0 ? null : new String(readBody(input, size), StandardCharsets.UTF_8);
                String routeMethod = "HEAD".equals(method) ? "GET" : method;
                reply = route(routeMethod, requestLine[1], body, headers.get("authorization"),
                        headers.get("content-type"));
            } catch (IllegalArgumentException | EOFException invalid) {
                reply = error(400, "INVALID_REQUEST", "本地 HTTP 请求无效");
            }
            write(output, reply, "HEAD".equals(method));
        } catch (IOException disconnected) {
            // Unity 取消资源加载或应用关停时会主动关闭连接。
        }
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
            LOG.log(Level.WARNING, "本地 HTTP 路由处理失败", failure);
            return error(500, "LOCAL_SERVICE_ERROR", "本地服务处理失败");
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
     * 读取有限制的 HTTP 请求头
     *
     * @param input 请求字节流
     * @return 小写头字段和值
     * @throws IOException 读取失败
     */
    private Map<String, String> headers(InputStream input) throws IOException {
        Map<String, String> result = new HashMap<>();
        int total = 0;
        for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
            total += line.length() + 2;
            int colon = line.indexOf(':');
            if (total > MAX_HEADER || colon <= 0 || !HEADER_NAME.matcher(line.substring(0, colon)).matches()) {
                throw new IllegalArgumentException("请求头无效");
            }
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (result.put(key, line.substring(colon + 1).trim()) != null
                    && ("content-length".equals(key) || "authorization".equals(key))) {
                throw new IllegalArgumentException("重复的请求头");
            }
        }
        return result;
    }

    /**
     * 取得一次有限长度请求体的实际字节数
     *
     * @param headers HTTP 请求头
     * @return 已校验正文长度
     */
    private int contentLength(Map<String, String> headers) {
        if (headers.containsKey("transfer-encoding")) {
            throw new IllegalArgumentException("不支持分块请求");
        }
        String value = headers.get("content-length");
        if (value == null) {
            return 0;
        }
        if (!value.matches("[0-9]{1,6}")) {
            throw new IllegalArgumentException("正文长度无效");
        }
        int length = Integer.parseInt(value);
        if (length > MAX_BODY) {
            throw new IllegalArgumentException("正文过长");
        }
        return length;
    }

    /**
     * 读取一个 CRLF 结尾的 HTTP 行
     *
     * @param input HTTP 字节流
     * @return 去掉 CRLF 的 ASCII 行
     * @throws IOException 请求被截断
     */
    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int size = 0; size <= MAX_LINE; size++) {
            int read = input.read();
            if (read == '\r' && input.read() == '\n') {
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (read < 0 || read == '\r' || read == '\n' || read > 127) {
                throw new EOFException("HTTP 请求行被截断");
            }
            line.write(read);
        }
        throw new IllegalArgumentException("HTTP 请求行过长");
    }

    /**
     * 读取完整正文，短写视为无效请求
     *
     * @param input HTTP 字节流
     * @param size 此请求应有的字节数
     * @return 原请求体
     * @throws IOException 输入不完整
     */
    private byte[] readBody(InputStream input, int size) throws IOException {
        byte[] body = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(body, offset, size - offset);
            if (count < 0) {
                throw new EOFException("HTTP 请求体被截断");
            }
            offset += count;
        }
        return body;
    }

    /**
     * 以明确字节长度返回少量 HTTP 头及原始正文
     *
     * @param output 本地套接字写入流
     * @param reply 路由产生的应答
     * @param headersOnly 是否只发头
     * @throws IOException 写入失败
     */
    private void write(OutputStream output, Reply reply, boolean headersOnly) throws IOException {
        String status = reply.status < 300 ? "OK" : "Request Failed";
        String headers = "HTTP/1.1 " + reply.status + " " + status + "\r\n"
                + "Content-Type: " + reply.type + "\r\n"
                + "Content-Length: " + reply.body.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!headersOnly) {
            output.write(reply.body);
        }
        output.flush();
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
