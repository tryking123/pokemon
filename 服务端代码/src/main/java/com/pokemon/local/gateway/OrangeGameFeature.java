package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.home.HomeService;
import com.pokemon.local.home.OrangeService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 橘子群岛地图、守卫、三队生命及扫荡到账。
 * @author tongruiyuan
 * @since 2026-11-19 20:06
 */
@Service
@Order(-50)
final class OrangeGameFeature implements GameFeature {
    private static final Set<Integer> BLOCKED_STARTS = new HashSet<>(
            Arrays.asList(66, 68, 69, 102, 105, 109, 121, 168, 181, 273, 285, 2203, 3037, 5071, 5072, 5101));
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };
    private final OrangeService orange;
    private final HomeService home;
    private final ObjectProvider<WorldGameFeature> world;
    private final InventoryGameFeature bags;
    private final RoleService roles;
    private final ObjectMapper json;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /** 绑定原地图与奖励推送。
     * @param orange 岛屿存档
     * @param home 心情余额
     * @param world 场景广播
     * @param bags 道具与邮件同步
     * @param roles 角色位置
     * @param json 原协议投影
     */
    OrangeGameFeature(OrangeService orange, HomeService home, ObjectProvider<WorldGameFeature> world,
            InventoryGameFeature bags, RoleService roles, ObjectMapper json) {
        this.orange = orange;
        this.home = home;
        this.world = world;
        this.bags = bags;
        this.roles = roles;
        this.json = json;
    }

    /** 发送橘子群岛的通关、生命及重连数据。
     * @param role 角色
     * @param output 网关连接
     * @throws IOException 写入失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        if (OrangeService.scene(role.sceneId)) {
            pushBuffs(role, output);
            ObjectNode deck = orange.deck(role.id);
            if (deck.has("list"))
                output.push(2018, json.convertValue(deck, BODY));
        }
        if (OrangeService.scene(role.sceneId) || role.sceneId == 80011)
            output.push(827, json.convertValue(orange.snapshot(role.id), BODY));
    }

    /** 同步本次群岛探险的持续增益。
     * @param role 当前角色
     * @param output 原协议输出
     * @throws IOException 连接写入失败
     */
    private void pushBuffs(LocalRole role, GameReply output) throws IOException {
        List<?> buffs = json.convertValue(orange.buffs(role.id), List.class);
        output.push(111, data("list", buffs, "isNull", buffs.isEmpty()));
    }

    /** 处理岛屿进入、真实敌人挑战和历史关卡扫荡。
     * @param role 当前认证角色
     * @param request 原客户端请求
     * @param output 连接
     * @return 是否处理
     * @throws IOException 写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (BLOCKED_STARTS.contains(request.type) || request.type == 2210 || request.type == 179 || request.type == 3001
                || request.type == 3002)
            role = roles.findById(role.id).orElse(role);
        if (request.type == 2210 && OrangeService.scene(role.sceneId) && request.session > 0) {
            String uid = String.valueOf(request.body.get("uid"));
            ObjectNode event = orange.event(role, uid);
            output.send(request, data("errno", event.path("errno").asInt()));
            if (event.path("errno").asInt() == 0) {
                output.push(12, data("sid", role.sceneId, "uids", Collections.singletonList(uid)));
                ObjectNode snap = orange.snapshot(role.id);
                output.push(825, data("teamhp", json.convertValue(snap.path("teamhp"), List.class)));
                output.push(826, data("current", snap.path("current").asInt(), "total", snap.path("total").asInt()));
                pushBuffs(role, output);
                if (event.has("adds"))
                    publish(role, output, event.path("adds"));
            }
            return true;
        }
        if (request.type == 179 && OrangeService.scene(role.sceneId) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
            }
            boolean all = Boolean.TRUE.equals(request.body.get("allGet"));
            long index = number(request.body, "idx", 0);
            ObjectNode r = orange.flip(role, index >= 1 && index <= 3 ? (int) index : 0, all,
                    conn + ":" + request.session);
            output.send(request, data("errno", r.path("errno").asInt(), "idx", index, "allGet", all));
            if (r.has("adds")) {
                output.push(14, data("flags", "bigtallup.ask_flop", "adds", json.convertValue(r.get("adds"), BODY),
                        "loses", data("diamonds", r.path("cost").asInt())));
                bags.pushBag(role, output);
                bags.pushMailCount(role, output);
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if (request.type == 361 && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, key -> UUID.randomUUID().toString());
            }
            ObjectNode up = orange.upgrade(role.id, conn + ":" + request.session);
            output.send(request, data("errno", up.path("errno").asInt(), "nextlevel", up.path("nextlevel").asInt()));
            if (up.path("errno").asInt() == 0 && !up.path("replayed").asBoolean()) {
                bags.pushBag(role, output);
                output.push(3, data("coins", roles.findById(role.id).orElse(role).coins));
            }
            return true;
        }
        if ((request.type == 341 || request.type == 372) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, key -> UUID.randomUUID().toString());
            }
            long island = number(request.body, "idx", 0);
            ObjectNode r = orange.enter(role.id, island >= 1 && island <= 8 ? (int) island : 0, request.type == 372,
                    conn + ":" + request.session);
            output.send(request, data("errno", r.path("errno").asInt(), "idx", island));
            if (r.path("errno").asInt() == 0 && !r.path("replayed").asBoolean()) {
                LocalRole saved = roles.findById(role.id).orElse(role);
                output.push(3, data("mood", home.mood(role.id)));
                if (request.type == 341)
                    world.getIfAvailable().synchronizeScene(saved, output);
                if (r.has("adds"))
                    publish(saved, output, r.path("adds"));
                synchronize(saved, output);
            }
            return true;
        }
        if (request.type == 342 && request.session > 0) {
            long team = number(request.body, "team", 0);
            ObjectNode wire = orange
                    .start(role.id, String.valueOf(request.body.get("uid")), team >= 1 && team <= 3 ? (int) team : 0)
                    .deepCopy();
            wire.retain("errno", "seed", "weather", "monster", "player");
            for (String side : Arrays.asList("player", "monster"))
                for (JsonNode actor : wire.path(side).path("pokemon"))
                    ((ObjectNode) actor).remove(Arrays.asList("skill", "targetLimit", "physical", "goldCarry", "homeStatues", "baseDamage"));
            output.send(request, json.convertValue(wire, BODY));
            return true;
        }
        if ((request.type == 3001 || request.type == 3002) && orange.active(role.id)) {
            Object obj = request.body.get("objs");
            ObjectNode r = orange.advance(role.id, (int) number(request.body, "sid", 0),
                    obj instanceof List ? (List<?>) obj : Collections.emptyList(),
                    (int) number(request.body, "flag", -1), request.type == 3002);
            if (r.path("outcome").asInt() > 0) {
                ObjectNode s = orange.snapshot(role.id);
                output.push(824,
                        data("iswin", s.path("finished").asBoolean(), "teamhp",
                                json.convertValue(s.path("teamhp"), List.class), "finished",
                                s.path("finished").asBoolean(), "idx", r.path("island").asInt(1)));
                if (r.has("uid"))
                    output.push(12,
                            data("sid", role.sceneId, "uids", Collections.singletonList(r.path("uid").asText())));
                output.push(826, data("current", s.path("current").asInt(), "total", s.path("total").asInt()));
                if (r.has("adds")) {
                    ObjectNode deck = orange.deck(role.id);
                    if (deck.has("list"))
                        output.push(2018, json.convertValue(deck, BODY));
                }
                if (r.has("adds"))
                    publish(role, output, r.path("adds"));
            }
            return true;
        }
        if (OrangeService.scene(role.sceneId) && BLOCKED_STARTS.contains(request.type)) {
            if (request.session > 0)
                output.send(request, data("errno", 2002));
            return true;
        }
        return false;
    }

    /** 推送道具和满包邮件。
     * @param role 角色
     * @param output 连接
     * @param adds 原道具到账
     * @throws IOException 写入失败
     */
    private void publish(LocalRole role, GameReply output, JsonNode adds) throws IOException {
        output.push(14, data("flags", "orange.reward", "adds", json.convertValue(adds, BODY)));
        bags.pushBag(role, output);
        bags.pushMailCount(role, output);
    }
}
