package com.pokemon.local.gateway;

import com.pokemon.local.gacha.GachaDraw;
import com.pokemon.local.gacha.GachaService;
import com.pokemon.local.gacha.GachaState;
import com.pokemon.local.gacha.SpecialEggPoolService;
import com.pokemon.local.quest.api.entity.QuestTask;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 游戏普通、高级、友情扭蛋和状态同步
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:02
 */
@Service
final class GachaGameFeature implements GameFeature {
    private final GachaService gacha;
    private final SpecialEggPoolService special;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final InventoryGameFeature bags;
    private final Map<GameReply, LocalConnection> connections = new WeakHashMap<>();

    /**
     * 初始化原协议扭蛋适配
     *
     * @param gacha 本地扭蛋服务
     * @param roles 最新角色存档查询
     * @param battle 精灵背包推送
     * @param bags 普通背包推送
     * @param special 超级蛋池目标及保底
     */
    GachaGameFeature(GachaService gacha, RoleService roles, BattleGameFeature battle, InventoryGameFeature bags,
                     SpecialEggPoolService special) {
        this.gacha = gacha;
        this.special = special;
        this.roles = roles;
        this.battle = battle;
        this.bags = bags;
    }

    /**
     * 结算原协议携带的扭蛋请求并推送实际资产变化
     *
     * @param role 连接认证的角色
     * @param request 原游戏请求
     * @param output 原协议消息出口
     * @return 是否由扭蛋模块处理
     * @throws IOException 写入应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 1901 && request.type != 10037) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        LocalConnection connection = connection(output);
        if (request.type == 10037) {
            int errno = special.refresh(role.id, connection.poolEnd, connection.poolTarget, connection.refreshes,
                    connection.id + ":" + request.session);
            output.send(request, data("errno", errno));
            if (errno == 0 || errno == 100013) {
                pushSpecial(role.id, connection, output);
            }
            if (errno == 0) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        long type = number(request.body, "mtype", 0);
        GachaDraw result = gacha.draw(role.id, connection.id + ":" + request.session, type);
        if (result.errno != 0) {
            output.send(request, data("errno", result.errno));
            return true;
        }
        output.send(request, data("errno", 0, "cd", result.state.normalTime,
                "normalnum", result.state.normalRemaining, "diamondnum", result.state.advancedTotal % 10,
                "diamondfreenum", result.state.advancedFree, "tenreward", Collections.emptyList(),
                "reward", result.rewards.stream().map(reward -> data("mtype", reward.type, "id", reward.id, "num", 1))
                        .collect(Collectors.toList())));
        if (!result.changed) {
            return true;
        }

        // 仅本次提交产生的数据变化才推送给界面。
        pushState(connection, result.state, output);
        if (type == 2 || type == 3) {
            pushSpecial(role.id, connection, output);
        }
        if (result.rewards.stream().anyMatch(reward -> reward.type == GachaService.ELF_REWARD)) {
            battle.pushElfs(role.id, output);
        }
        if (result.couponSpent || result.rewards.stream().anyMatch(reward -> reward.type != GachaService.ELF_REWARD)) {
            bags.pushBag(role, output);
        }
        if (result.diamondsSpent > 0) {
            long diamonds = roles.findById(role.id).map(saved -> saved.diamonds).orElse(role.diamonds);
            output.push(3, data("diamonds", diamonds, "servertime", Instant.now().getEpochSecond()));
        }
        if (type == 4) {
            pushFriendPoints(result.state, output);
        }
        for (QuestTask task : result.tasks) {
            output.push(101, data("id", task.id, "state", task.state, "progress", task.progress));
        }
        return true;
    }

    /**
     * 进场时告知当天剩余次数和历史抽取状态
     *
     * @param role 连接认证的角色
     * @param output 原协议消息出口
     * @throws IOException 写入推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        LocalConnection connection = connection(output);
        pushState(connection, gacha.snapshot(role.id), output);
        pushSpecial(role.id, connection, output);
    }

    /**
     * 更新原角色模型用于友情抽奖的余额。
     *
     * @param state 当前持久化扭蛋状态
     * @param output 原协议消息出口
     * @throws IOException 写入推送失败
     */
    private void pushFriendPoints(GachaState state, GameReply output) throws IOException {
        output.push(3, data("friendpoint", state.friendPoints, "servertime", Instant.now().getEpochSecond()));
    }

    /**
     * 业务日变化后更新同一连接的扭蛋次数
     *
     * @param role 连接认证的角色
     * @param output 原协议消息出口
     * @throws IOException 写入推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        LocalConnection connection = connection(output);
        if (connection.resetAt > 0 && Instant.now().getEpochSecond() >= connection.resetAt) {
            GachaState state = gacha.snapshot(role.id);
            connection.resetAt = state.nextReset;
            pushSpecial(role.id, connection, output);
            output.push(201, data("NormalMaxNum", state.normalRemaining,
                    "DiamondFreeNum", state.advancedFree, "ResetTime", state.nextReset));
        }
    }

    /**
     * 推送当前周的神兽、保底区间和当日手动刷新档位。
     * @param roleId 当前角色
     * @param connection 原协议连接
     * @param output 原协议消息出口
     * @throws IOException 发送失败
     */
    private void pushSpecial(String roleId, LocalConnection connection, GameReply output) throws IOException {
        Map<String, Object> state = special.snapshot(roleId);
        if (!state.isEmpty()) {
            connection.poolEnd = ((Number) state.get("next_time")).longValue();
            connection.poolTarget = ((Number) state.get("id")).intValue();
            connection.refreshes = ((Number) state.get("refresh")).intValue();
            output.push(2039, state);
        }
    }

    /**
     * 推送原客户端模型的完整扭蛋快照
     *
     * @param connection 当前连接状态
     * @param state 已读取的扭蛋快照
     * @param output 原协议消息出口
     * @throws IOException 写入推送失败
     */
    private void pushState(LocalConnection connection, GachaState state, GameReply output) throws IOException {
        connection.resetAt = state.nextReset;
        output.push(200, data("NormalMaxNum", state.normalRemaining, "NormalTime", state.normalTime,
                "DiamondFreeNum", state.advancedFree, "DiamondSumNum", state.advancedTotal % 10,
                "DiamondTenNum", state.advancedTotal / 10, "IsFirstNomal", state.normalTotal == 0 ? 1 : 0,
                "IsFirstDiamond", state.advancedTotal == 0 ? 1 : 0, "ResetTime", state.nextReset));
    }

    /**
     * 分配仅在同一 TCP 连接中复用的请求身份
     *
     * @param output 当前连接持有的消息出口
     * @return 本地连接身份
     */
    private LocalConnection connection(GameReply output) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, key -> new LocalConnection());
        }
    }

    /**
     * 用于区分不同连接的 Sproto 会话和每日刷新
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:02
     */
    private static final class LocalConnection {
        private final String id = UUID.randomUUID().toString();
        private long resetAt;
        private long poolEnd;
        private int poolTarget;
        private int refreshes;
    }
}
