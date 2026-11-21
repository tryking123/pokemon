package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.FeastService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 圣诞麋鹿的地图刷新、原挑战请求和回合应答。
 *
 * @author tongruiyuan
 * @since 2026-11-20 15:01
 */
@Service
@Order(-150)
final class FeastGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY = new TypeReference<Map<String,Object>>() {};
    private final FeastService feast;
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final Map<GameReply,View> sessions = Collections.synchronizedMap(new WeakHashMap<>());

    /** 单次连接已发送的怪物和刷新时刻。 */
    private static final class View {
        final String id = UUID.randomUUID().toString();
        Set<String> visible = Collections.emptySet();
        long next;
    }

    /**
     * 绑定活动结算、原地图和背包。
     * @param inventory 宝箱开取
     * @param feast 麋鹿活动
     * @param json 原协议映射
     * @param roles 角色位置
     * @param bags 背包展示
     */
    FeastGameFeature(InventoryService inventory, FeastService feast, ObjectMapper json, RoleService roles, BagSnapshot bags) {
        this.feast = feast;
        this.inventory = inventory;
        this.json = json;
        this.roles = roles;
        this.bags = bags;
    }

    /**
     * 为完整场景快照登记本连接刚看到的目标，后续心跳仅推送变化。
     * @param role 场景角色
     * @param output 当前连接
     * @return 当前场景的麋鹿列表
     */
    JsonNode scene(LocalRole role, GameReply output) {
        JsonNode actors = feast.scene(role);
        Set<String> visible = new HashSet<>();
        for (JsonNode actor : actors) visible.add(actor.path("uid").asText());
        sessions.computeIfAbsent(output, ignored -> new View()).visible = visible;
        return actors;
    }

    /**
     * 在玩家停留主城期间增删本场目标。
     * @param role 玩家角色
     * @param output 游戏连接
     * @throws IOException 连接写入失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        View view = sessions.computeIfAbsent(output, ignored -> new View());
        long now = System.currentTimeMillis() / 1000;
        if (now < view.next) return;
        view.next = now + 10;
        role = roles.findById(role.id).orElse(role);
        if (role.sceneId != 71002) {
            view.visible = Collections.emptySet();
            return;
        }
        List<Map<String,Object>> added = new ArrayList<>();
        Set<String> visible = new HashSet<>();
        for (JsonNode actor : feast.scene(role)) {
            String uid = actor.path("uid").asText();
            visible.add(uid);
            if (!view.visible.contains(uid)) added.add(json.convertValue(actor, BODY));
        }
        Set<String> removed = new HashSet<>(view.visible);
        removed.removeAll(visible);
        if (!removed.isEmpty()) output.push(12, data("sid", 71002, "uids", new ArrayList<>(removed)));
        if (!added.isEmpty()) output.push(801, data("scence", 71002, "monsters", added));
        view.visible = visible;
    }

    /**
     * 从已匹配的本地麋鹿实例创建战斗或提交战斗动作。
     * @param role 玩家角色
     * @param request 原客户端请求
     * @param output 游戏连接
     * @return 是否已消费本次请求
     * @throws IOException 协议写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 2401 && number(request.body, "id", 0) == 34700) {
            if (request.session <= 0) return true;
            long count = number(request.body, "num", 1);
            View view = sessions.computeIfAbsent(output, ignored -> new View());
            long given = inventory.openReindeerGift(role.id, count, number(request.body, "giftid", 0),
                    view.id + ":" + request.session);
            if (given >= 0) {
                output.push(14, data("flags", "item.use", "loses", data("items",
                        Collections.singletonList(data("id", 34700, "n", count))), "adds", data("items",
                        given == 0 ? Collections.emptyList() : Collections.singletonList(data("id", 8506, "n", given)))));
                bags.push(role.id, output);
                bags.refreshMailCount(role.id, output);
            }
            output.send(request, data("errno", given < -1 ? -given : 0));
            return true;
        }
        if (request.type == 290) {
            if (request.session <= 0) return true;
            View view = sessions.computeIfAbsent(output, ignored -> new View());
            ObjectNode fight = feast.start(role.id, String.valueOf(request.body.getOrDefault("uid", "")), view.id + ":" + request.session);
            output.send(request, json.convertValue(fight, BODY));
            return true;
        }
        if (WorldBossGameFeature.competingAction(request.type) && feast.active(role.id)) {
            if (request.session > 0) output.send(request, data("errno", 2002));
            return true;
        }
        if ((request.type != 3001 && request.type != 3002) || !feast.active(role.id)) return false;
        Object targets = request.body.get("objs");
        ObjectNode result = feast.turn(role.id, (int)number(request.body, "sid", 0),
                targets instanceof List ? (List<?>)targets : Collections.emptyList(),
                (int)number(request.body, "flag", -1), request.type == 3002);
        if (result.path("outcome").asInt() == 2 && request.type != 3002) output.push(1051, data());
        if (result.has("uid")) {
            String uid = result.path("uid").asText();
            View view = sessions.get(output);
            if (view != null) view.visible.remove(uid);
            output.push(12, data("sid", 71002, "uids", Collections.singletonList(uid)));
            if (result.path("gift").asBoolean()) output.push(14, data("flags", "bombshell_attack", "adds",
                    data("items", Collections.singletonList(data("id", 34700, "n", 1)))));
            bags.push(role.id, output);
            bags.refreshMailCount(role.id, output);
        }
        return true;
    }
}
