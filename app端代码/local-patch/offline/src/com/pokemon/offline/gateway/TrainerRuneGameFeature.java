package com.pokemon.offline.gateway;

import static com.pokemon.offline.gateway.SprotoProtocol.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activitylevel.TrainerRuneService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;

import com.pokemon.offline.platform.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 连接原训练家装备刻印、分解和贴纸商店界面。
 *
 * @author tongruiyuan
 * @since 2026-11-20 14:01
 */
@Service
final class TrainerRuneGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> WIRE =
            new TypeReference<Map<String, Object>>() {};
    private final TrainerRuneService runes;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final ObjectMapper json;
    private final com.pokemon.offline.role.RoleBattlePowerService power;
    private final Map<GameReply, View> views = new WeakHashMap<>();

    /**
     * 连接角色真实贴纸、货币和背包快照。
     *
     * @param runes 贴纸账本
     * @param roles 钱包读回
     * @param bags 原背包快照
     * @param json 请求与响应转换
     * @param power 刻印后实际生效的角色战力
     */
    TrainerRuneGameFeature(
            TrainerRuneService runes, RoleService roles, BagSnapshot bags, ObjectMapper json,
            com.pokemon.offline.role.RoleBattlePowerService power) {
        this.runes = runes;
        this.roles = roles;
        this.bags = bags;
        this.json = json;
        this.power = power;
    }

    /** 保留本连接实际收到的货架版本。 */
    private synchronized View view(GameReply out) {
        return views.computeIfAbsent(out, k -> new View());
    }

    /**
     * 登录时恢复商店、碎片余额及自动刷新时间。
     *
     * @param role 当前实际角色
     * @param out 原连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply out) throws IOException {
        pushShop(role.id, out);
    }

    /**
     * 原货架倒计时结束后按服务器四小时窗口刷新。
     *
     * @param role 当前角色
     * @param out 原连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply out) throws IOException {
        if (java.time.Instant.now().getEpochSecond() >= view(out).next) pushShop(role.id, out);
    }

    /**
     * 推送服务器当前货架及钱包的绝对值。
     *
     * @param id 当前角色编号
     * @param out 原协议连接
     * @throws IOException 写入连接失败
     */
    private void pushShop(String id, GameReply out) throws IOException {
        ObjectNode s = runes.shop(id);
        View view = view(out);
        view.next = s.path("autotime").asLong();
        view.revision = s.path("rev").asLong();
        out.push(
                803,
                json.convertValue(
                        s.deepCopy().retain("chips", "list", "autotime", "manaual"), WIRE));
    }

    /**
     * 将原操作提交至同一个角色事务，向界面同步实物和真实余额。
     *
     * @param role 当前角色
     * @param req 玩家原请求
     * @param out 原连接
     * @return 是否由刻印业务承接
     * @throws IOException 写入响应失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request req, GameReply out)
            throws IOException {
        int action = req.type;
        boolean gift =
                action == 2401
                        && TrainerRuneService.isGift(SprotoProtocol.number(req.body, "id", 0));
        if (!gift && (action < 2528 || action > 2533)) return false;
        if (req.session <= 0) return true;
        View v = view(out);
        ObjectNode r =
                runes.act(
                        role.id,
                        action,
                        json.valueToTree(req.body),
                        v.revision,
                        v.connection + ":" + action + ":" + req.session);
        boolean success = r.path("errno").asInt() == 0;
        if (success && !r.path("replayed").asBoolean() && r.has("adds")) {
            out.push(
                    14, data("flags", "item.use", "adds", json.convertValue(r.path("adds"), WIRE)));
        }
        if (gift && success) {
            bags.push(role.id, out);
        }
        if (action == 2528 && success) {
            bags.push(role.id, out);
            out.push(3, data("battlepower", power.score(role.id)));
        }
        out.send(
                req,
                json.convertValue(
                        r.deepCopy()
                                .retain(
                                        "errno",
                                        "suit_data",
                                        "list",
                                        "manaual",
                                        "idx",
                                        "chips",
                                        "sid",
                                        "lock",
                                        "finalchips"),
                        WIRE));
        if (success && !gift) {
            if (action != 2528) bags.push(role.id, out);
            if (action >= 2528 && action <= 2530) {
                LocalRole saved = roles.findById(role.id).orElse(role);
                out.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
            }
        }
        pushShop(role.id, out);
        return true;
    }

    /** 对应一个实际原连接的同步信息。 */
    private static final class View {
        final String connection = UUID.randomUUID().toString();
        long next, revision = -1;
    }
}
