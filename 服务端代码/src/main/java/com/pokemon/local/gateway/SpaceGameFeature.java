package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.space.SpaceService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 究极空间战斗、扫荡及原面板消息。
 * @author tongruiyuan
 * @since 2026-11-19 19:42
 */
@Service
@Order(-48)
final class SpaceGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };
    private final SpaceService space;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final ObjectMapper json;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /** 绑定究极空间协议与真实资产。
     * @param space 空间进度与资产结算
     * @param roles 钱包存档
     * @param inventory 背包与邮件推送
     * @param json 协议投影工具
     */
    SpaceGameFeature(SpaceService space, RoleService roles, InventoryGameFeature inventory, ObjectMapper json) {
        this.space = space;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
    }

    /** 同步究极空间进度与当日剩余次数。
     * @param role 当前角色
     * @param output 连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(2051, json.convertValue(space.snapshot(role.id), BODY));
    }

    /** 从原关卡、排行与阶段奖励入口驱动进度。
     * @param role 当前角色
     * @param request 原协议请求
     * @param output 本地连接
     * @return 是否处理
     * @throws IOException 回包失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type >= 5101 && request.type <= 5104 && request.session > 0) {
            ObjectNode result;
            if (request.type == 5101) {
                String conn;
                synchronized (connections) {
                    conn = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
                }
                result = space.start(role.id, conn + ":" + request.session);
            } else if (request.type == 5102) {
                result = space.rank(role.realmId);
            } else {
                long mission = number(request.body, "mission_id", 0);
                result = request.type == 5104 && (mission <= 0 || mission > Integer.MAX_VALUE)
                        ? json.createObjectNode().put("errno", 1)
                        : space.milestones(role.id, request.type == 5103 ? 0 : (int) mission);
            }
            ObjectNode wire = result.deepCopy();
            wire.retain("errno", "seed", "weather", "monster", "player", "rank", "mission_id", "stage_reward");
            for (String side : Arrays.asList("player", "monster"))
                for (JsonNode actor : wire.path(side).path("pokemon"))
                    ((ObjectNode) actor).remove(Arrays.asList("skill", "targetLimit", "physical", "goldCarry", "homeStatues", "baseDamage"));
            output.send(request, json.convertValue(wire, BODY));
            if (result.has("adds"))
                publish(role, output, result.get("adds"), "space.stage_reward");
            return true;
        }
        switch (request.type) {
        case 3001:
        case 3002:
            if (!space.active(role.id))
                return false;
            Object target = request.body.get("objs");
            ObjectNode result = space.advance(role.id, (int) number(request.body, "sid", 0),
                    target instanceof List ? (List<?>) target : Collections.emptyList(),
                    (int) number(request.body, "flag", -1), request.type == 3002);
            if (result.path("outcome").asInt() == 1)
                publish(role, output, result.path("adds"), "spacedup.__handle_win");
            else if (result.path("outcome").asInt() == 2) {
                synchronize(role, output);
                if (request.type != 3002)
                    output.push(1051, data());
            }
            return true;
        case 8:
        case 66:
        case 68:
        case 69:
        case 102:
        case 105:
        case 109:
        case 121:
        case 168:
        case 169:
        case 268: case 181:
        case 273:
        case 285:
        case 2203:
        case 3037:
        case 5071:
        case 5072:
            if (!space.active(role.id))
                return false;
            if (request.session > 0)
                output.send(request, data("errno", 2002));
            return true;
        default:
            return false;
        }
    }

    /** 同步实际到账及空间关卡进度。
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
