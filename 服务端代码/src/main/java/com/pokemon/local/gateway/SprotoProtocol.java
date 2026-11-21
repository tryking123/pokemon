package com.pokemon.local.gateway;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 守护小精灵游戏网关的请求、响应和推送封包
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:46
 */
@Component
final class SprotoProtocol {

    private final Map<Integer, String> requests = new HashMap<>();
    private final Map<Integer, String> responses = new HashMap<>();
    private final Map<Integer, String> pushes = new HashMap<>();
    private final Set<Integer> bodylessRequests = new HashSet<>();
    private final Map<Integer, String> failureFields = new HashMap<>();
    private final SprotoCodec codec;

    /**
     * 加载从原客户端提取的消息定义
     *
     * @throws IOException 协议资源读取失败
     */
    SprotoProtocol() throws IOException {
        Map<String, List<SprotoField>> types = new HashMap<>();
        ClassPathResource definition = new ClassPathResource("protocol/gateway.sproto.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(definition.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                loadLine(types, line.trim());
            }
        }
        codec = new SprotoCodec(types);
    }

    /**
     * 从已压缩消息获取客户端请求头与承载内容
     *
     * @param packed 已压缩的帧内容
     * @return 客户端请求
     * @throws IOException 数据格式无效
     */
    Request decodeRequest(byte[] packed) throws IOException {
        ByteBuffer source = SprotoCodec.unpack(packed);
        Map<String, Object> header = codec.decode("package", source);
        long type = number(header, "type", -1);
        long session = number(header, "session", 0);
        if (type < 0 || type > Integer.MAX_VALUE || session < 0) {
            throw new IOException("Sproto 请求头无效");
        }
        String name = requests.get((int) type);
        if (name == null || bodylessRequests.contains((int) type) || source.remaining() < 2) {
            // 原客户端以 nil 作为空请求内容，可能只携带 package。
            return new Request((int) type, session, Collections.emptyMap());
        }
        return new Request((int) type, session, codec.decode(name, source));
    }

    /**
     * 生成带原请求 session 的响应包
     *
     * @param request 客户端请求
     * @param body 响应字段
     * @return 已压缩响应数据
     * @throws IOException 响应数据无法编码
     */
    byte[] response(Request request, Map<String, Object> body) throws IOException {
        if (request.session <= 0 || !responses.containsKey(request.type)) {
            throw new IOException("该请求缺少 session 或未定义响应");
        }
        return packet(data("session", request.session), responses.get(request.type), body);
    }

    /**
     * 返回尚未开放的命令所支持的明确失败响应
     *
     * @param request 客户端请求
     * @return 只有协议声明相关错误字段时才包含字段
     */
    Map<String, Object> unsupported(Request request) {
        String field = failureFields.get(request.type);
        if (request.session <= 0 || field == null) {
            return Collections.emptyMap();
        }
        return data(field, "errno".equals(field) ? 501 : "501 Not Implemented");
    }

    /**
     * 生成服务端主动推送包
     *
     * @param type 服务端消息编号
     * @param body 推送字段
     * @return 已压缩推送数据
     * @throws IOException 推送数据无法编码
     */
    byte[] push(int type, Map<String, Object> body) throws IOException {
        return packet(data("type", type), pushes.get(type), body);
    }

    /**
     * 按键值对构造协议结构
     *
     * @param fields 成对提供的字段与值
     * @return 协议字段映射
     */
    static Map<String, Object> data(Object... fields) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    /**
     * 读取整数值，字段不存在时回退到缺省值
     *
     * @param fields 协议字段
     * @param name 字段名称
     * @param fallback 缺省值
     * @return 字段值或缺省值
     */
    static long number(Map<String, Object> fields, String name, long fallback) {
        Object value = fields.get(name);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    /**
     * 装载一行消息或嵌套结构声明
     *
     * @param types 已知结构映射
     * @param line 声明文本
     */
    private void loadLine(Map<String, List<SprotoField>> types, String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] columns = line.split(" ", 4);
        String name;
        String fields;
        if ("TYPE".equals(columns[0])) {
            name = columns[1];
            fields = columns.length == 3 ? columns[2] : "";
        } else {
            name = columns[0] + "." + columns[2];
            fields = columns.length == 4 ? columns[3] : "";
            Map<Integer, String> messages = "S".equals(columns[0]) ? pushes
                    : name.endsWith(".response") ? responses : requests;
            int type = Integer.parseInt(columns[1]);
            messages.put(type, name);
            if (messages == requests && fields.isEmpty()) {
                bodylessRequests.add(type);
            }
        }
        List<SprotoField> structure = new ArrayList<>();
        if (!fields.isEmpty()) {
            for (String definition : fields.split(",")) {
                structure.add(new SprotoField(definition));
            }
            structure.sort(Comparator.comparingInt(field -> field.tag));
        }
        if (name.startsWith("C.") && name.endsWith(".response")) {
            int type = Integer.parseInt(columns[1]);
            if (structure.stream().anyMatch(field -> "errno".equals(field.name) && "integer".equals(field.type))) {
                failureFields.put(type, "errno");
            } else if (structure.stream().anyMatch(field -> "error".equals(field.name) && "string".equals(field.type))) {
                failureFields.put(type, "error");
            }
        }
        types.put(name, structure);
    }

    /**
     * 合并头部和结构，压缩得到 TCP 帧负载
     *
     * @param header 消息头
     * @param bodyType 消息内容类型
     * @param body 消息内容
     * @return 已压缩负载
     * @throws IOException 消息编码失败
     */
    private byte[] packet(Map<String, Object> header, String bodyType, Map<String, Object> body) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(codec.encode("package", header));
        result.write(codec.encode(bodyType, body));
        return SprotoCodec.pack(result.toByteArray());
    }

    /**
     * 解码后的本地网关请求
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:46
     */
    static final class Request {
        final int type;
        final long session;
        final Map<String, Object> body;

        /**
         * 保存客户端请求的类型、会话号和字段
         *
         * @param type 客户端消息编号
         * @param session 消息关联号
         * @param body 解码字段
         */
        private Request(int type, long session, Map<String, Object> body) {
            this.type = type;
            this.session = session;
            this.body = body;
        }
    }
}
