package com.pokemon.local.activitydaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日在线奖励、签到和累签奖励 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:13
 */
@Service
public class DailyActivityService {

    private static final int ONLINE = 10001;
    private static final int SIGN = 10006;
    private static final int ONLINE_STAGES = 5;
    private static final int DAY_SECONDS = 86_400;
    private static final int HEARTBEAT_WINDOW_SECONDS = 90;
    private static final int SIGN_ITEM = 34013;
    public static final int ITEM = 1;
    public static final int DIAMONDS = 104;

    // 来源：底包 sign_proto。未列出的普通签到日均为 20 个道具。
    private static final int[] SIGN_AMOUNTS = {
        30, 20, 20, 20, 20, 20, 50, 20, 20, 20,
        20, 20, 20, 20, 80, 20, 20, 20, 20, 20,
        20, 20, 20, 20, 20, 20, 20, 20, 20, 100
    };
    // 来源：底包 checkin_proto，档位 1 到 5 对应签到 4/8/12/16/20 天。
    private static final int[] SIGN_TOTAL_ITEMS = {34135, 34135, 34136, 34136, 34137};
    private static final int[] SIGN_TOTAL_AMOUNTS = {2, 3, 1, 1, 1};

    private final ActivityService activities;
    private final DailyActivityMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;
    private final GamePaymentService payments;
    private final List<Reward> onlineRewards;

    /**
     * 加载底包 online_proto 转存的五个真实档位。
     *
     * @param activities 统一活动周期日历
     * @param mapper 在线与签到记录 Mapper
     * @param roles 本地角色写入锁
     * @param inventory 背包与资产服务
     * @param payments 本地 VIP 等级
     * @param json JSON 配置解析器
     * @throws IOException 奖励文件不可读或不完整
     */
    DailyActivityService(ActivityService activities, DailyActivityMapper mapper, RoleService roles,
                         InventoryService inventory, GamePaymentService payments, ObjectMapper json) throws IOException {
        this.payments = payments;
        this.activities = activities;
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
        List<Reward> rewards = new ArrayList<>(ONLINE_STAGES);
        try (InputStream content = new ClassPathResource("inventory/online.json").getInputStream()) {
            JsonNode rows = json.readTree(content);
            int precedingTime = 0;
            for (int stage = 1; stage <= ONLINE_STAGES; stage++) {
                JsonNode row = rows.path(Integer.toString(stage));
                int time = row.path("seconds").asInt(-1);
                int type = row.path("awardType").asInt(-1);
                int id = row.path("itemId").asInt(-1);
                int amount = row.path("quantity").asInt(-1);
                if (time <= precedingTime || amount < 1 || type == ITEM && id <= 0
                        || type == DIAMONDS && id != 0 || type != ITEM && type != DIAMONDS) {
                    throw new IOException("在线奖励缺少有效的档位：" + stage);
                }
                rewards.add(new Reward(type, id, amount, time));
                precedingTime = time;
            }
        }
        this.onlineRewards = Collections.unmodifiableList(rewards);
    }

    /**
     * 只累计同一连接连续有效的前后两个心跳之间的时间。
     *
     * @param roleId 角色编号
     * @param from 上一次服务端观察时刻，Unix 秒
     * @param to 本次服务端观察时刻，Unix 秒
     * @return 写入时长后的活动状态
     */
    @Transactional
    public OnlineState observe(String roleId, long from, long to) {
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(ONLINE);
        long start = Math.max(period.start, from);
        if (from > 0 && to > start && to < period.end && to - from <= HEARTBEAT_WINDOW_SECONDS) {
            mapper.creditTime(roleId, period.start, start, to);
        }
        return onlineState(roleId, period);
    }

    /**
     * 查询当天在线奖励剩余时间和下一档奖励预览。
     *
     * @param roleId 角色编号
     * @return 已提交的在线活动状态
     */
    @Transactional(readOnly = true)
    public OnlineState online(String roleId) {
        return onlineState(roleId, activities.cycle(ONLINE));
    }

    /**
     * 读取含初始体力和历次购买、兑换的余额。
     * @param role 角色编号
     * @return 实际体力值
     */
    public long staminaBalance(String role) {
        return mapper.stamina(role);
    }

    /**
     * 查询当日已购的体力份数。
     * @param role 角色编号
     * @param day 北京时间当日零点
     * @return 当日已购份数
     */
    public int staminaPurchases(String role, long day) {
        return mapper.staminaCount(role, day, 100);
    }

    /**
     * 原子结算购买体力或兑换当日普通副本次数。
     * @param role 当前角色
     * @param receipt 本地连接与原协议请求编号
     * @param buy 是否购买，否代表消耗额外的 100 体力
     * @param now 处理请求时的 Unix 秒
     * @return 原协议错误码
     */
    @Transactional
    public int staminaAction(String role, String receipt, boolean buy, long now) {
        roles.lockRole(role);
        int delta = buy ? 100 : -100;
        List<Integer> replay = mapper.staminaReceipt(role, receipt);
        if (!replay.isEmpty()) return replay.get(0) == delta ? 0 : 100021;
        long day = Math.floorDiv(now + 28800L, 86400L) * 86400L - 28800L;
        if (buy) {
            int bought = mapper.staminaCount(role, day, 100);
            if (bought >= 3) return 100021;
            if (!inventory.spendMaterials(role, Collections.emptyMap(), 0, 50L * (bought + 1))) return 100011;
        } else if (mapper.stamina(role) < 200) {
            return 100021;
        }
        mapper.recordStamina(role, receipt, day, delta);
        return 0;
    }

    /**
     * 依据已核实时长领取下一档奖励。
     *
     * @param roleId 角色编号
     * @param expectedCycle 客户端最近同步的周期起点
     * @param receipt 当前连接和原协议请求流水
     * @return 新发放的奖励，提前领取或已处理时为空
     */
    @Transactional
    public Reward claimOnline(String roleId, long expectedCycle, String receipt) {
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(ONLINE);
        if (period.start != expectedCycle || !isOpen(period) || mapper.hasReceipt(roleId, period.start, receipt)) {
            return null;
        }
        int stage = mapper.onlineClaims(roleId, period.start);
        if (stage >= onlineRewards.size()) {
            return null;
        }
        Reward reward = onlineRewards.get(stage);
        if (mapper.onlineSeconds(roleId, period.start) < reward.seconds) {
            return null;
        }
        grant(roleId, reward);
        mapper.claimOnline(roleId, period.start, stage + 1, receipt);
        return reward;
    }

    /**
     * 读取原客户端签到状态。
     *
     * @param roleId 角色编号
     * @return 当前周期和累签档位快照
     */
    @Transactional(readOnly = true)
    public AttendanceState attendance(String roleId) {
        ActivityPeriod period = activities.cycle(SIGN);
        List<Integer> days = mapper.signDays(roleId, period.start);
        int availableDays = roles.findById(roleId)
                .map(role -> Math.max(0, period.day - firstEligibleDay(period, role.createdAt) + 1)).orElse(0);
        return new AttendanceState(period.start, period.day, Math.min(period.end, period.start + period.day * DAY_SECONDS),
                availableDays, days.size(), mapper.patchCount(roleId, period.start), days.contains(period.day),
                mapper.signClaims(roleId, period.start), mapper.vipClaims(roleId, period.start));
    }

    /**
     * 登记今日签到或免费补齐当期最早的合规漏签日。
     *
     * @param roleId 角色编号
     * @param expectedCycle App 本地连接最近显示的周期
     * @param expectedDay App 本地连接最近显示的自然日序
     * @param patchReceipt 补签请求唯一标识，当天签到传空
     * @return 新发放的签到奖励，无合规日期或请求已处理时为空
     */
    @Transactional
    public Reward sign(String roleId, long expectedCycle, int expectedDay, String patchReceipt) {
        LocalRole role = roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(SIGN);
        if (period.start != expectedCycle || period.day != expectedDay || !isOpen(period)) {
            return null;
        }
        List<Integer> days = mapper.signDays(roleId, period.start);
        int earliest = firstEligibleDay(period, role.createdAt);
        if (days.size() >= SIGN_AMOUNTS.length || period.day > SIGN_AMOUNTS.length || earliest > period.day) {
            return null;
        }
        int day = period.day;
        if (patchReceipt != null) {
            if (mapper.hasPatchReceipt(roleId, patchReceipt)) {
                return null;
            }
            day = earliest;
            while (day < period.day && days.contains(day)) {
                day++;
            }
            if (day >= period.day) {
                return null;
            }
        } else if (days.contains(day)) {
            return null;
        }
        Reward reward = grantAttendanceItem(roleId, "attendance:" + period.start + ":day:" + day,
                SIGN_ITEM, SIGN_AMOUNTS[days.size()]);
        mapper.sign(roleId, period.start, day, patchReceipt);
        return reward;
    }

    /**
     * 为 VIP1 及以上角色追加一份已领取的当序签到奖励。
     * @param roleId 角色编号
     * @param cycle 当前界面显示的周期
     * @param index 普通签到的奖励序号
     * @return 实际发放的奖励，资格不足时为空
     */
    @Transactional
    public Reward signVip(String roleId, long cycle, long index) {
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(SIGN);
        if (period.start != cycle || !isOpen(period) || index < 1 || index > SIGN_AMOUNTS.length
                || index > mapper.signDays(roleId, cycle).size() || payments.vip(roleId).level < 1
                || mapper.vipClaims(roleId, cycle).contains((int) index)) return null;
        Reward reward = grantAttendanceItem(roleId, "attendance:vip:" + cycle + ":" + index, SIGN_ITEM, SIGN_AMOUNTS[(int)index - 1]);
        mapper.recordVip(roleId, cycle, (int) index);
        return reward;
    }

    /**
     * 以角色创建时间确定本轮可补签自然日的起点。
     *
     * @param period 签到活动周期
     * @param createdAt 角色创建时间，Unix 秒
     * @return 首个合规自然日的周期内日序
     */
    private int firstEligibleDay(ActivityPeriod period, long createdAt) {
        if (createdAt >= period.end) {
            return SIGN_AMOUNTS.length + 1;
        }
        return 1 + (int) (Math.max(0, createdAt - period.start) / DAY_SECONDS);
    }

    /**
     * 领取已经达成的原版累签奖励。
     *
     * @param roleId 角色编号
     * @param expectedCycle 客户端最近同步的周期
     * @param index 累签档位
     * @return 新发放的奖励，未达成、已领取或无效档位时为空
     */
    @Transactional
    public Reward claimAttendance(String roleId, long expectedCycle, long index) {
        if (index < 1 || index > SIGN_TOTAL_ITEMS.length) {
            return null;
        }
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(SIGN);
        if (period.start != expectedCycle || !isOpen(period)
                || mapper.signDays(roleId, period.start).size() < index * 4
                || mapper.signClaims(roleId, period.start).contains((int) index)) {
            return null;
        }
        int offset = (int) index - 1;
        Reward reward = grantAttendanceItem(roleId, "attendance:" + period.start + ":tier:" + index,
                SIGN_TOTAL_ITEMS[offset], SIGN_TOTAL_AMOUNTS[offset]);
        mapper.claimSign(roleId, period.start, (int) index);
        return reward;
    }

    /**
     * 发放签到道具，并将背包无法容纳的数量保存在系统邮件中。
     *
     * @param roleId 角色编号
     * @param receipt 同一周期内日期或累签档位的唯一回执
     * @param id 道具配置编号
     * @param amount 应发放的数量
     * @return 实际入包数量以及是否生成了溢出邮件
     */
    private Reward grantAttendanceItem(String roleId, String receipt, int id, int amount) {
        int delivered = inventory.grantItemsOrMail(roleId, receipt, Collections.singletonMap(id, (long) amount))
                .getOrDefault(id, 0L).intValue();
        return new Reward(ITEM, id, delivered, 0, delivered < amount);
    }

    /**
     * 返回当前下一档以及基于已确认在线时间得出的倒计时。
     *
     * @param roleId 角色编号
     * @param period 在线活动周期
     * @return 下一档倒计时与奖励，完成时倒计时为负一
     */
    private OnlineState onlineState(String roleId, ActivityPeriod period) {
        int stage = mapper.onlineClaims(roleId, period.start);
        if (!isOpen(period) || stage >= onlineRewards.size()) {
            return new OnlineState(period.start, -1, null);
        }
        Reward reward = onlineRewards.get(stage);
        int seconds = mapper.onlineSeconds(roleId, period.start);
        return new OnlineState(period.start, Math.max(0, reward.seconds - seconds), reward);
    }

    /**
     * 向本地资产服务提交当前奖励。
     *
     * @param roleId 角色编号
     * @param reward 活动奖励
     */
    private void grant(String roleId, Reward reward) {
        inventory.grant(roleId, reward.type == DIAMONDS ? 1002 : reward.id, reward.amount);
    }

    /**
     * 检查时间锚点是否已开放。
     *
     * @param period 当前活动窗口
     * @return 服务器时间是否在此窗口内
     */
    private boolean isOpen(ActivityPeriod period) {
        long now = Instant.now().getEpochSecond();
        return now >= period.start && now < period.end;
    }

    /**
     * 原版奖励三元组与在线档位要求。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:13
     */
    public static final class Reward {
        public final int type;
        public final int id;
        public final int amount;
        public final int seconds;
        public final boolean mailed;

        /**
         * 创建一档活动奖励。
         *
         * @param type 原协议奖励类型
         * @param id 道具编号，数值资产为零
         * @param amount 奖励数量
         * @param seconds 在线档位累计秒数
         */
        Reward(int type, int id, int amount, int seconds) {
            this(type, id, amount, seconds, false);
        }

        /**
         * 创建一档活动奖励并记录是否已生成溢出邮件。
         *
         * @param type 原协议奖励类型
         * @param id 道具编号，数值资产为零
         * @param amount 已直接发放的数量
         * @param seconds 在线档位累计秒数
         * @param mailed 是否有余量转入系统邮件
         */
        Reward(int type, int id, int amount, int seconds, boolean mailed) {
            this.type = type;
            this.id = id;
            this.amount = amount;
            this.seconds = seconds;
            this.mailed = mailed;
        }

        /**
         * 转换为原客户端要求的整数数组。
         *
         * @return 依次为类型、编号和数量
         */
        public List<Integer> values() {
            return Arrays.asList(type, id, amount);
        }
    }

    /**
     * 在线奖励下一档的显示快照。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:13
     */
    public static final class OnlineState {
        public final long cycle;
        public final int remaining;
        public final Reward next;

        /**
         * 创建在线快照。
         *
         * @param cycle 当前活动周期起点
         * @param remaining 距离下一档的剩余秒数
         * @param next 下一档奖励
         */
        OnlineState(long cycle, int remaining, Reward next) {
            this.cycle = cycle;
            this.remaining = remaining;
            this.next = next;
        }
    }

    /**
     * 每日签到显示快照。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:13
     */
    public static final class AttendanceState {
        public final long cycle;
        public final int day;
        public final long nextDay;
        public final int availableDays;
        public final int count;
        public final int patched;
        public final boolean today;
        public final List<Integer> claims;
        /** 本轮已领 VIP 追加日奖的序号。 */
        public final List<Integer> vipClaims;

        /**
         * 创建每日签到快照。
         *
         * @param cycle 周期起点
         * @param day 当前周期日序
         * @param nextDay 下一自然日的起点
         * @param availableDays 建角后本期已开放的签到日数
         * @param count 已签到自然日数量
         * @param patched 本期已补签自然日数量
         * @param today 本日是否已签到
         * @param vipClaims 已领取的 VIP 签到序号
         * @param claims 已领取的累签档位
         */
        AttendanceState(long cycle, int day, long nextDay, int availableDays, int count, int patched,
                        boolean today, List<Integer> claims, List<Integer> vipClaims) {
            this.vipClaims = vipClaims;
            this.cycle = cycle;
            this.day = day;
            this.nextDay = nextDay;
            this.availableDays = availableDays;
            this.count = count;
            this.patched = patched;
            this.today = today;
            this.claims = claims;
        }
    }
}
