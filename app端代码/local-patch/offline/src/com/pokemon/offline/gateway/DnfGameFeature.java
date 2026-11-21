package com.pokemon.offline.gateway;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.DnfService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;

import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 地下城的原面板、委托、抽奖、赛季结算和战斗回合协议。
 *
 * @author tongruiyuan
 * @since 2026-11-20 14:49
 */
@Service
@Order(-180)
final class DnfGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY =
            new TypeReference<Map<String, Object>>() {};
    private final DnfService dnf;
    private final BagSnapshot bags;
    private final RoleService roles;
    private final ObjectMapper json;
    private final Map<GameReply, View> views = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 推送钱包及角色经验的已提交值。
     *
     * @param role 当前角色
     * @param out 游戏连接
     * @throws IOException 推送失败
     */
    private void wallet(LocalRole role, GameReply out) throws IOException {
        LocalRole saved = roles.findById(role.id).orElse(role);
        out.push(
                3,
                data(
                        "level",
                        saved.level,
                        "exp",
                        saved.exp,
                        "coins",
                        saved.coins,
                        "diamonds",
                        saved.diamonds));
    }

    /** 连接已展示的赛季和下次检查时间。 */
    private static final class View {
        final String id = UUID.randomUUID().toString();
        long week, next;
        ObjectNode panel;
    }

    /**
     * 绑定地下城业务、资产展示及编码器。
     *
     * @param dnf 地下城服务
     * @param roles 当前角色的钱包与等级
     * @param bags 背包与邮件
     * @param json 编码器
     */
    DnfGameFeature(DnfService dnf, RoleService roles, BagSnapshot bags, ObjectMapper json) {
        this.dnf = dnf;
        this.roles = roles;
        this.bags = bags;
        this.json = json;
    }

    /**
     * 推送当前赛季快照。
     *
     * @param role 本地角色
     * @param output 游戏连接
     * @throws IOException 连接失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        if (!DnfService.open(role)) return;
        View view = views.computeIfAbsent(output, k -> new View());
        ObjectNode panel = dnf.panel(role.id);
        if (!panel.equals(view.panel)) output.push(453, json.convertValue(panel, BODY));
        view.panel = panel;
        view.week = DnfService.week();
        view.next = Instant.now().getEpochSecond() + 60;
    }

    /**
     * 随自然周、寻宝收益和挑战次数刷新状态。
     *
     * @param role 本地角色
     * @param output 游戏连接
     * @throws IOException 连接失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        View view = views.get(output);
        if (DnfService.open(role) && (view == null || view.next <= Instant.now().getEpochSecond()))
            synchronize(role, output);
    }

    /**
     * 刷新本次操作实际发放的资产。
     *
     * @param role 本地角色
     * @param out 原游戏连接
     * @param changes 资产明细
     * @param flag 原 UI 奖励监听标志
     * @throws IOException 连接失败
     */
    private void grant(LocalRole role, GameReply out, JsonNode changes, String flag)
            throws IOException {
        if (changes == null || changes.isEmpty()) return;
        out.push(14, data("flags", flag, "adds", json.convertValue(changes, BODY)));
        wallet(role, out);
        bags.push(role.id, out);
        bags.pushMailCount(role.id, out);
    }

    /**
     * 转发地下城动作并以其事务提交后的状态应答。
     *
     * @param role 已验证角色
     * @param req 请求
     * @param out 游戏连接
     * @return 是否已接管此动作
     * @throws IOException 连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request req, GameReply out)
            throws IOException {
        int cmd = req.type;
        if (cmd >= 293 && cmd <= 302) {
            if (req.session <= 0) return true;
            View view = views.computeIfAbsent(out, k -> new View());
            if (view.week == 0 || cmd == 293) synchronize(role, out);
            ObjectNode result =
                    dnf.act(
                            role.id,
                            cmd,
                            json.valueToTree(req.body),
                            view.week,
                            view.id + ":" + req.session);
            JsonNode adds = result.remove("adds");
            if (cmd == 294 && result.path("errno").asInt() == 0) {
                result.retain("errno", "seed", "weather", "monster", "player");
                for (String side : Arrays.asList("player", "monster"))
                    for (JsonNode actor : result.path(side).path("pokemon"))
                        ((ObjectNode) actor)
                                .remove(
                                        Arrays.asList(
                                                "skill",
                                                "targetLimit",
                                                "physical",
                                                "goldCarry",
                                                "homeStatues",
                                                "baseDamage",
                                                "actions",
                                                "type",
                                                "bigger"));
            }
            out.send(req, json.convertValue(result, BODY));
            if (cmd == 299
                    && result.path("errno").asInt() == 0
                    && result.path("refreshtimes").asInt() > 1) {
                wallet(role, out);
                bags.push(role.id, out);
            }
            grant(role, out, adds, cmd == 298 ? "dnf.dnf_raffle" : "dnf.reward");
            if (result.path("errno").asInt() == 0
                    && cmd != 293
                    && cmd != 296
                    && cmd != 297
                    && cmd != 302) synchronize(role, out);
            return true;
        }
        switch (cmd) {
            case 3001:
            case 3002:
                if (!dnf.active(role.id)) return false;
                Object targets = req.body.get("objs");
                ObjectNode result =
                        dnf.advance(
                                role.id,
                                (int) number(req.body, "sid", 0),
                                targets instanceof List
                                        ? (List<?>) targets
                                        : Collections.emptyList(),
                                (int) number(req.body, "flag", -1),
                                cmd == 3002);
                if (result.has("result")) {
                    JsonNode adds = result.remove("adds");
                    out.push(454, json.convertValue(result, BODY));
                    grant(role, out, adds, "dnf.dnf_atk");
                    synchronize(role, out);
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
            case 123:
            case 127:
            case 168:
            case 169:
            case 181:
            case 182:
            case 268:
            case 272:
            case 273:
            case 285:
            case 290:
            case 333:
            case 2203:
            case 3037:
            case 5069:
            case 5071:
            case 5072:
            case 5101:
            case 10016:
            case 10040:
            case 3651:
            case 10034:
                if (!dnf.active(role.id)) return false;
                if (req.session > 0) out.send(req, data("errno", 2002));
                return true;
            default:
                return false;
        }
    }
}
