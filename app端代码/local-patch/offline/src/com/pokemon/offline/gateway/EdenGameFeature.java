package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.eden.EdenEncounterService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 神兽岛原协议入口与场景战斗回包。
 * @author tongruiyuan
 * @since 2026-11-19 18:17
 */
@Service
@Order(-45)
final class EdenGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };
    private final EdenEncounterService eden;
    private final InventoryGameFeature inventory;
    private final BattleGameFeature battle;
    private final RoleService roles;
    private final ObjectMapper json;
    private final ActivityGameFeature activity;
    private final Map<GameReply, String> connections = new WeakHashMap<>();
    private final Map<GameReply, Map<String, JsonNode>> visible = new WeakHashMap<>();

    /** 绑定角色岛屿及协议推送服务。
     * @param eden 岛屿状态
     * @param inventory 背包及邮件
     * @param battle 精灵资产
     * @param roles 角色钱包
     * @param json 协议投影工具
     * @param activity 神兽岛卡片剩余次数推送
     */
    EdenGameFeature(EdenEncounterService eden, InventoryGameFeature inventory, BattleGameFeature battle,
            RoleService roles, ObjectMapper json, ActivityGameFeature activity) {
        this.eden = eden;
        this.inventory = inventory;
        this.battle = battle;
        this.roles = roles;
        this.json = json;
        this.activity = activity;
    }

    /** 推送原神兽岛剩余次数所需字段。
     * @param role 当前角色
     * @param output 连接回执
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(813, json.convertValue(eden.counters(role.id), BODY));
    }

    /** 岛内停留时推送已逃跑及重新出现的场景演员。
     * @param role 心跳时更新的位置
     * @param output 原场景连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        if (role.sceneId < 80001 || role.sceneId > 80003) {
            synchronized (visible) {
                visible.remove(output);
            }
            return;
        }
        Map<String, JsonNode> current = new LinkedHashMap<>();
        for (JsonNode actor : eden.sceneActors(role))
            current.put(actor.path("uid").asText(), actor);
        Map<String, JsonNode> previous;
        synchronized (visible) {
            previous = visible.put(output, current);
        }
        if (previous == null)
            return;
        List<String> removed = new ArrayList<>(previous.keySet());
        removed.removeAll(current.keySet());
        if (!removed.isEmpty())
            output.push(12, data("sid", role.sceneId, "uids", removed));
        List<Map<String, Object>> added = new ArrayList<>();
        current.forEach((uid, actor) -> {
            if (!previous.containsKey(uid))
                added.add(json.convertValue(actor, BODY));
        });
        if (!added.isEmpty())
            output.push(801, data("scence", role.sceneId, "monsters", added));
    }

    /** 从原岛屿请求推进地图、战斗和结算状态。
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 连接回执
     * @return 是否由神兽岛处理
     * @throws IOException 回执发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type >= 282 && request.type <= 286 && request.session > 0) {
            String connection;
            synchronized (connections) {
                connection = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            }
            long ball = number(request.body, "ball", 0);
            ObjectNode result = eden.request(role.id, request.type, String.valueOf(request.body.get("uid")),
                    ball >= 21070 && ball <= 21072 ? (int) ball : 0,
                    connection + ":" + request.type + ":" + request.session);
            ObjectNode wire = result.deepCopy();
            wire.remove(Arrays.asList("qty", "chip", "replayed"));
            if (request.type == 285) {
                wire.retain("errno", "seed", "weather", "player", "monster");
                for (String side : Arrays.asList("player", "monster"))
                    for (JsonNode actor : wire.path(side).path("pokemon"))
                        ((ObjectNode) actor).remove(Arrays.asList("skill", "goldCarry", "homeStatues", "baseDamage", "targetLimit", "physical"));
            }
            output.send(request, json.convertValue(wire, BODY));
            if (result.path("errno").asInt() != 0 || result.path("replayed").asBoolean())
                return true;
            if (request.type == 284 || request.type == 285) {
                synchronize(role, output);
                activity.pushActivityStatus(role, output);
            }
            if (request.type == 284)
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            if (request.type == 286) {
                inventory.pushBag(role, output);
                if (result.path("result").asBoolean()) {
                    inventory.pushMailCount(role, output);
                    battle.pushElfs(role.id, output);
                    output.push(12, data("sid", roles.findById(role.id).orElse(role).sceneId, "uids",
                            Collections.singletonList(result.path("uid").asText())));
                } else {
                    List<Map<String, Object>> chips = result.has("chip")
                            ? Collections.singletonList(
                                    data("id", result.path("chip").asInt(), "n", result.path("qty").asInt()))
                            : Collections.emptyList();
                    output.push(14, data("flags", "eden.not_catch", "adds", data("elschips", chips)));
                }
            }
            return true;
        }
        switch (request.type) {
        case 3001:
        case 3002:
            if (!eden.active(role.id))
                return false;
            Object target = request.body.get("objs");
            int result = eden.advance(role.id, (int) number(request.body, "sid", 0),
                    target instanceof List ? (List<?>) target : Collections.emptyList(),
                    (int) number(request.body, "flag", -1), request.type == 3002);
            if (result > 0)
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
        case 2203:
        case 273:
        case 3037:
        case 5071:
        case 5072:
            if (!eden.active(role.id))
                return false;
            if (request.session > 0)
                output.send(request, data("errno", 2002));
            return true;
        default:
            return false;
        }
    }
}
