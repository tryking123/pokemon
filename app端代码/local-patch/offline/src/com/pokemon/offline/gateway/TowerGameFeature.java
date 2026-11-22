package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.tower.TowerService;
import java.io.IOException;
import java.util.ArrayList;
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
 * 洞穴闯关的原界面、地图和战斗入口。
 * @author tongruiyuan
 * @since 2026-11-19 19:24
 */
@Service
@Order(-47)
final class TowerGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };
    private final TowerService tower;
    private final RoleService roles;
    private final ActivityGameFeature activity;
    private final InventoryGameFeature inventory;
    private final ObjectMapper json;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /** 绑定洞穴玩法及钱包背包推送。
     * @param tower 洞穴服务
     * @param roles 角色
     * @param inventory 背包和邮件
     * @param json 协议表示
     * @param activity 冒险卡片可用次数
     */
    TowerGameFeature(TowerService tower, RoleService roles, InventoryGameFeature inventory, ObjectMapper json, ActivityGameFeature activity) {
        this.tower = tower;
        this.roles = roles;
        this.activity = activity;
        this.inventory = inventory;
        this.json = json;
    }

    /** 同步洞穴楼层、次数及在场特性。
     * @param role 当前角色
     * @param output 游戏连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        if (role.level < 14)
            return;
        ObjectNode state = tower.snapshot(role.id);
        output.push(430,
                data("mission", state.path("mission").asInt(), "current", state.path("current").asInt(), "box",
                        state.path("box").asBoolean(), "times", state.path("times").asInt(), "resettime",
                        (state.path("day").asLong() + 1) * 86400 - 28800, "bufftime", state.path("bufftime").asLong(),
                        "renovation", state.path("renovation").asInt()));
        if(com.pokemon.offline.home.OrangeService.scene(role.sceneId))return;
        List<Map<String, Object>> buffs = new ArrayList<>();
        if (TowerService.isScene(role.sceneId))
            for (JsonNode buff : state.path("ally"))
                buffs.add(data("id", buff.asInt(), "amass", 1, "endtime", state.path("bufftime").asLong()));
        output.push(111, data("list", buffs, "isNull", buffs.isEmpty()));
    }

    /** 处理原场景预览、守卫挑战、特性刷新及里程碑宝箱。
     * @param role 当前认证角色
     * @param request 原客户端请求
     * @param output 连接
     * @return 已处理本请求
     * @throws IOException 回包失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
        case 120:
            long layer = number(request.body, "mission", 0);
            output.send(request,
                    json.convertValue(tower.preview(role.id, layer >= 1 && layer <= 120 ? (int) layer : 0), BODY));
            return true;
        case 121:
            long team = number(request.body, "teamid", 0);
            ObjectNode start = tower.start(role.id, String.valueOf(request.body.get("uid")), team >= 1 && team <= 3 ? (int) team : 0)
                    .deepCopy();
            start.retain("errno", "seed", "weather", "player", "monster");
            for (String side : Arrays.asList("player", "monster"))
                for (JsonNode actor : start.path(side).path("pokemon"))
                    ((ObjectNode) actor).remove(Arrays.asList("skill", "targetLimit", "physical", "goldCarry", "homeStatues", "baseDamage"));
            output.send(request, json.convertValue(start, BODY));
            return true;
        case 134:
            String connection;
            synchronized (connections) {
                connection = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            }
            ObjectNode result = tower.refresh(role.id, "tower:" + connection + ":" + request.session);
            output.send(request, json.convertValue(result, BODY));
            if (result.path("errno").asInt() == 0) {
                LocalRole current = roles.findById(role.id).orElse(role);
                output.push(3, data("diamonds", current.diamonds));
                synchronize(current, output);
            }
            return true;
        case 2210:
            LocalRole saved = roles.findById(role.id).orElse(role);
            if (!TowerService.isScene(saved.sceneId))
                return false;
            String uid = String.valueOf(request.body.get("uid"));
            ObjectNode chest = tower.box(role.id, uid);
            output.send(request, data("errno", chest.path("errno").asInt()));
            if (chest.has("adds")) {
                output.push(14, data("flags", "tower.open_box", "adds", json.convertValue(chest.path("adds"), BODY)));
                inventory.pushBag(role, output);
                inventory.pushMailCount(role, output);
                output.push(12, data("sid", saved.sceneId, "uids", Collections.singletonList(uid)));
                synchronize(saved, output);
            }
            return true;
        case 3001:
        case 3002:
            if (!tower.active(role.id))
                return false;
            Object target = request.body.get("objs");
            ObjectNode end = tower.advance(role.id, (int) number(request.body, "sid", 0),
                    target instanceof List ? (List<?>) target : Collections.emptyList(),
                    (int) number(request.body, "flag", -1), request.type == 3002);
            if (end.path("outcome").asInt() > 0) {
                LocalRole current = roles.findById(role.id).orElse(role);
                if (end.has("adds")) {
                    output.push(14,
                            data("flags", "tower.mission_win", "adds", json.convertValue(end.path("adds"), BODY)));
                    output.push(3, data("diamonds", current.diamonds));
                    inventory.pushBag(role, output);
                    inventory.pushMailCount(role, output);
                    output.push(12,
                            data("sid", current.sceneId, "uids", Collections.singletonList(end.path("uid").asText())));
                    List<Map<String, Object>> boxes = new ArrayList<>();
                    for (JsonNode box : tower.actors(current, true))
                        boxes.add(json.convertValue(box, BODY));
                    if (!boxes.isEmpty())
                        output.push(801, data("scence", current.sceneId, "others", boxes));
                } else if (request.type != 3002)
                    output.push(1051, data());
                synchronize(current, output);
                if (end.path("outcome").asInt() == 2) activity.pushActivityStatus(current,output);
            }
            return true;
        case 8:
        case 66:
        case 68:
        case 69:
        case 102:
        case 105:
        case 109:
        case 168:
        case 268: case 181:
        case 273:
        case 285:
        case 2203:
        case 3037:
        case 5071:
        case 5072:
            if (!tower.active(role.id))
                return false;
            if (request.session > 0)
                output.send(request, data("errno", 2002));
            return true;
        default:
            return false;
        }
    }
}
