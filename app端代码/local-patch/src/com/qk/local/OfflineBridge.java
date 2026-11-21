package com.qk.local;

import android.content.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.embedded.EmbeddedRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity 经 JNI 直接调用的离线业务入口，不使用 HTTP 或 TCP。
 *
 * @author tongruiyuan
 * @since 2026-11-21 23:55
 */
public final class OfflineBridge {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Context application;
    private static boolean installed;
    private OfflineBridge() { }

    /**
     * 保存应用上下文并加载进程内原生桥。
     * @param context 应用上下文
     */
    public static synchronized void install(Context context) {
        if (installed) return;
        application = context.getApplicationContext();
        System.loadLibrary("pokemonoffline");
        installed = true;
    }

    /**
     * 直接执行一次离线操作并返回已提交结果。
     * @param request JSON 操作及参数；游戏消息使用十六进制原协议
     * @return JSON 结果或明确失败信息
     */
    public static String exchange(String request) {
        Map<String,Object> result = new LinkedHashMap<>();
        try {
            if (application == null) throw new IllegalStateException("App 离线桥未初始化");
            if (request == null || request.length() > 262144) throw new IllegalArgumentException("离线操作长度无效");
            JsonNode input = JSON.readTree(request);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("离线操作格式无效");
            EmbeddedRuntime runtime = EmbeddedRuntime.get(application);
            List<byte[]> frames = new ArrayList<>();
            switch (input.path("op").asText()) {
                case "connect": runtime.game().connect(); break;
                case "disconnect": runtime.game().disconnect(); break;
                case "request": frames = runtime.game().request(unhex(input.path("payload").asText())); break;
                case "poll": frames = runtime.game().poll(); break;
                case "api":
                    LocalApi.LocalResponse response = runtime.api().request(input.path("method").asText("GET"),
                            input.path("path").asText(), input.path("body").isMissingNode() ? null : input.path("body").asText(),
                            input.path("authorization").isMissingNode() ? null : input.path("authorization").asText(),
                            input.path("contentType").asText("application/json"));
                    result.put("status", response.status);
                    result.put("text", response.json);
                    break;
                default: throw new IllegalArgumentException("未知离线操作");
            }
            List<String> encoded = new ArrayList<>();
            for (byte[] frame : frames) encoded.add(hex(frame));
            result.put("frames", encoded);
            result.put("ok", true);
        } catch (Exception | LinkageError failure) {
            android.util.Log.e("PokemonOffline", "Unity 离线调用失败", failure);
            result.clear();
            result.put("ok", false);
            result.put("error", failure.getMessage() == null ? "离线操作失败" : failure.getMessage());
        }
        try { return JSON.writeValueAsString(result); }
        catch (Exception impossible) { return "{\"ok\":false,\"error\":\"ENCODING_ERROR\"}"; }
    }

    /**
     * 解码有界的十六进制消息。
     * @param value 十六进制文本
     * @return 原始消息
     */
    private static byte[] unhex(String value) {
        if (value.isEmpty() || (value.length() & 1) != 0 || value.length() > 131070) throw new IllegalArgumentException("消息编码无效");
        byte[] data = new byte[value.length() / 2];
        for (int i = 0; i < data.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("消息编码无效");
            data[i] = (byte) ((high << 4) | low);
        }
        return data;
    }

    /**
     * 将原消息编码为可安全跨 JNI 的文本。
     * @param data 原始消息
     * @return 十六进制文本
     */
    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            output[i * 2] = digits[(data[i] >>> 4) & 15];
            output[i * 2 + 1] = digits[data[i] & 15];
        }
        return new String(output);
    }
}
