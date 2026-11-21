package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.dailydup.DailyDungeonService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 每日副本战斗、扫荡及原面板消息。
 * @author tongruiyuan
 * @since 2026-11-19 19:02
 */
@Service
@Order(-46)
final class DailyDungeonGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };
    private final DailyDungeonService daily;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final ObjectMapper json;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /** 绑定每日副本协议与真实资产。
     * @param daily 副本进度与资产结算
     * @param roles 钱包存档
     * @param inventory 背包与邮件推送
     * @param json 协议投影工具
     */
    DailyDungeonGameFeature(DailyDungeonService daily, RoleService roles, InventoryGameFeature inventory,
            ObjectMapper json) {
        this.daily = daily;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
    }

    /** 原 S415 包含五类正式挑战用量与历史通关关卡。
     * @param role 当前角色
     * @param output 连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(415, json.convertValue(daily.snapshot(role.id), BODY));
    }

    /** 从实际战斗动作、正式挑战及扫荡入口驱动结算。
     * @param role 当前角色
     * @param request 原协议请求
     * @param output 本地连接
     * @return 是否处理
     * @throws IOException 回包失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if ((request.type == 168 || request.type == 169) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            }
            long stage = number(request.body, "dupId", 0);
            ObjectNode reply = daily.enter(role.id, stage > 0 && stage <= Integer.MAX_VALUE ? (int) stage : 0,
                    request.type == 169, "dailydup:" + conn + ":" + request.session);
            ObjectNode wire = reply.deepCopy();
            wire.retain("errno", "seed", "weather", "monster", "player");
            for (String side : Arrays.asList("player", "monster"))
                for (JsonNode actor : wire.path(side).path("pokemon"))
                    ((ObjectNode) actor).remove(Arrays.asList("skill", "targetLimit", "physical", "goldCarry", "homeStatues", "baseDamage"));
            if (request.type == 169 && reply.has("adds") && !reply.path("replayed").asBoolean()) {
                synchronize(role, output);
            }
            output.send(request, json.convertValue(wire, BODY));
            if (request.type == 169 && reply.has("adds") && !reply.path("replayed").asBoolean()) {
                publish(role, output, reply.path("adds"), "dailydup.clean_dailydup");
            }
            return true;
        }
        switch (request.type) {
        case 3001:
        case 3002:
            if (!daily.active(role.id))
                return false;
            Object target = request.body.get("objs");
            ObjectNode result = daily.advance(role.id, (int) number(request.body, "sid", 0),
                    target instanceof List ? (List<?>) target : Collections.emptyList(),
                    (int) number(request.body, "flag", -1), request.type == 3002);
            if (result.path("outcome").asInt() == 1)
                publish(role, output, result.path("adds"), "dailydup.__handle_win");
            else if (result.path("outcome").asInt() == 2 && request.type != 3002)
                output.push(1051, data());
            return true;
        case 8:
        case 66:
        case 68:
        case 69:
        case 102:
        case 105:
        case 109:
        case 268: case 181:
        case 273:
        case 285:
        case 2203:
        case 3037:
        case 5071:
        case 5072:
            if (!daily.active(role.id))
                return false;
            if (request.session > 0)
                output.send(request, data("errno", 2002));
            return true;
        default:
            return false;
        }
    }

    /** 同步胜利或扫荡后的实际到账和正式挑战进度。
     * @param role 当前角色
     * @param output 连接
     * @param adds 奖励增量
     * @param flag 奖励入口名称
     * @throws IOException 推送失败
     */
    private void publish(LocalRole role, GameReply output, JsonNode adds, String flag) throws IOException {
        output.push(14, data("flags", flag, "adds", json.convertValue(adds, BODY)));
        inventory.pushBag(role, output);
        inventory.pushMailCount(role, output);
        output.push(3, data("coins", roles.findById(role.id).orElse(role).coins));
        synchronize(role, output);
    }
}
