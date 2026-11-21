package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.guild.GuildEncounterService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Iterator;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;
import static com.pokemon.local.guild.GuildEncounterService.BANDIT_BOSS;
import static com.pokemon.local.guild.GuildEncounterService.GUARD;
import static com.pokemon.local.guild.GuildEncounterService.GUILD_BOSS;

/**
 * 原客户端强盗和公会副本入口的本地战斗协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:31
 */
@Service
@Order(-25)
final class GuildEncounterGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {};
    private final GuildEncounterService encounters;
    private final ObjectMapper json;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final ObjectProvider<PersonalGuildGameFeature> guildPanel;

    /**
     * 注入本地遭遇与协议信息。
     *
     * @param encounters 遭遇与结算业务
     * @param json 战斗快照转换工具
     * @param roles 角色场景存档
     * @param inventory 背包和邮件快照
     * @param guildPanel 公会资料快照的延迟协议组件
     */
    GuildEncounterGameFeature(GuildEncounterService encounters, ObjectMapper json, RoleService roles,
                              InventoryGameFeature inventory, ObjectProvider<PersonalGuildGameFeature> guildPanel) {
        this.encounters = encounters;
        this.json = json;
        this.roles = roles;
        this.inventory = inventory;
        this.guildPanel = guildPanel;
    }

    /**
     * 处理场景强盗与会长已开启的首领副本。
     *
     * @param role 已认证角色
     * @param request 原客户端协议请求
     * @param output 应答和推送
     * @return 已消费请求时为真
     * @throws IOException 写入客户端失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 3001 || request.type == 3002) {
            if (!encounters.active(role.id)) {
                return false;
            }
            settle(role, request, output);
            return true;
        }
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 66:
                output.send(request, data("errno", 0, "count", encounters.used(role.id, GUARD, 1)));
                return true;
            case 68:
                LocalRole saved = roles.findById(role.id).orElse(role);
                JsonNode actor = encounters.banditBoss(saved);
                Map<String, Object> state = data("errno", actor.isMissingNode() ? 3 : 0,
                        "bosstimes", encounters.used(role.id, BANDIT_BOSS, 1),
                        "list", json.convertValue(encounters.banditRank(saved), List.class));
                if (!actor.isMissingNode()) {
                    state.put("monInfo", json.convertValue(actor, BODY));
                }
                output.send(request, state);
                return true;
            case 69:
                List<Map<String, Object>> items = java.util.Arrays.asList(data("id", 21063, "n", 2),
                        data("id", 20027, "n", 1));
                output.send(request, data("errno", 0, "awardlist", Collections.singletonList(data("rank", 1,
                        "attach", data("items", items))), "partaward", data("items", Collections.emptyList())));
                return true;
            case GUARD:
            case BANDIT_BOSS:
            case GUILD_BOSS:
                challenge(role, request, output);
                return true;
            case 5071:
                int index = integer(request, "index", 0);
                int errno = encounters.open(role.id, index);
                if (errno == 0) {
                    pushBossState(role, index, output);
                    pushNumbers(role, output);
                    guildPanel.getIfAvailable().synchronizeGuild(role, output);
                }
                output.send(request, data("errno", errno));
                return true;
            case 5072:
                ObjectNode boss = encounters.bossGroup(role.id, integer(request, "index", 0));
                output.send(request, boss == null ? data("errno", 3) : json.convertValue(boss, BODY));
                return true;
            case 268: case 181:
            case 102:
            case 105:
            case 109:
            case 2203:
            case 3037:
            case 8:
            case 57:
            case 1501:
            case 3013:
            case 3014:
                if (encounters.active(role.id)) {
                    output.send(request, data("errno", 3));
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * 还原原客户端在角色上线时需要的挑战额度。
     *
     * @param role 当前登录角色
     * @param output 原推送通道
     * @throws IOException 写入客户端失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushNumbers(role, output);
        pushBossState(role, 0, output);
    }

    /**
     * 发送有效的对战队伍和可重放的同一场种子。
     *
     * @param role 当前角色
     * @param request 原开战请求
     * @param output 原应答出口
     * @throws IOException 写入客户端失败
     */
    private void challenge(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String target = request.body.get("uid") instanceof String ? (String) request.body.get("uid") : "";
        ObjectNode result = encounters.start(role.id, request.type, target, integer(request, "id", 0),
                integer(request, "itype", 1), integer(request, "teamid", 1));
        int errno = result.path("errno").asInt();
        if (errno != 0) {
            output.send(request, data("errno", errno));
            return;
        }
        Map<String, Object> response = data("errno", 0, "seed", result.path("seed").asInt(),
                "weather", result.path("weather").asInt(), "player", formation(result.path("player")),
                "monster", formation(result.path("monster")));
        if (request.type == BANDIT_BOSS) {
            response.put("flag", false);
        }
        output.send(request, response);
        pushNumbers(role, output);
    }

    /**
     * 将真实结算推送至当前场景、奖励提示和首领面板。
     *
     * @param role 当前角色
     * @param request 当前回合请求
     * @param output 原协议推送
     * @throws IOException 写入客户端失败
     */
    private void settle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Object raw = request.body.get("objs");
        List<?> targets = raw instanceof List<?> ? (List<?>) raw : Collections.emptyList();
        ObjectNode result = encounters.action(role.id, integer(request, "sid", 0), targets,
                integer(request, "flag", -1), request.type == 3002);
        if (result == null) {
            return;
        }
        int mode = result.path("mode").asInt();
        if (mode == GUILD_BOSS) {
            output.push(2030, data("damage", result.path("damage").asLong()));
            if (result.path("won").asBoolean() && result.path("kind").asInt() == 1) {
                output.push(2025, data("status", 2, "index", result.path("index").asInt(), "id", result.path("stage").asInt()));
                pushBossState(role, result.path("index").asInt(), output);
            }
        } else if (result.path("won").asBoolean()) {
            output.push(12, data("sid", result.path("scene").asInt(),
                    "uids", Collections.singletonList(result.path("uid").asText())));
        }
        if (result.has("tabletDrop")) {
            output.push(467, data("index", result.path("tabletDrop").asInt()));
        }
        Map<String, Object> adds = data();
        if (result.has("treasureMap")) {
            List<Map<String, Object>> rewards = new java.util.ArrayList<>();
            rewards.add(data("id", result.path("treasureMap").asInt(), "n", 1, "type", 20));
            if (result.has("badgeMaterial")) {
                rewards.add(data("id", result.path("badgeMaterial").asInt(), "n", 1, "type", 20));
            }
            adds.put("antiquity", rewards);
        }
        if (!result.path("items").isEmpty()) {
            adds.put("items", json.convertValue(result.path("items"), List.class));
        }
        if (!adds.isEmpty()) {
            output.push(14, data("flags", "guild.localEncounterWin", "adds", adds, "loses", data()));
            if (result.has("treasureMap")) {
                output.push(465, encounters.antiquities(role.id));
            }
            inventory.pushBag(role, output);
        }
        inventory.pushMailCount(role, output);
        pushNumbers(role, output);
        if (request.type != 3002 && !result.path("won").asBoolean()) {
            output.push(1051, data());
        }
    }

    /**
     * 将已持久化的开放状态和当前首领阶段同步到原公会副本面板。
     *
     * @param role 当前角色
     * @param index 当前首领类型，零表示全部
     * @param output 原版状态推送出口
     * @throws IOException 消息写入失败
     */
    private void pushBossState(LocalRole role, int index, GameReply output) throws IOException {
        for (Map.Entry<Integer, Integer> phase : encounters.openedBosses(role.id, index).entrySet()) {
            output.push(2024, data("status", phase.getValue() == 0 ? 1 : 2, "index", phase.getKey()));
            if (phase.getValue() > 0) {
                output.push(2025, data("status", 1, "index", phase.getKey(), "id", phase.getValue()));
            }
        }
    }

    /**
     * 按两种界面的字段同步挑战次数。
     *
     * @param role 当前角色
     * @param output 原推送出口
     * @throws IOException 消息发送失败
     */
    private void pushNumbers(LocalRole role, GameReply output) throws IOException {
        long reset = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
        output.push(434, data("times", encounters.used(role.id, GUARD, 1), "nexttime", reset,
                "boss", encounters.used(role.id, BANDIT_BOSS, 1)));
        output.push(2027, data("normal", encounters.remaining(role.id, 1), "simulation", encounters.remaining(role.id, 2)));
    }

    /**
     * 发送仍存活的场上单位，保留原槽位并剔除仅供本地回合计算的字段。
     *
     * @param node 对局阵容快照
     * @return 原客户端可解码的阵容
     */
    private Map<String, Object> formation(JsonNode node) {
        ObjectNode wire = node.deepCopy();
        Iterator<JsonNode> actors = wire.withArray("pokemon").elements();
        while (actors.hasNext()) {
            ObjectNode actor = (ObjectNode) actors.next();
            if (actor.path("hp").asLong() <= 0) {
                actors.remove();
            } else {
                actor.remove(java.util.Arrays.asList("goldCarry", "homeStatues", "baseDamage", "targetLimit", "physical"));
            }
        }
        return json.convertValue(wire, BODY);
    }

    /**
     * 读取客户端提供的有效整数。
     *
     * @param request 当前请求
     * @param key 字段名称
     * @param fallback 字段缺失时的原默认值
     * @return 有效范围内的值，溢出时为最小整数
     */
    private int integer(SprotoProtocol.Request request, String key, int fallback) {
        long value = number(request.body, key, fallback);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }
}
