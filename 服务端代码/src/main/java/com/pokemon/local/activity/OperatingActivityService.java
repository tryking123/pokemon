package com.pokemon.local.activity;

import com.pokemon.local.activity.OperatingActivityRules.AttendanceDay;
import com.pokemon.local.activity.OperatingActivityRules.Offer;
import com.pokemon.local.activity.OperatingActivityRules.Rules;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.battle.BattleService;
import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.pokemon.local.activity.OperatingActivityRules.ATTENDANCE;
import static com.pokemon.local.activity.OperatingActivityRules.EXCHANGE;

/**
 * 神秘兑换、节日签到的周期状态与资产结算 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 10:14
 */
@Service
@DependsOnDatabaseInitialization
public class OperatingActivityService {
    /** 限时投资活动编号。 */
    public static final int INVESTMENT = 10049;
    /** 原限时任务活动编号。 */
    public static final int RUSH_JOB = 10035;
    /** 每日单笔对应充值福利活动。 */
    public static final int CHARGE_GIFT = 10038;
    private static final int INVESTMENT_COST = 1000;
    private static final int LAST_BUY_DAY = 5;
    private static final int[] INVESTMENT_ITEMS = {21063, 20027, 21068};
    private static final int NOT_OPEN = 100013;
    private static final int ALREADY_RECEIVED = 100011;
    private static final int NOT_QUALIFIED = 100012;
    private static final int NO_SPACE = 100015;
    private static final int INVALID_INDEX = 100021;
    private static final int LOGIN = 1;
    private static final int RECHARGE = 2;
    private static final int SPEND = 4;
    private static final int TASK_MASK = LOGIN | RECHARGE | SPEND;
    private static final long DAY = 86_400;

    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final DiamondSpendMapper spending;
    private final JdbcTemplate jdbc;
    private final BattleService battles;
    private final List<Offer> offers;
    private final Rules attendance;
    private final List<List<Integer>> boxes;

    /**
     * 加载运营规则，关联活动日历、角色锁与共享资产记录。
     *
     * @param calendar 本地活动周期
     * @param mapper 活动参与记录
     * @param inventory 道具与钻石校验及结算
     * @param roles 提交前锁定当前角色
     * @param spending 角色真实钻石消费流水
     * @param jdbc 活动表初始化
     * @param battles 普通副本已完成记录
     * @param rules 统一的运营数值
     */
    OperatingActivityService(ActivityService calendar, ActivityMapper mapper, InventoryService inventory,
                             RoleService roles, DiamondSpendMapper spending, JdbcTemplate jdbc,
                             BattleService battles, OperatingActivityRules rules) {
        this.calendar = calendar;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.spending = spending;
        this.jdbc = jdbc;
        this.battles = battles;
        this.offers = rules.activity(EXCHANGE).offers;
        this.attendance = rules.activity(ATTENDANCE);
        this.boxes = attendance.attendance.stream().map(day -> day.award)
                .filter(award -> !award.isEmpty()).collect(Collectors.toList());
        if (offers.isEmpty() || attendance.attendance.size() != attendance.days || boxes.size() != 5) {
            throw new IllegalArgumentException("运营活动配置缺少货架、签到日或累计宝箱");
        }
        for (Offer offer : offers) {
            if (offer.redeem.size() != 3 || offer.redeem.get(0) != 104 || offer.redeem.get(1) != 0
                    || offer.redeem.get(2) <= 0 || offer.num <= 0) {
                throw new IllegalArgumentException("神秘兑换价格或数量错误");
            }
            validateItem(offer.exchange);
        }
        for (AttendanceDay day : attendance.attendance) {
            if (day.chongzhi.size() != 5 || day.chongzhi.get(0) != 2 || day.chongzhi.get(1) <= 0
                    || day.xiaohao.size() != 4 || day.xiaohao.get(0) <= 0) {
                throw new IllegalArgumentException("节日签到门槛格式错误");
            }
            validateItem(day.login);
            validateItem(day.chongzhi.subList(2, 5));
            validateItem(day.xiaohao.subList(1, 4));
        }
        int previous = 0;
        for (List<Integer> box : boxes) {
            if (box.size() != 4 || box.get(0) <= previous || box.get(0) > attendance.days * 3) {
                throw new IllegalArgumentException("节日签到累计宝箱门槛错误");
            }
            previous = box.get(0);
            validateItem(box.subList(1, 4));
        }
    }

    /**
     * 核对奖励具有可落库的真实道具模板及正整数数量。
     *
     * @param award 原协议奖励三元组
     */
    private void validateItem(List<Integer> award) {
        if (award.size() != 3 || award.get(0) != 1 || award.get(1) <= 1002 || award.get(2) <= 0) {
            throw new IllegalArgumentException("运营活动奖励必须是带数量的普通道具");
        }
        inventory.itemType(award.get(1));
    }

    /** 初始化活动参与记录表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("activity/operating-exchange-recharge.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 查询本轮投资的购买资格、领取进度及奖励。
     *
     * @param roleId 当前角色编号
     * @param period 当前投资周期
     * @return 投资面板字段
     */
    public Map<String, Object> investmentStatus(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int day = investmentDay(actions);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < INVESTMENT_ITEMS.length; i++) {
            rows.add(fields("idx", i, "rew", java.util.Arrays.asList(1, INVESTMENT_ITEMS[i], 1),
                    "get", actions.containsKey(i) ? 1 : 0));
        }
        return fields("errno", 0, "info", rows, "progress", 0,
                "flag", day == 0 ? 0 : 1, "condition", java.util.Arrays.asList(1, 0),
                "cost", INVESTMENT_COST, "length", LAST_BUY_DAY);
    }

    /**
     * 从本轮负数购买凭据读取购买日序。
     *
     * @param actions 本轮已完成动作
     * @return 购买日序，零表示未购买
     */
    private int investmentDay(Map<Integer, Integer> actions) {
        for (int i = 1; i <= LAST_BUY_DAY; i++) {
            if (actions.containsKey(-i)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 在本轮前五天投入钻石并冻结购买日。
     *
     * @param roleId 当前角色编号
     * @param start 玩家查看面板时的周期起点
     * @return 实际扣款结果
     */
    @Transactional
    public Result buyInvestment(String roleId, long start) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(INVESTMENT, now);
        if (period.start != start || now < period.start || period.day > LAST_BUY_DAY) {
            return Result.error(NOT_OPEN);
        }
        if (investmentDay(mapper.operatingActions(roleId, period)) != 0) {
            return Result.error(ALREADY_RECEIVED);
        }
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, INVESTMENT_COST)) {
            return Result.error(NOT_QUALIFIED);
        }
        mapper.recordOperatingAction(roleId, period, -period.day, "buy");
        return new Result(0, 0, Collections.emptyList(), INVESTMENT_COST);
    }

    /**
     * 按固定返利期核算已到期投资分档，背包溢出时将道具保存到系统信。
     *
     * @param roleId 当前角色编号
     * @param start 面板展示的周期起点
     * @param index 原面板编号，零为顶部奖励，一和二为返利日序
     * @return 档位结算结果及直接入包的奖励
     */
    @Transactional
    public Result claimInvestment(String roleId, long start, long index) {
        if (index < 0 || index >= INVESTMENT_ITEMS.length) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(INVESTMENT, now);
        if (period.start != start || now < period.start) {
            return Result.error(NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int day = investmentDay(actions);
        if (day == 0 || period.day - LAST_BUY_DAY < Math.max(1L, index)) {
            return Result.error(NOT_QUALIFIED);
        }
        if (actions.containsKey((int) index)) {
            return Result.error(ALREADY_RECEIVED);
        }
        int item = INVESTMENT_ITEMS[(int) index];
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "invest:" + start + ":" + index,
                Collections.singletonMap(item, 1L));
        mapper.recordOperatingAction(roleId, period, (int) index, "claim");
        return new Result(0, (int) index, delivered.isEmpty() ? Collections.emptyList()
                : java.util.Arrays.asList(1, item, 1), 0);
    }

    /**
     * 返回可在本地完成的原限时任务，保持行序以供原界面回传领取索引。
     *
     * @param roleId 当前角色
     * @param period 当前活动周期
     * @return 原任务面板的应答字段
     */
    public Map<String, Object> rushJobs(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RushJob task : RushJob.values()) {
            int progress = rushProgress(roleId, period, task);
            int state = actions.containsKey(period.day * 1000 + task.code) ? 3 : progress >= task.goal ? 2 : 1;
            rows.add(fields("task1", task.code, "times", task.goal, "progress", progress, "state", state,
                    "award", task.award, "reset", 1, "wenben", task.word));
        }
        return fields("errno", 0, "info", rows);
    }

    /**
     * 检查是否有当天可领的限时任务。
     *
     * @param roleId 当前角色
     * @return 有真实进度达标且未领取的行时为真
     */
    public boolean rushJobAvailable(String roleId) {
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(RUSH_JOB, now);
        if (period.start > now) {
            return false;
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        for (RushJob task : RushJob.values()) {
            if (!actions.containsKey(period.day * 1000 + task.code) && rushProgress(roleId, period, task) >= task.goal) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按北京时间自然日汇总登录、扣钻、道馆和副本的已结算事件。
     *
     * @param roleId 当前角色
     * @param period 当前周期
     * @param task 对应的原表任务
     * @return 当日已完成的目标量
     */
    private int rushProgress(String roleId, ActivityPeriod period, RushJob task) {
        long start = period.start + (period.day - 1L) * DAY;
        switch (task) {
            case LOGIN:
                return jdbc.queryForObject("SELECT COUNT(*) FROM local_activity_login_day WHERE role_id=? "
                        + "AND period_start+(day_number-1)*86400=?", Integer.class, roleId, start) > 0 ? 1 : 0;
            case SPEND:
                return (int) Math.min(task.goal, spending.totalBetween(roleId, start * 1000, (start + DAY) * 1000));
            case DAILY_DUNGEON:
                return Math.min(task.goal, jdbc.queryForObject("SELECT COUNT(*) FROM local_dailydup_completion "
                        + "WHERE role_id=? AND completed_at>=? AND completed_at<?", Integer.class, roleId, start, start + DAY));
            case SOLO_DUNGEON:
                return Math.min(task.goal, battles.completedDungeonRuns(roleId, start));
            case DOJO:
                long localDay = Math.floorDiv(start + 8 * 3600, DAY);
                return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM local_dojo_challenge_day "
                        + "WHERE role_id=? AND challenge_day=?) + (SELECT COUNT(*) FROM local_dojo_sweep "
                        + "WHERE role_id=? AND sweep_day=?)", Integer.class, roleId, localDay, roleId, localDay) > 0 ? 1 : 0;
            default:
                throw new IllegalArgumentException("限时任务缺少已接入的事件来源");
        }
    }

    /**
     * 按已展示的行序领取达标任务，每个自然日每项只提交一次。
     *
     * @param roleId 当前角色
     * @param index 原面板从一开始的行序
     * @param expected 打开面板时的活动周期
     * @return 原行序、发奖结果和直接入包的道具
     */
    @Transactional
    public Result claimRushJob(String roleId, long index, ActivityPeriod expected) {
        if (expected == null) {
            return Result.error(NOT_OPEN);
        }
        if (index < 1 || index > RushJob.values().length) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(RUSH_JOB, now);
        if (period.start > now || expected.start != period.start || expected.day != period.day) {
            return Result.error(NOT_OPEN);
        }
        RushJob task = RushJob.values()[(int) index - 1];
        int action = period.day * 1000 + task.code;
        if (mapper.operatingActions(roleId, period).containsKey(action)) {
            return Result.error(ALREADY_RECEIVED);
        }
        if (rushProgress(roleId, period, task) < task.goal) {
            return Result.error(NOT_QUALIFIED);
        }
        int item = task.award.get(1);
        long amount = task.award.get(2);
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "rush:" + period.start + ":" + action,
                Collections.singletonMap(item, amount));
        mapper.recordOperatingAction(roleId, period, action, "claim");
        long received = delivered.getOrDefault(item, 0L);
        return new Result(0, (int) index, received == 0 ? Collections.emptyList()
                : java.util.Arrays.asList(1, item, (int) received), 0);
    }

    /**
     * 原限时任务表中当前可由本地持久事件核验的行。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:01
     */
    private enum RushJob {
        LOGIN(109, 1095, 1, 22019, 10),
        SPEND(110, 1096, 500, 21023, 10),
        DOJO(106, 1092, 1, 20003, 10),
        DAILY_DUNGEON(101, 1087, 3, 31022, 1),
        SOLO_DUNGEON(102, 1088, 3, 34003, 1);

        private final int code;
        private final int word;
        private final int goal;
        private final List<Integer> award;

        /**
         * 保存原表任务的目标及奖励。
         *
         * @param code 原任务编号
         * @param word 原说明文字编号
         * @param goal 达标数量
         * @param item 道具编号
         * @param quantity 奖励数量
         */
        RushJob(int code, int word, int goal, int item, int quantity) {
            this.code = code;
            this.word = word;
            this.goal = goal;
            this.award = java.util.Arrays.asList(1, item, quantity);
        }
    }

    /**
     * 读取原超值礼包面板、当天对应的单笔充值及已领状态。
     *
     * @param roleId 当前角色
     * @param period 当前活动日期
     * @return 原活动充值按钮和领取按钮的数据
     */
    public Map<String, Object> chargeGiftStatus(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", NOT_OPEN);
        }
        List<Integer> paid = chargeGiftOrders(roleId, period);
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChargeGift tier : ChargeGift.values()) {
            int flag = actions.containsKey(tier.action(period.day)) ? 2 : paid.contains(tier.sku) ? 1 : 0;
            rows.add(fields("num", tier.diamonds, "chongfu", 0, "chongzhi", 1, "rew", tier.rewards, "flag", flag));
        }
        return fields("errno", 0, "show", 54001, "info", rows);
    }

    /**
     * 仅读取当前自然日已确认的普通钻石订单，每个商品独立命中自己的档位。
     *
     * @param roleId 当前角色
     * @param period 当前活动日期
     * @return 当日实际购过的普通钻石商品编号
     */
    private List<Integer> chargeGiftOrders(String roleId, ActivityPeriod period) {
        long start = (period.start + (period.day - 1L) * DAY) * 1000;
        return jdbc.query("SELECT DISTINCT paysid FROM local_game_payment_order WHERE role_id=? "
                        + "AND paysid IN (5,6) AND confirmed_at>=? AND confirmed_at<?",
                (row, index) -> row.getInt(1), roleId, start, start + DAY * 1000);
    }

    /**
     * 核实今天是否有已充值且可领的对应礼包。
     *
     * @param roleId 当前角色
     * @return 至少一档可领时为真
     */
    public boolean chargeGiftAvailable(String roleId) {
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(CHARGE_GIFT, now);
        if (period.start > now) {
            return false;
        }
        List<Integer> paid = chargeGiftOrders(roleId, period);
        if (paid.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        for (ChargeGift tier : ChargeGift.values()) {
            if (paid.contains(tier.sku) && !actions.containsKey(tier.action(period.day))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发放当日的一份充值达标礼包，背包放不下的部分转入系统邮件。
     *
     * @param roleId 当前角色
     * @param index 面板中按原配置顺序设置的序号
     * @param viewed 打开礼包时的活动日期
     * @return 实际发放的道具和钻石
     */
    @Transactional
    public Result claimChargeGift(String roleId, long index, ActivityPeriod viewed) {
        if (viewed == null) {
            return Result.error(NOT_OPEN);
        }
        if (index < 1 || index > ChargeGift.values().length) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(CHARGE_GIFT, now);
        if (period.start > now || viewed.start != period.start || viewed.day != period.day) {
            return Result.error(NOT_OPEN);
        }
        ChargeGift tier = ChargeGift.values()[(int) index - 1];
        int action = tier.action(period.day);
        if (mapper.operatingActions(roleId, period).containsKey(action)) {
            return Result.error(ALREADY_RECEIVED);
        }
        if (!chargeGiftOrders(roleId, period).contains(tier.sku)) {
            return Result.error(NOT_QUALIFIED);
        }
        Map<Integer, Long> items = new LinkedHashMap<>();
        List<Integer> received = new ArrayList<>();
        for (int i = 0; i < tier.rewards.size(); i += 3) {
            int type = tier.rewards.get(i);
            int item = tier.rewards.get(i + 1);
            int amount = tier.rewards.get(i + 2);
            if (type == 104) {
                roles.addCurrency(roleId, 0, amount, 0);
                received.addAll(tier.rewards.subList(i, i + 3));
            } else {
                items.put(item, (long) amount);
            }
        }
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "chargegift:" + period.start + ":" + action, items);
        delivered.forEach((item, quantity) -> received.addAll(java.util.Arrays.asList(1, item, quantity.intValue())));
        mapper.recordOperatingAction(roleId, period, action, "claim");
        return new Result(0, (int) index, received, 0);
    }

    /**
     * 原超值礼包两档及对应的当前渠道充值商品。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:13
     */
    private enum ChargeGift {
        SMALL(5, 6000, 1, 21015, 1, 1, 21038, 1, 1, 34031, 1, 104, 0, 60),
        LARGE(6, 30000, 1, 21015, 1, 1, 21038, 2, 1, 34031, 5, 1, 34010, 1);

        private final int sku;
        private final int diamonds;
        private final List<Integer> rewards;

        /**
         * 保存当前渠道真实充值档和原活动奖品三元组。
         *
         * @param sku 本地普通充值商品
         * @param diamonds 该档基础钻石
         * @param rewards 原表礼包内逐件奖励
         */
        ChargeGift(int sku, int diamonds, Integer... rewards) {
            this.sku = sku;
            this.diamonds = diamonds;
            this.rewards = java.util.Arrays.asList(rewards);
        }

        /**
         * 将活动日和固定商品编号映射到唯一领取行。
         *
         * @param day 从一开始的周期日
         * @return 当天此档领取标记
         */
        private int action(int day) {
            return day * 10 + sku;
        }
    }

    /**
     * 返回原神秘兑换货架及本轮各档已兑换次数。
     *
     * @param roleId 当前角色编号
     * @param period 当前展示的活动周期
     * @return 原请求应答字段
     */
    public Map<String, Object> exchangeStatus(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", NOT_OPEN);
        }
        Map<Integer, Integer> uses = mapper.operatingActions(roleId, period);
        List<Map<String, Object>> info = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            info.add(fields("redeem", offer.redeem, "exchange", offer.exchange, "num", offer.num,
                    "mytimes", uses.getOrDefault(i + 1, 0), "refresh", 0));
        }
        return fields("errno", 0, "info", info);
    }

    /**
     * 兑换一个有效档位，校验和发放在同一角色事务内提交。
     *
     * @param roleId 当前角色编号
     * @param index 一起始档位序号
     * @param expectedStart 客户端已查询的周期起点
     * @param requestKey 当前连接中的请求唯一键
     * @return 本次真实结算结果
     */
    @Transactional
    public Result exchange(String roleId, long index, long expectedStart, String requestKey) {
        if (index < 1 || index > offers.size()) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(EXCHANGE, now);
        if (period.start > now || period.start != expectedStart) {
            return Result.error(NOT_OPEN);
        }
        Offer offer = offers.get((int) index - 1);
        if (mapper.hasExchangeRequest(roleId, requestKey)
                || mapper.operatingActions(roleId, period).getOrDefault((int) index, 0) >= offer.num) {
            return Result.error(ALREADY_RECEIVED);
        }
        int item = offer.exchange.get(1);
        int amount = offer.exchange.get(2);
        int cost = offer.redeem.get(2);
        if (!inventory.canGrantItem(roleId, item, amount)) {
            return Result.error(NO_SPACE);
        }
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, cost)) {
            return Result.error(NOT_QUALIFIED);
        }
        inventory.grant(roleId, item, amount);
        mapper.recordOperatingAction(roleId, period, (int) index, requestKey);
        return new Result(0, (int) index, offer.exchange, cost);
    }

    /**
     * 构造每日签到栏、累计目标和已领奖的持久状态。
     *
     * @param roleId 当前角色编号
     * @param period 当前展示的活动周期
     * @return 节日签到的原版应答字段
     */
    public Map<String, Object> attendanceStatus(String roleId, ActivityPeriod period) {
        long now = System.currentTimeMillis();
        if (period.start * 1000 > now) {
            return fields("errno", NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int available = attendanceEligibility(roleId, period, now);
        List<Map<String, Object>> info = new ArrayList<>();
        for (int i = 0; i < attendance.attendance.size(); i++) {
            AttendanceDay day = attendance.attendance.get(i);
            Map<String, Object> row = fields("could", i + 1 == period.day ? available : 0,
                    "get", attendanceMask(actions, i + 1), "login", day.login,
                    "chongzhi", day.chongzhi, "xiaohao", day.xiaohao);
            if (!day.award.isEmpty()) {
                row.put("award", day.award);
            }
            info.add(row);
        }
        List<Integer> record = new ArrayList<>();
        for (int index = 1; index <= boxes.size(); index++) {
            if (actions.containsKey(-index)) {
                record.add(index);
            }
        }
        return fields("errno", 0, "day", period.day, "show", attendance.show,
                "info", info, "total", attendanceTotal(actions), "record", record);
    }

    /**
     * 按原协议独立领取当天登录、充值或消费签到奖励。
     *
     * @param roleId 当前角色编号
     * @param bit 原任务位编码
     * @param expectedStart 客户端已查询的周期起点
     * @param expectedDay 客户端已查询的周期日序
     * @return 本次真实发奖及今日已领取位图
     */
    @Transactional
    public Result claimAttendance(String roleId, long bit, long expectedStart, int expectedDay) {
        if (bit != LOGIN && bit != RECHARGE && bit != SPEND) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = System.currentTimeMillis();
        ActivityPeriod period = calendar.cycleAt(ATTENDANCE, now / 1000);
        if (period.start * 1000 > now || period.start != expectedStart || period.day != expectedDay) {
            return Result.error(NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int claimed = attendanceMask(actions, period.day);
        if ((claimed & bit) != 0) {
            return Result.error(ALREADY_RECEIVED);
        }
        if ((attendanceEligibility(roleId, period, now) & bit) == 0) {
            return Result.error(NOT_QUALIFIED);
        }
        AttendanceDay day = attendance.attendance.get(period.day - 1);
        List<Integer> reward = bit == LOGIN ? day.login : bit == RECHARGE
                ? day.chongzhi.subList(2, day.chongzhi.size()) : day.xiaohao.subList(1, day.xiaohao.size());
        return grantAttendance(roleId, period, (period.day * 8) + (int) bit, claimed | (int) bit, reward);
    }

    /**
     * 领取已经达到本轮签到次数门槛的累计宝箱。
     *
     * @param roleId 当前角色编号
     * @param index 一起始累计宝箱序号
     * @param expectedStart 客户端已查询的周期起点
     * @return 本次真实发奖及档位序号
     */
    @Transactional
    public Result claimBox(String roleId, long index, long expectedStart) {
        if (index < 1 || index > boxes.size()) {
            return Result.error(INVALID_INDEX);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ATTENDANCE, now);
        if (period.start > now || period.start != expectedStart) {
            return Result.error(NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        if (actions.containsKey(-(int) index)) {
            return Result.error(ALREADY_RECEIVED);
        }
        List<Integer> config = boxes.get((int) index - 1);
        if (attendanceTotal(actions) < config.get(0)) {
            return Result.error(NOT_QUALIFIED);
        }
        return grantAttendance(roleId, period, -(int) index, (int) index, config.subList(1, config.size()));
    }

    /**
     * 检查当前的节日签到栏或累计宝箱是否存在待领奖项。
     *
     * @param roleId 当前角色编号
     * @return 有资格领取且未领时为真
     */
    public boolean attendanceAvailable(String roleId) {
        long now = System.currentTimeMillis();
        ActivityPeriod period = calendar.cycleAt(ATTENDANCE, now / 1000);
        if (period.start * 1000 > now) {
            return false;
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int claimed = attendanceMask(actions, period.day);
        if (claimed != TASK_MASK && (attendanceEligibility(roleId, period, now) & ~claimed) != 0) {
            return true;
        }
        int total = attendanceTotal(actions);
        for (int index = 0; index < boxes.size(); index++) {
            if (total >= boxes.get(index).get(0) && !actions.containsKey(-index - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 以服务器日期及已提交流水计算当日三个任务的资格位。
     *
     * @param roleId 当前角色编号
     * @param period 当前签到周期
     * @param now 当前 Unix 毫秒
     * @return 当天达标的任务位图
     */
    private int attendanceEligibility(String roleId, ActivityPeriod period, long now) {
        long start = (period.start + (period.day - 1L) * DAY) * 1000;
        long end = Math.min(now + 1, start + DAY * 1000);
        AttendanceDay day = attendance.attendance.get(period.day - 1);
        int mask = LOGIN;
        if (mapper.rechargeSum(roleId, start, end) * 10L >= day.chongzhi.get(1)) {
            mask |= RECHARGE;
        }
        if (spending.totalBetween(roleId, start, end) >= day.xiaohao.get(0)) {
            mask |= SPEND;
        }
        return mask;
    }

    /**
     * 汇总指定自然日已成功领取的三个任务位。
     *
     * @param actions 本轮活动的已提交动作
     * @param day 周期日序，从一开始
     * @return 已领奖的任务位图
     */
    private int attendanceMask(Map<Integer, Integer> actions, int day) {
        int mask = 0;
        for (int bit = 1; bit <= SPEND; bit *= 2) {
            if (actions.containsKey(day * 8 + bit)) {
                mask |= bit;
            }
        }
        return mask;
    }

    /**
     * 计算已发放的日常签到栏奖励数。
     *
     * @param actions 本轮活动的已提交动作
     * @return 原 Unity 累计签到次数
     */
    private int attendanceTotal(Map<Integer, Integer> actions) {
        return (int) actions.keySet().stream().filter(index -> index > 0).count();
    }

    /**
     * 校验并且记录一个签到栏或累计宝箱的唯一资产发放。
     *
     * @param roleId 当前角色编号
     * @param period 当前运营周期
     * @param action 用于存档的动作编码
     * @param value 客户端期待返回的位图或宝箱序号
     * @param reward 实际奖励三元组
     * @return 本次资产结算结果
     */
    private Result grantAttendance(String roleId, ActivityPeriod period, int action, int value, List<Integer> reward) {
        int item = reward.get(1);
        int amount = reward.get(2);
        if (!inventory.canGrantItem(roleId, item, amount)) {
            return Result.error(NO_SPACE);
        }
        inventory.grant(roleId, item, amount);
        mapper.recordOperatingAction(roleId, period, action, "claim");
        return new Result(0, value, reward, 0);
    }

    /**
     * 生成保留原协议字段命名的快照。
     *
     * @param pairs 交替排列的字段名称和值
     * @return 原 Sproto 编码器使用的字段表
     */
    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            output.put((String) pairs[i], pairs[i + 1]);
        }
        return output;
    }

    /**
     * 运营动作的结算结果，可用来同步真实奖励和消耗。
     *
     * @author tongruiyuan
     * @since 2026-11-19 10:14
     */
    public static final class Result {
        public final int errno;
        public final int value;
        public final List<Integer> rewards;
        public final int spentDiamonds;

        /**
         * 保存动作结果及提交后的实际资产变更。
         *
         * @param errno 原协议错误码
         * @param value 原协议返回值
         * @param rewards 发放的道具三元组
         * @param spentDiamonds 本次扣除的钻石
         */
        private Result(int errno, int value, List<Integer> rewards, int spentDiamonds) {
            this.errno = errno;
            this.value = value;
            this.rewards = rewards;
            this.spentDiamonds = spentDiamonds;
        }

        /**
         * 构造资产没有发生变更的失败结果。
         *
         * @param errno 原协议错误码
         * @return 失败结果
         */
        private static Result error(int errno) {
            return new Result(errno, 0, Collections.emptyList(), 0);
        }
    }
}
