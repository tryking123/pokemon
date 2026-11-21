package com.pokemon.local.gateway;

import com.pokemon.local.activitydaily.DailyActivityService;
import com.pokemon.local.battle.BattleService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.activitydaily.DailyActivityService.AttendanceState;
import com.pokemon.local.activitydaily.DailyActivityService.OnlineState;
import com.pokemon.local.activitydaily.DailyActivityService.Reward;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原客户端每日在线倒计时、签到与累签奖励入口。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:15
 */
@Service
final class DailyActivityGameFeature implements GameFeature {

    private final DailyActivityService activities;
    private final InventoryService inventory;
    private final InventoryGameFeature bags;
    private final RoleService roles;
    private final BattleService battles;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 初始化协议入口。
     *
     * @param activities 在线和签到服务
     * @param inventory 道具类型查询服务
     * @param roles 角色货币余额
     * @param battles 普通副本可用次数
     * @param bags 已写入背包快照推送
     */
    DailyActivityGameFeature(DailyActivityService activities, InventoryService inventory, InventoryGameFeature bags,
                             RoleService roles, BattleService battles) {
        this.roles = roles;
        this.battles = battles;
        this.activities = activities;
        this.inventory = inventory;
        this.bags = bags;
    }

    /**
     * 处理原协议的在线奖励与签到请求。
     *
     * @param role 当前已认证角色
     * @param request 已解码的客户端请求
     * @param output 当前连接推送通道
     * @return 是否处理了该请求
     * @throws IOException 向客户端发送数据失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int type = request.type;
        if (type == 1208 || type == 10992) {
            if (request.session <= 0) return true;
            Session client = session(output);
            long now = Instant.now().getEpochSecond();
            int error = client.ready ? activities.staminaAction(role.id, client.id + ":" + request.session, type == 1208, now) : 100021;
            output.send(request, data("errno", error));
            if (client.ready) {
                pushStamina(role.id, client, now, output);
                output.push(3, data("diamonds", roles.findById(role.id).get().diamonds));
                if (type == 10992 && error == 0) output.push(373, data("dnum", battles.remainingDungeonRuns(role.id, now)));
            }
            return true;
        }
        if (type != 10000 && type != 10001 && type != 130 && type != 131 && type != 132) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        Session connection = session(output);
        if (type == 10001) {
            OnlineState state = activities.online(role.id);
            output.send(request, data("errno", 0, "condition_time", state.next == null ? 0 : state.next.seconds,
                    "reward", state.next == null ? Collections.emptyList() : state.next.values()));
            pushOnline(connection, state, output);
        } else if (type == 10000) {
            Reward reward = connection.ready ? activities.claimOnline(
                    role.id, connection.onlineCycle, connection.id + ":" + request.session) : null;
            output.send(request, data("errno", reward == null ? 1 : 0));
            notifyReward(role, output, reward, "online.get_online_reward");
            pushOnline(connection, activities.online(role.id), output);
        } else if (type == 130) {
            boolean patch = Boolean.TRUE.equals(request.body.get("ispatch"));
            String receipt = patch ? connection.id + ":" + request.session : null;
            Reward reward = connection.ready ? activities.sign(
                    role.id, connection.signCycle, connection.signDay, receipt) : null;
            output.send(request, data("errno", reward == null ? 1 : 0, "ispatch", patch));
            notifyReward(role, output, reward, "sign.sign_up");
            pushAttendance(connection, activities.attendance(role.id), output);
        } else if (type == 131) {
            long idx = number(request.body, "idx", 0);
            Reward reward = connection.ready ? activities.signVip(role.id, connection.signCycle, idx) : null;
            output.send(request, data("errno", reward == null ? 1 : 0, "idx", idx));
            notifyReward(role, output, reward, "sign.sign_extra");
            pushAttendance(connection, activities.attendance(role.id), output);
        } else {
            long index = number(request.body, "idx", 0);
            Reward reward = connection.ready ? activities.claimAttendance(role.id, connection.signCycle, index) : null;
            output.send(request, data("errno", reward == null ? 1 : 0, "idx", index));
            notifyReward(role, output, reward, "sign.sign_total");
            pushAttendance(connection, activities.attendance(role.id), output);
        }
        return true;
    }

    /**
     * 开始观察同一已进场连接并发送两类活动快照。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @throws IOException 向客户端发送数据失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        Session connection = new Session();
        connection.ready = true;
        connection.lastSeen = Instant.now().getEpochSecond();
        synchronized (sessions) {
            sessions.put(output, connection);
        }
        pushStamina(role.id, connection, connection.lastSeen, output);
        pushOnline(connection, activities.online(role.id), output);
        pushAttendance(connection, activities.attendance(role.id), output);
    }

    /**
     * 心跳到达后记录有效在线区间，并在跨日时刷新原客户端。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @throws IOException 向客户端发送数据失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Session connection = session(output);
        if (!connection.ready) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        if (now >= connection.nextStaminaDay) pushStamina(role.id, connection, now, output);
        long before = connection.lastSeen;
        connection.lastSeen = now;
        if (now != before) {
            pushOnline(connection, activities.observe(role.id, before, now), output);
        }
        if (now >= connection.nextSignDay) {
            pushAttendance(connection, activities.attendance(role.id), output);
        }
    }

    /**
     * 通过客户端已有的 S18、S1210 恢复或刷新购次和余额。
     * @param role 角色编号
     * @param connection 本地连接状态
     * @param now 当前 Unix 秒
     * @param output 客户端推送
     * @throws IOException 推送失败
     */
    private void pushStamina(String role, Session connection, long now, GameReply output) throws IOException {
        long day = Math.floorDiv(now + 28800L, 86400L) * 86400L - 28800L;
        connection.nextStaminaDay = day + 86400L;
        output.push(18, data("times", activities.staminaPurchases(role, day), "nexttime", connection.nextStaminaDay));
        output.push(1210, data("tirepower", activities.staminaBalance(role), "nexttime", 0));
    }

    /**
     * 每期的每档奖励首次显示时同步服务器确认的剩余秒数。
     *
     * @param connection 当前连接的活动同步游标
     * @param state 在线活动快照
     * @param output 当前连接推送通道
     * @throws IOException 写入连接失败
     */
    private void pushOnline(Session connection, OnlineState state, GameReply output) throws IOException {
        int threshold = state.next == null ? -1 : state.next.seconds;
        // 原导航栏每收到 S10000 会新建计时器。同一档由客户端自行倒计时，心跳仍由服务端累计。
        if (connection.onlineCycle != state.cycle || connection.onlineThreshold != threshold) {
            output.push(10000, data("remaining_time", state.remaining));
            connection.onlineCycle = state.cycle;
            connection.onlineThreshold = threshold;
        }
    }

    /**
     * 用已提交的真实签到记录替换客户端状态。
     *
     * @param connection 当前连接的活动同步游标
     * @param state 签到快照
     * @param output 当前连接推送通道
     * @throws IOException 写入连接失败
     */
    private void pushAttendance(Session connection, AttendanceState state, GameReply output) throws IOException {
        output.push(437, data("index", state.availableDays, "today", state.today, "total", state.count, "patch", state.patched,
                "record", state.claims, "vip", state.vipClaims));
        connection.signCycle = state.cycle;
        connection.signDay = state.day;
        connection.nextSignDay = state.nextDay;
    }

    /**
     * 仅通知本次事务真实发放的奖励。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @param reward 已发放的奖励，未发奖时为空
     * @param flags 原协议中的奖励来源
     * @throws IOException 写入连接失败
     */
    private void notifyReward(LocalRole role, GameReply output, Reward reward, String flags) throws IOException {
        if (reward == null) {
            return;
        }
        if (reward.mailed) {
            bags.pushMailCount(role, output);
        }
        if (reward.amount == 0) {
            return;
        }
        Map<String, Object> adds = reward.type == DailyActivityService.DIAMONDS
                ? data("diamonds", reward.amount)
                : data("items", Collections.singletonList(data("id", reward.id, "n", reward.amount)));
        output.push(14, data("flags", flags, "adds", adds, "loses", data()));
        if (reward.type == DailyActivityService.ITEM) {
            bags.pushBag(role, output);
        }
    }

    /**
     * 获取当前连接的活动游标。
     *
     * @param output 当前连接推送通道
     * @return 当前连接的活动游标
     */
    private Session session(GameReply output) {
        synchronized (sessions) {
            return sessions.computeIfAbsent(output, key -> new Session());
        }
    }

    /**
     * 对应一条当前认证游戏连接的观察与界面游标。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:15
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private boolean ready;
        private long lastSeen;
        private long onlineCycle = Long.MIN_VALUE;
        private int onlineThreshold = Integer.MIN_VALUE;
        private long signCycle;
        private int signDay;
        private long nextSignDay;
        private long nextStaminaDay;
    }
}
