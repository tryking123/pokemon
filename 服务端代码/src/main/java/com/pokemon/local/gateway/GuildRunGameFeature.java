package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.guild.GuildRunService;
import com.pokemon.local.role.api.entity.LocalRole;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 原公会竞跑准备界面、跑图与宝箱协议。
 *
 * @author tongruiyuan
 * @since 2026-11-20 15:05
 */
@Service
final class GuildRunGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY =
            new TypeReference<Map<String, Object>>() {};
    private final GuildRunService runs;
    private final ObjectMapper json;
    private final BagSnapshot bags;
    private final Map<GameReply, String> connections =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定竞跑存档与本地资产快照。
     *
     * @param runs 竞跑业务
     * @param json 协议转换
     * @param bags 背包推送
     */
    GuildRunGameFeature(GuildRunService runs, ObjectMapper json, BagSnapshot bags) {
        this.runs = runs;
        this.json = json;
        this.bags = bags;
    }

    /**
     * 在登录时结清旧赛季并推送角色实有的两类宝箱。
     *
     * @param role 已登录角色
     * @param out 本地连接
     * @throws IOException 协议写入失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply out) throws IOException {
        ObjectNode boxes =
                runs.act(role.id, 305, json.createObjectNode(), "", Instant.now().getEpochSecond());
        if (boxes.has("boxnum"))
            out.push(
                    428,
                    SprotoProtocol.data(
                            "boxnum",
                            json.convertValue(boxes.get("boxnum"), java.util.List.class)));
    }

    /**
     * 返回竞跑结果及已提交的奖励、消费或宝箱变化。
     *
     * @param role 已登录角色
     * @param request 原协议请求
     * @param out 消息通道
     * @return 是否已处理
     * @throws IOException 协议写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply out)
            throws IOException {
        int cmd = request.type;
        if (cmd < 303 || cmd > 309 || cmd == 308) return false;
        if (request.session <= 0) return true;
        String key =
                "grun:"
                        + connections.computeIfAbsent(out, k -> UUID.randomUUID().toString())
                        + ":"
                        + request.session;
        ObjectNode result =
                runs.act(
                        role.id,
                        cmd,
                        json.valueToTree(request.body),
                        key,
                        Instant.now().getEpochSecond());
        JsonNode adds = result.remove("adds"),
                loses = result.remove("loses"),
                boxes = result.remove("boxes");
        if (adds != null || loses != null) {
            out.push(
                    14,
                    SprotoProtocol.data(
                            "flags",
                            "guildrun",
                            "adds",
                            adds == null ? Collections.emptyMap() : json.convertValue(adds, BODY),
                            "loses",
                            loses == null
                                    ? Collections.emptyMap()
                                    : json.convertValue(loses, BODY)));
        }
        if (boxes != null)
            out.push(
                    428,
                    SprotoProtocol.data("boxnum", json.convertValue(boxes, java.util.List.class)));
        out.send(request, json.convertValue(result, BODY));
        if (cmd == 306 && result.path("errno").asInt() == 0) {
            bags.push(role.id, out);
            bags.pushMailCount(role.id, out);
        }
        return true;
    }
}
