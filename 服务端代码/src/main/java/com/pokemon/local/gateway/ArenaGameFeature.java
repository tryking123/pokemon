package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.ArenaService;
import com.pokemon.local.arena.ZodiacService;
import com.pokemon.local.arena.ZodiacShopService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
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
 * 将原竞技场、单人竞技及精灵王入口接入本地 NPC 训练。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:14
 */
@Service
@Order(-20)
final class ArenaGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {};
    private final ArenaService arena;
    private final ZodiacService zodiac;
    private final ZodiacShopService zodiacShop;
    private final ObjectMapper json;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final InventoryService inventory;
    private final InventoryGameFeature mail;
    private final Map<GameReply, String> connectionIds = new WeakHashMap<>();

    /**
     * 绑定训练及客户端快照服务。
     *
     * @param arena 本地训练
     * @param zodiac 十二宫远征
     * @param zodiacShop 十二宫货架
     * @param json 协议模型编码
     * @param roles 本地角色资产
     * @param bags 背包刷新
     * @param inventory 物品类型
     * @param mail 邮件提示
     */
    ArenaGameFeature(ArenaService arena, ZodiacService zodiac, ZodiacShopService zodiacShop, ObjectMapper json, RoleService roles, BagSnapshot bags,
                     InventoryService inventory, InventoryGameFeature mail) {
        this.arena = arena;
        this.zodiac = zodiac;
        this.zodiacShop = zodiacShop;
        this.json = json;
        this.roles = roles;
        this.bags = bags;
        this.inventory = inventory;
        this.mail = mail;
    }

    /**
     * 接收原客户端竞技训练入口及该角色已经打开的专属战斗操作。
     *
     * @param role 已登录角色
     * @param request 原协议请求
     * @param output 协议应答和推送
     * @return 是否消费该请求
     * @throws IOException 消息发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case 100:
            case 103:
                sendInfo(role, request, output, ArenaService.SOLO, 0, request.type == 103);
                return true;
            case 101:
                setLineup(role, request, output, ArenaService.SOLO, 0);
                return true;
            case 102:
                start(role, request, output, ArenaService.SOLO, 0, integer(request, "pkrank"), 1);
                return true;
            case 104:
            case 106:
                sendInfo(role, request, output, ArenaService.ARENA, 0, request.type == 106);
                return true;
            case 105:
                start(role, request, output, ArenaService.ARENA, 0,
                        integer(request, "ranking"), integer(request, "teamid"));
                return true;
            case 107:
                sendInfo(role, request, output, ArenaService.KING, integer(request, "type"), false);
                return true;
            case 108:
                setLineup(role, request, output, ArenaService.KING, integer(request, "type"));
                return true;
            case 272:
                setLineup(role, request, output, ArenaService.ZODIAC, 0);
                return true;
            case 109:
                start(role, request, output, ArenaService.KING, integer(request, "type"), integer(request, "pos"), 1);
                return true;
            case 110:
            case 111:
            case 112:
                buy(role, request, output, request.type == 110 ? ArenaService.ARENA
                        : request.type == 111 ? ArenaService.KING : ArenaService.SOLO);
                return true;
            case 113:
                claim(role, request, output);
                return true;
            case 3001:
            case 3002:
                if (!arena.active(role.id)) {
                    return false;
                }
                ObjectNode result = request.type == 3002 ? arena.flee(role.id)
                        : arena.synchronize(role.id, integer(request, "sid"), targets(request),
                                (int) number(request.body, "flag", -1));
                pushResult(role, result, output);
                return true;
            case 268: case 181:
            case 2203:
            case 3037:
                if (!arena.active(role.id)) {
                    return false;
                }
                if (request.session > 0) {
                    output.send(request, data("errno", 2002));
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * 登录和改阵后同步十二宫编队。
     *
     * @param role 当前角色
     * @param output 推送出口
     * @throws IOException 消息发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        Map<String, Object> snapshot = body(zodiac.snapshot(role.id));
        snapshot.putAll(zodiacShop.snapshot(role.id));
        output.push(810, snapshot);
    }

    /**
     * 回传原客户端面板或换批数据。
     *
     * @param role 当前角色
     * @param request 面板请求
     * @param output 应答出口
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param refresh 是否主动更换对手
     * @throws IOException 消息发送失败
     */
    private void sendInfo(LocalRole role, SprotoProtocol.Request request, GameReply output, String mode, int type,
                          boolean refresh) throws IOException {
        if (request.session > 0) {
            ObjectNode result = arena.information(role.id, mode, type, refresh);
            if (refresh) {
                result.retain("errno", "matchingPlayers", "matchingtime");
            }
            output.send(request, body(result));
        }
    }

    /**
     * 保存单人或精灵王阵容。
     *
     * @param role 当前角色
     * @param request 原阵容请求
     * @param output 应答出口
     * @param mode 训练模式
     * @param type 精灵王属性
     * @throws IOException 消息发送失败
     */
    private void setLineup(LocalRole role, SprotoProtocol.Request request, GameReply output, String mode, int type)
            throws IOException {
        if (request.session <= 0) {
            return;
        }
        List<Map<String, Object>> selection = new ArrayList<>();
        if (ArenaService.SOLO.equals(mode)) {
            selection.add(request.body);
        } else {
            Object value = request.body.get("elflist");
            if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    selection.add(json.convertValue(item, BODY));
                }
            } else {
                output.send(request, data("errno", 100021));
                return;
            }
        }
        long formation = number(request.body, ArenaService.ZODIAC.equals(mode) ? "tactics" : "positionid", 1);
        int error = formation < 1 || formation > 7 ? 100021 : arena.setLineup(role.id, mode, type, (int) formation, selection);
        output.send(request, data("errno", error));
        if (error == 0 && ArenaService.ZODIAC.equals(mode)) {
            synchronize(role, output);
        }
        if (error == 0 && ArenaService.KING.equals(mode)) {
            output.push(423, body(json.createObjectNode().set("lineup", arena.kingLineups(role.id))));
        }
    }

    /**
     * 创建训练战斗并按原战斗开场协议应答。
     *
     * @param role 当前角色
     * @param request 原挑战请求
     * @param output 应答出口
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param position 对手显示序号
     * @param team 普通阵容编号
     * @throws IOException 消息发送失败
     */
    private void start(LocalRole role, SprotoProtocol.Request request, GameReply output, String mode,
                       int type, int position, int team) throws IOException {
        if (request.session > 0) {
            ObjectNode result = arena.start(role.id, mode, type, position, team);
            result.retain("errno", "seed", "weather", "player", "monster", "isnpc");
            for (JsonNode formation : new JsonNode[]{result.path("player"), result.path("monster")}) {
                for (JsonNode pokemon : formation.path("pokemon")) {
                    ObjectNode fighter = (ObjectNode) pokemon;
                    fighter.remove(Arrays.asList("goldCarry", "homeStatues", "baseDamage", "targetLimit", "physical"));
                    fighter.put("add_skill_point_limit", pokemon.path("add_skill_point_limit").asInt(0));
                }
            }
            output.send(request, body(result));
        }
    }

    /**
     * 按当前模式购买次数并推送实际本地钻石余额。
     *
     * @param role 当前角色
     * @param request 购买请求
     * @param output 应答和推送出口
     * @param mode 购买的训练模式
     * @throws IOException 消息发送失败
     */
    private void buy(LocalRole role, SprotoProtocol.Request request, GameReply output, String mode) throws IOException {
        if (request.session <= 0) {
            return;
        }
        String receipt;
        synchronized (connectionIds) {
            receipt = connectionIds.computeIfAbsent(output, key -> UUID.randomUUID().toString()) + ":" + request.session;
        }
        ObjectNode result = arena.buy(role.id, mode, receipt);
        output.send(request, data("errno", result.path("errno").asInt()));
        if (result.path("changed").asBoolean()) {
            output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            if (!ArenaService.KING.equals(mode)) {
                pushPanel(role, mode, output);
            }
        }
    }

    /**
     * 领取周奖并同步实际可见的背包或邮件。
     *
     * @param role 当前角色
     * @param request 原周奖请求
     * @param output 应答和推送出口
     * @throws IOException 消息发送失败
     */
    private void claim(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return;
        }
        ObjectNode result = arena.claim(role.id, integer(request, "idx"));
        Map<String, Object> response = data("errno", result.path("errno").asInt());
        if (result.has("idx")) {
            response.put("idx", result.path("idx").asInt());
        }
        output.send(request, response);
        if (result.path("changed").asBoolean()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : result.path("items")) {
                int id = item.path("id").asInt();
                items.add(data("id", id, "n", item.path("n").asLong()));
            }
            if (!items.isEmpty()) {
                output.push(14, data("flags", "arena.week_award", "adds", data("items", items)));
                bags.push(role.id, output);
            }
            mail.pushMailCount(role, output);
            pushPanel(role, ArenaService.ARENA, output);
        }
    }

    /**
     * 按已结算模式推送真实结果和面板变化。
     *
     * @param role 当前角色
     * @param result 已提交战斗结果，持续中为空
     * @param output 原协议推送出口
     * @throws IOException 消息发送失败
     */
    private void pushResult(LocalRole role, ObjectNode result, GameReply output) throws IOException {
        if (result == null) {
            return;
        }
        String mode = result.path("mode").asText();
        if (ArenaService.ARENA.equals(mode)) {
            if (result.path("won").asBoolean()) {
                output.push(421, data("pkpoint", result.path("points").asInt(), "diamonds", 0,
                        "oldranking", result.path("oldranking").asInt(), "nowranking", result.path("nowranking").asInt(),
                        "uprank", false));
            }
            pushPanel(role, mode, output);
        } else if (ArenaService.SOLO.equals(mode)) {
            pushPanel(role, mode, output);
        }
    }

    /**
     * 推送与原竞技信息事件完全相同的字段。
     *
     * @param role 当前角色
     * @param mode 竞技场或单人模式
     * @param output 原协议推送出口
     * @throws IOException 消息发送失败
     */
    private void pushPanel(LocalRole role, String mode, GameReply output) throws IOException {
        ObjectNode state = arena.information(role.id, mode, 0, false);
        if (ArenaService.ARENA.equals(mode)) {
            output.push(422, body(state.retain("matchingPlayers", "info", "award")));
        } else {
            output.push(420, body(state.retain("matchingPlayers", "info", "elf_battle_info")));
        }
    }

    /**
     * 读取原战斗技能目标列表。
     *
     * @param request 原回合包
     * @return 目标列表，无合法数组时为空
     */
    private List<?> targets(SprotoProtocol.Request request) {
        Object value = request.body.get("objs");
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    /**
     * 读取原整数型字段。
     *
     * @param request 原请求
     * @param key 字段名
     * @return 字段值，缺失时为零
     */
    private int integer(SprotoProtocol.Request request, String key) {
        long value = number(request.body, key, 0);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? 0 : (int) value;
    }

    /**
     * 将训练协议快照转换为网关字段。
     *
     * @param snapshot 训练快照
     * @return 具有原字段名的映射
     */
    private Map<String, Object> body(JsonNode snapshot) {
        return json.convertValue(snapshot, BODY);
    }
}
