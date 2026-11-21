package com.pokemon.local.gateway;

import com.pokemon.local.activity.ActivityScoreExchangeService;
import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.WishWellService;
import com.pokemon.local.activity.FirstChargeRushService;
import com.pokemon.local.activity.OperatingActivityService;
import com.pokemon.local.activity.OperatingActivityService.Result;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.activitydaily.RechargePromotionService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.activity.OperatingActivityRules.ATTENDANCE;
import static com.pokemon.local.activity.OperatingActivityRules.DAILY_SPEND;
import static com.pokemon.local.activity.OperatingActivityRules.EXCHANGE;
import static com.pokemon.local.activity.OperatingActivityRules.LIFETIME_RECHARGE;
import static com.pokemon.local.activity.OperatingActivityRules.WEEKLY_SPEND;
import static com.pokemon.local.activity.ActivityScoreExchangeService.KYLIN;
import static com.pokemon.local.activity.ActivityScoreExchangeService.REBATE;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原 Unity 神秘兑换、消耗活动、节日签到和终身累充协议入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 10:15
 */
@Service
final class OperatingActivityGameFeature implements GameFeature {
    private final ActivityService calendar;
    private final OperatingActivityService activities;
    private final RechargePromotionService recharge;
    private final ActivityScoreExchangeService scores;
    private final FirstChargeRushService firstChargeRush;
    private final InventoryService inventory;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final WishWellService wishes;
    private final InventoryGameFeature mail;
    private final Map<GameReply, Session> connections = new WeakHashMap<>();

    /**
     * 绑定运营活动规则、事务结算与实时资产同步。
     *
     * @param calendar 运营活动周期
     * @param activities 神秘兑换与节日签到
     * @param recharge 三天循环累充及消费奖档
     * @param scores 神兽降临和超级返利积分兑换
     * @param firstChargeRush 首冲来袭顺序领奖
     * @param inventory 道具显示类型
     * @param roles 角色货币余额
     * @param wishes 许愿池结算
     * @param mail 奖励溢出邮件数量同步
     * @param bags 物品背包快照
     */
    OperatingActivityGameFeature(ActivityService calendar, OperatingActivityService activities,
                                 RechargePromotionService recharge, ActivityScoreExchangeService scores,
                                 FirstChargeRushService firstChargeRush, InventoryService inventory,
                                 RoleService roles, BagSnapshot bags, WishWellService wishes, InventoryGameFeature mail) {
        this.calendar = calendar;
        this.activities = activities;
        this.recharge = recharge;
        this.scores = scores;
        this.firstChargeRush = firstChargeRush;
        this.inventory = inventory;
        this.roles = roles;
        this.bags = bags;
        this.wishes = wishes;
        this.mail = mail;
    }

    /**
     * 响应原游戏的活动列表、兑换、签到及领奖。
     *
     * @param role 当前已认证的角色
     * @param request 原客户端请求
     * @param output Sproto 回复及推送通道
     * @return 是否处理了请求
     * @throws IOException 写入协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 140:
            case 141:
                exchange(role, request, output);
                return true;
            case 142:
            case 143:
                promotion(role, request, output, WEEKLY_SPEND);
                return true;
            case 194:
            case 195:
                promotion(role, request, output, DAILY_SPEND);
                return true;
            case 211:
            case 212:
                rushJob(role, request, output);
                return true;
            case 219:
            case 220:
            case 221:
                attendance(role, request, output);
                return true;
            case 222:
            case 223:
                chargeGift(role, request, output);
                return true;
            case 261:
            case 262:
            case 263:
                investment(role, request, output);
                return true;
            case 265:
            case 266:
            case 267:
                wish(role, request, output);
                return true;
            case 370:
            case 371:
                promotion(role, request, output, LIFETIME_RECHARGE);
                return true;
            case 10008:
                List<Integer> redpoints = redpoints(role.id);
                session(output).redpoints = redpoints;
                output.send(request, data("info", redpoints));
                return true;
            default:
                return false;
        }
    }

    /**
     * 登录进场后重新建立各活动的客户端快照游标和红点状态。
     *
     * @param role 当前角色
     * @param output 当前连接通道
     * @throws IOException 写入协议失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        synchronized (connections) {
            connections.put(output, new Session());
        }
        tick(role, output);
    }

    /**
     * 本地订单、消费或周期变化后推送新出现的可领奖项。
     *
     * @param role 当前角色
     * @param output 当前连接通道
     * @throws IOException 写入协议失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Session state = session(output);
        List<Integer> available = redpoints(role.id);
        List<Integer> added = new ArrayList<>(available);
        added.removeAll(state.redpoints);
        if (!added.isEmpty()) {
            output.push(10003, data("info", added));
        }
        state.redpoints = available;
    }

    /**
     * 展示对应单笔充值礼包或领取当日奖品。
     *
     * @param role 当前角色
     * @param request 原礼包面板请求
     * @param output 应答与资产推送通道
     * @throws IOException 协议发送失败
     */
    private void chargeGift(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 222) {
            state.chargeGiftPeriod = calendar.cycle(OperatingActivityService.CHARGE_GIFT);
            output.send(request, activities.chargeGiftStatus(role.id, state.chargeGiftPeriod));
            return;
        }
        long index = number(request.body, "idx", 0);
        Result result = activities.claimChargeGift(role.id, index, state.chargeGiftPeriod);
        output.send(request, data("errno", result.errno, "idx", index));
        if (result.errno == 0) {
            pushRewards(role, "activity.chargegift", result.rewards, 0, output);
            mail.pushMailCount(role, output);
            if (activities.chargeGiftAvailable(role.id)) {
                output.push(10003, data("info", Collections.singletonList(OperatingActivityService.CHARGE_GIFT)));
            }
        }
    }

    /**
     * 处理原限时任务的列表与当天领取请求。
     *
     * @param role 已认证角色
     * @param request 原面板请求
     * @param output 回复与资产推送通道
     * @throws IOException 协议写入失败
     */
    private void rushJob(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 211) {
            state.rushPeriod = calendar.cycle(OperatingActivityService.RUSH_JOB);
            output.send(request, activities.rushJobs(role.id, state.rushPeriod));
            return;
        }
        long index = number(request.body, "idx", 0);
        Result result = activities.claimRushJob(role.id, index, state.rushPeriod);
        if (result.errno == 0) {
            pushRewards(role, "activity.rush", result.rewards, 0, output);
        }
        output.send(request, data("errno", result.errno, "idx", index));
        if (result.errno == 0 && activities.rushJobAvailable(role.id)) {
            output.push(10003, data("info", Collections.singletonList(OperatingActivityService.RUSH_JOB)));
        }
    }

    /**
     * 处理许愿池的选择、锁定及次日领取。
     *
     * @param role 当前角色
     * @param request 原许愿请求
     * @param output 面板应答与资产推送通道
     * @throws IOException 写入协议失败
     */
    private void wish(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 265) {
            state.wishDay = wishes.day();
            output.send(request, wishes.status(role.id, state.wishDay));
        } else if (request.type == 266) {
            Object indexes = request.body.get("idx");
            int error = wishes.lock(role.id, state.wishDay, indexes instanceof List
                    ? (List<?>) indexes : Collections.emptyList());
            output.send(request, data("errno", error));
        } else {
            int times = wishes.claim(role.id, state.wishDay);
            if (times > 0) {
                bags.push(role.id, output);
                LocalRole updated = roles.findById(role.id).orElse(role);
                output.push(3, data("diamonds", updated.diamonds));
            }
            output.send(request, data("errno", times > 0 ? 0 : -times, "double", Math.max(times, 0)));
        }
    }

    /**
     * 处理限时投资面板、购买及逐档领奖。
     *
     * @param role 当前角色
     * @param request 原投资请求
     * @param output 应答和资产推送通道
     * @throws IOException 协议写入失败
     */
    private void investment(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 261) {
            ActivityPeriod period = calendar.cycle(OperatingActivityService.INVESTMENT);
            state.investmentStart = period.start;
            output.send(request, activities.investmentStatus(role.id, period));
            return;
        }
        long index = number(request.body, "idx", -1);
        Result result = request.type == 262 ? activities.buyInvestment(role.id, state.investmentStart)
                : activities.claimInvestment(role.id, state.investmentStart, index);
        if (result.errno == 0) {
            pushRewards(role, "activity.invest", result.rewards, result.spentDiamonds, output);
        }
        output.send(request, request.type == 263 ? data("errno", result.errno, "idx", index)
                : data("errno", result.errno));
    }

    /**
     * 处理神秘兑换并刷新扣款、真实库存和客户端兑换计数。
     *
     * @param role 当前角色
     * @param request 兑换列表或购买请求
     * @param output 当前连接通道
     * @throws IOException 写入协议失败
     */
    private void exchange(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 140) {
            ActivityPeriod period = calendar.cycle(EXCHANGE);
            state.exchangeStart = period.start;
            output.send(request, activities.exchangeStatus(role.id, period));
            return;
        }
        long index = number(request.body, "idx", 0);
        Result result = activities.exchange(role.id, index, state.exchangeStart, state.id + ":" + request.session);
        if (result.errno == 0) {
            pushRewards(role, "activity.convert", result.rewards, result.spentDiamonds, output);
        }
        output.send(request, data("errno", result.errno, "idx", index));
    }

    /**
     * 查询节日签到及办理当日领奖或累计领奖。
     *
     * @param role 当前角色
     * @param request 原节日签到请求
     * @param output 当前连接通道
     * @throws IOException 写入协议失败
     */
    private void attendance(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Session state = session(output);
        if (request.type == 219) {
            state.attendancePeriod = calendar.cycle(ATTENDANCE);
            output.send(request, activities.attendanceStatus(role.id, state.attendancePeriod));
            return;
        }
        ActivityPeriod period = state.attendancePeriod;
        if (period == null) {
            output.send(request, data("errno", 100013));
            return;
        }
        boolean daily = request.type == 220;
        Result result = daily ? activities.claimAttendance(role.id, number(request.body, "bit", 0), period.start, period.day)
                : activities.claimBox(role.id, number(request.body, "idx", 0), period.start);
        if (result.errno == 0) {
            pushRewards(role, "activity.clockin", result.rewards, 0, output);
        }
        output.send(request, data("errno", result.errno, daily ? "get" : "idx", result.value));
    }

    /**
     * 返回累充或消费进度，或领取指定档位的真实奖励。
     *
     * @param role 当前角色
     * @param request 原活动请求
     * @param output 当前连接通道
     * @param activityId 运营活动编号
     * @throws IOException 写入协议失败
     */
    private void promotion(LocalRole role, SprotoProtocol.Request request, GameReply output, int activityId)
            throws IOException {
        Session state = session(output);
        if (request.type % 2 == 0) {
            ActivityPeriod period = recharge.window(activityId);
            state.promotionStarts.put(activityId, period.start);
            output.send(request, recharge.status(role.id, activityId, period));
            return;
        }
        long index = number(request.body, "idx", 0);
        long start = state.promotionStarts.getOrDefault(activityId, Long.MIN_VALUE);
        RechargePromotionService.Result result = recharge.claim(role.id, activityId, index, start);
        if (result.errno == 0) {
            String source = activityId == LIFETIME_RECHARGE ? "activity.alwaysamass" : "activity.consume";
            pushRewards(role, source, result.rewards, 0, output);
        }
        output.send(request, data("errno", result.errno, "idx", index));
    }

    /**
     * 发送已持久化的钻石变化、道具和统一奖励提示。
     *
     * @param role 当前角色
     * @param source 奖励发放来源
     * @param rewards 已实际发放的道具三元组
     * @param spent 本次消耗的钻石
     * @param output 当前连接通道
     * @throws IOException 写入协议失败
     */
    private void pushRewards(LocalRole role, String source, List<Integer> rewards, int spent, GameReply output)
            throws IOException {
        int earnedDiamonds = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < rewards.size(); i += 3) {
            if (rewards.get(i) == 104) {
                earnedDiamonds += rewards.get(i + 2);
                continue;
            }
            int itemId = rewards.get(i + 1);
            items.add(data("id", itemId, "n", rewards.get(i + 2)));
        }
        if (spent > 0 || earnedDiamonds > 0) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("diamonds", saved.diamonds));
        }
        bags.push(role.id, output);
        Map<String, Object> adds = data("items", items);
        if (earnedDiamonds > 0) {
            adds.put("diamonds", earnedDiamonds);
        }
        output.push(14, data("flags", source, "adds", adds,
                "loses", spent > 0 ? data("diamonds", spent) : data()));
    }

    /**
     * 汇总已达成且未领取的运营活动红点。
     *
     * @param roleId 当前角色编号
     * @return 原运营活动编号列表
     */
    private List<Integer> redpoints(String roleId) {
        List<Integer> redpoints = new ArrayList<>(7);
        if (activities.chargeGiftAvailable(roleId)) {
            redpoints.add(OperatingActivityService.CHARGE_GIFT);
        }
        if (activities.rushJobAvailable(roleId)) {
            redpoints.add(OperatingActivityService.RUSH_JOB);
        }
        if (activities.attendanceAvailable(roleId)) {
            redpoints.add(ATTENDANCE);
        }
        for (int id : new int[]{WEEKLY_SPEND, DAILY_SPEND, LIFETIME_RECHARGE}) {
            if (recharge.claimable(roleId, id)) {
                redpoints.add(id);
            }
        }
        for (int id : new int[]{KYLIN, REBATE}) {
            if (scores.claimable(roleId, id)) {
                redpoints.add(id);
            }
        }
        if (firstChargeRush.claimable(roleId)) {
            redpoints.add(FirstChargeRushService.ACTIVITY);
        }
        return redpoints;
    }

    /**
     * 获取或创建当前连接的活动快照。
     *
     * @param output 当前连接通道
     * @return 当前连接的活动状态
     */
    private Session session(GameReply output) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, unused -> new Session());
        }
    }

    /**
     * 保存旧周期请求拦截游标及兑换去重键。
     *
     * @author tongruiyuan
     * @since 2026-11-19 10:15
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private long wishDay = -1;
        private long investmentStart = Long.MIN_VALUE;
        private long exchangeStart = Long.MIN_VALUE;
        private final Map<Integer, Long> promotionStarts = new HashMap<>();
        private ActivityPeriod attendancePeriod;
        private ActivityPeriod rushPeriod;
        private ActivityPeriod chargeGiftPeriod;
        private List<Integer> redpoints = Collections.emptyList();
    }
}
