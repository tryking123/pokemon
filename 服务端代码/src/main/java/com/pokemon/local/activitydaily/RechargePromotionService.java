package com.pokemon.local.activitydaily;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.OperatingActivityRules;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.pokemon.local.activity.OperatingActivityRules.DAILY_SPEND;
import static com.pokemon.local.activity.OperatingActivityRules.LIFETIME_RECHARGE;
import static com.pokemon.local.activity.OperatingActivityRules.WEEKLY_SPEND;

/**
 * 累计充值、连续充值、单笔充值、每日累充和消费档位活动 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:17
 */
@Service
@DependsOnDatabaseInitialization
public class RechargePromotionService {
    public static final int TOTAL = 10012;
    public static final int STREAK = 10013;
    public static final int SINGLE = 10014;
    public static final int DAILY = 10031;
    private static final int ITEM = 1;
    private static final int ELF = 2;
    private static final int CHIP = 3;
    private static final int COINS = 103;
    private static final int DIAMONDS = 104;
    private static final long DAY = 86400;
    private final ActivityService calendar;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final DiamondSpendMapper spending;
    private final ElfService elfs;
    private final Map<Integer, List<Tier>> tiers;

    /**
     * 读取底包充值活动的门槛和奖励。
     *
     * @param calendar 活动周期
     * @param jdbc 本地订单和领取记录
     * @param roles 角色锁与货币发放
     * @param inventory 道具服务
     * @param elfs 精灵服务
     * @param json 配置读取器
     * @param localRules 本地累充和消费活动的门槛与奖励
     * @param spending 真实钻石扣款流水
     * @throws IOException 配置无法读取
     */
    RechargePromotionService(ActivityService calendar, JdbcTemplate jdbc, RoleService roles,
                             InventoryService inventory, ElfService elfs, ObjectMapper json,
                             OperatingActivityRules localRules, DiamondSpendMapper spending) throws IOException {
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.elfs = elfs;
        this.spending = spending;
        try (InputStream input = new ClassPathResource("activity/recharge-promotions.json").getInputStream()) {
            tiers = json.readValue(input, new TypeReference<Map<Integer, List<Tier>>>() { });
        }
        for (int id : new int[]{LIFETIME_RECHARGE, WEEKLY_SPEND, DAILY_SPEND}) {
            List<Tier> planned = localRules.activity(id).tiers;
            if (planned.isEmpty()) {
                throw new IllegalArgumentException("运营活动缺少奖档 " + id);
            }
            int previous = 0;
            for (Tier tier : planned) {
                if (tier.num <= previous || tier.rew.isEmpty() || tier.rew.size() % 3 != 0) {
                    throw new IllegalArgumentException("运营活动门槛或奖品格式错误 " + id);
                }
                for (int i = 0; i < tier.rew.size(); i += 3) {
                    if (tier.rew.get(i) != ITEM || tier.rew.get(i + 1) <= 1002 || tier.rew.get(i + 2) <= 0) {
                        throw new IllegalArgumentException("运营活动档位必须配置普通道具 " + id);
                    }
                    inventory.itemType(tier.rew.get(i + 1));
                }
                previous = tier.num;
            }
            tiers.put(id, planned);
        }
    }

    /**
     * 判断本轮是否有达标且未领取的本地累充或消费档位。
     *
     * @param roleId 当前角色编号
     * @param id 本地累充或消费活动编号
     * @return 至少一个档位可领取时为真
     */
    public boolean claimable(String roleId, int id) {
        ActivityPeriod period = window(id);
        if (period.start > Instant.now().getEpochSecond()) {
            return false;
        }
        Map<Integer, Integer> received = claimed(roleId, id, period);
        long total = totalsFor(roleId, period, new HashMap<>())[0];
        List<Tier> list = tiers.get(id);
        for (int index = 0; index < list.size(); index++) {
            if (total >= list.get(index).num && !received.containsKey(index + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建独立于已有每日充值领取记录的活动档位表。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("activity/recharge-promotions.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 返回当前窗口的充值进度和档位状态。
     *
     * @param roleId 角色编号
     * @param id 运营活动编号
     * @return 原协议的状态、档位及累计值
     */
    public Map<String, Object> status(String roleId, int id) {
        return status(roleId, id, window(id));
    }

    /**
     * 按明确展示给当前连接的活动窗口查询累计与领奖状态。
     *
     * @param roleId 当前角色编号
     * @param id 运营活动编号
     * @param window 该角色已打开的活动窗口
     * @return 原协议的状态、档位及累计值
     */
    public Map<String, Object> status(String roleId, int id, ActivityPeriod window) {
        Map<String, Object> response = new LinkedHashMap<>();
        boolean open = window.start <= Instant.now().getEpochSecond();
        response.put("errno", open ? 0 : 100013);
        if (!open) {
            return response;
        }
        Map<Integer, Integer> claimed = claimed(roleId, id, window);
        Map<Long, Long> days = new HashMap<>();
        long[] amounts = totalsFor(roleId, window, days);
        List<Map<String, Object>> info = new ArrayList<>();
        List<Tier> list = tiers.get(id);
        for (int index = 0; index < list.size(); index++) {
            Tier tier = list.get(index);
            boolean already = claimed.containsKey(index + 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("num", tier.num);
            row.put("rew", tier.rew);
            row.put("flag", already ? 1 : 0);
            if (id == SINGLE) {
                row.put("chongzhi", tier.chongzhi);
                row.put("chongfu", tier.chongfu);
                row.put("flag", already ? 2 : amounts[1] >= tier.num ? 1 : 0);
            } else if (id == STREAK) {
                row.put("zuanshi", tier.zuanshi);
            }
            info.add(row);
        }
        response.put("info", info);
        if (id != SINGLE) {
            response.put("total", id == STREAK ? streak(days, list.get(0).zuanshi) : amounts[0]);
        }
        if (id == TOTAL) {
            response.put("refresh", false);
        }
        return response;
    }

    /**
     * 在同一角色事务内发放所选档位的全部奖励。
     *
     * @param roleId 角色编号
     * @param id 运营活动编号
     * @param index 档位序号，从一开始
     * @return 本次领取状态与奖励三元组
     */
    @Transactional
    public Result claim(String roleId, int id, long index) {
        return claim(roleId, id, index, null);
    }

    /**
     * 领取已展示周期的活动档位。
     *
     * @param roleId 角色编号
     * @param id 运营活动编号
     * @param index 档位序号
     * @param expectedStart 已展示周期的 Unix 秒起点，允许默认查询当前周期
     * @return 本次领取状态与奖励三元组
     */
    @Transactional
    public Result claim(String roleId, int id, long index, Long expectedStart) {
        List<Tier> list = tiers.get(id);
        if (index < 1 || index > list.size()) {
            return new Result(100021, Collections.emptyList());
        }
        roles.lockRole(roleId);
        ActivityPeriod window = window(id);
        if (window.start > Instant.now().getEpochSecond() || expectedStart != null && window.start != expectedStart) {
            return new Result(100013, Collections.emptyList());
        }
        if (claimed(roleId, id, window).containsKey((int) index)) {
            return new Result(100011, Collections.emptyList());
        }
        Tier tier = list.get((int) index - 1);
        Map<Long, Long> days = new HashMap<>();
        long[] amounts = totalsFor(roleId, window, days);
        long score = id == STREAK ? streak(days, tier.zuanshi) : id == SINGLE ? amounts[1] : amounts[0];
        if (score < tier.num) {
            return new Result(100012, Collections.emptyList());
        }
        if (!canGrant(roleId, tier.rew)) {
            return new Result(100015, Collections.emptyList());
        }
        grant(roleId, tier.rew);
        jdbc.update("INSERT INTO local_recharge_promotion_claim(role_id,activity_id,period_start,tier) VALUES(?,?,?,?)",
                roleId, id, window.start, index);
        return new Result(0, tier.rew);
    }

    /**
     * 查询领取档位。
     *
     * @param roleId 角色编号
     * @param id 运营活动编号
     * @param window 活动统计窗口
     * @return 按原档位序号索引的已领取记录
     */
    private Map<Integer, Integer> claimed(String roleId, int id, ActivityPeriod window) {
        Map<Integer, Integer> result = new HashMap<>();
        jdbc.query("SELECT tier FROM local_recharge_promotion_claim WHERE role_id=? AND activity_id=? AND period_start=?",
                row -> { int tier = row.getInt(1); result.put(tier, tier); }, roleId, id, window.start);
        return result;
    }

    /**
     * 读取真实确认金额，保存累计、单笔最大及各自然日充值额。
     *
     * @param roleId 角色编号
     * @param window 活动统计区间
     * @param days 用于记录每个北京时间自然日的充值钻石值
     * @return 累计及单笔最大值
     */
    private long[] amounts(String roleId, ActivityPeriod window, Map<Long, Long> days) {
        long[] totals = {0L, 0L};
        jdbc.query("SELECT amount,confirmed_at FROM local_recharge_activity_order WHERE role_id=? "
                        + "AND confirmed_at>=? AND confirmed_at<? AND confirmed_at<=?",
                row -> {
                    long units = Math.multiplyExact(row.getLong(1), 10);
                    long day = (row.getLong(2) / 1000 - window.start) / DAY;
                    days.merge(day, units, Math::addExact);
                    totals[0] = Math.addExact(totals[0], units);
                    totals[1] = Math.max(totals[1], units);
                }, roleId, window.start * 1000, window.end * 1000, System.currentTimeMillis());
        return totals;
    }

    /**
     * 根据活动统计已确认订单或真实钻石支出。
     *
     * @param roleId 当前角色编号
     * @param window 统计区间
     * @param days 按自然日保存充值金额，以便连续充值活动继续计算
     * @return 累计值和单笔最大值
     */
    private long[] totalsFor(String roleId, ActivityPeriod window, Map<Long, Long> days) {
        if (window.id == WEEKLY_SPEND || window.id == DAILY_SPEND) {
            long end = Math.min(window.end * 1000, System.currentTimeMillis() + 1);
            return new long[]{spending.totalBetween(roleId, window.start * 1000, end), 0L};
        }
        return amounts(roleId, window, days);
    }

    /**
     * 计算本轮最长的连续达标自然日数。
     *
     * @param days 从本轮起点累计的日期编号与充值值
     * @param minimum 每天应达到的充值值
     * @return 已实现的最长连续天数
     */
    private long streak(Map<Long, Long> days, int minimum) {
        long previous = -2;
        long count = 0;
        long longest = 0;
        for (Map.Entry<Long, Long> day : new java.util.TreeMap<>(days).entrySet()) {
            if (day.getValue() >= minimum) {
                count = day.getKey() == previous + 1 ? count + 1 : 1;
                previous = day.getKey();
                longest = Math.max(longest, count);
            }
        }
        return longest;
    }

    /**
     * 每日累充和每日消费按北京时间自然日结算，其他活动按运营周期结算。
     *
     * @param id 运营活动编号
     * @return 当前统计窗口
     */
    public ActivityPeriod window(int id) {
        ActivityPeriod period = calendar.cycle(id);
        if (id != DAILY && id != DAILY_SPEND) {
            return period;
        }
        long start = period.start + (period.day - 1L) * DAY;
        return new ActivityPeriod(id, start, Math.min(start + DAY, period.end), 1);
    }

    /**
     * 检查整组奖励需要的额外格数及叠加上限。
     *
     * @param roleId 角色编号
     * @param rewards 原版奖励三元组
     * @return 全部奖励可以存入
     */
    private boolean canGrant(String roleId, List<Integer> rewards) {
        Map<Integer, Long> items = new HashMap<>();
        int elfCount = 0;
        for (int i = 0; i < rewards.size(); i += 3) {
            if (rewards.get(i) == ITEM) {
                items.merge(rewards.get(i + 1), (long) rewards.get(i + 2), Long::sum);
            } else if (rewards.get(i) == ELF) {
                elfCount += rewards.get(i + 2);
            }
        }
        if (elfCount > 0 && elfs.list(roleId).size() + elfCount > inventory.elfCapacity(roleId)) {
            return false;
        }
        int newSlots = 0;
        for (Map.Entry<Integer, Long> entry : items.entrySet()) {
            if (!inventory.canGrantItem(roleId, entry.getKey(), entry.getValue())) {
                return false;
            }
            if (inventory.count(roleId, entry.getKey()) == 0) {
                newSlots++;
            }
        }
        return newSlots == 0 || inventory.ordinaryUsed(roleId) + newSlots <= inventory.ordinaryCapacity(roleId);
    }

    /**
     * 将底包原始奖励写入实际资产。
     *
     * @param roleId 角色编号
     * @param rewards 按类别、编号、数量排列的奖励
     */
    private void grant(String roleId, List<Integer> rewards) {
        for (int i = 0; i < rewards.size(); i += 3) {
            int id = rewards.get(i + 1);
            int quantity = rewards.get(i + 2);
            switch (rewards.get(i)) {
                case ITEM:
                    inventory.grant(roleId, id, quantity);
                    break;
                case ELF:
                    for (int count = 0; count < quantity; count++) {
                        elfs.grant(roleId, id);
                    }
                    break;
                case CHIP:
                    if (jdbc.update("UPDATE local_elf_chip SET quantity=quantity+? WHERE role_id=? AND config_id=?",
                            quantity, roleId, id) == 0) {
                        jdbc.update("INSERT INTO local_elf_chip(role_id,config_id,quantity) VALUES(?,?,?)",
                                roleId, id, quantity);
                    }
                    break;
                case COINS:
                    roles.addCurrency(roleId, quantity, 0, 0);
                    break;
                case DIAMONDS:
                    roles.addCurrency(roleId, 0, quantity, 0);
                    break;
                default:
                    throw new IllegalArgumentException("未配置的充值活动奖励类别 " + rewards.get(i));
            }
        }
    }

    /**
     * 原客户端的充值活动门槛及奖励配置。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:17
     */
    public static class Tier {
        /** 累计钻石、连续天数或单笔钻石门槛。 */
        public int num;
        /** 连续充值每天的最低钻石值。 */
        public int zuanshi;
        /** 原单笔充值按钮配置。 */
        public int chongzhi;
        /** 原单笔充值重复领取配置。 */
        public int chongfu;
        /** 原版奖励三元组。 */
        public List<Integer> rew;
    }

    /**
     * 充值活动档位领取结果。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:17
     */
    public static class Result {
        public final int errno;
        public final List<Integer> rewards;

        /**
         * 保存结果与本次实际奖励。
         *
         * @param errno 原协议业务状态
         * @param rewards 本次实际奖励
         */
        Result(int errno, List<Integer> rewards) {
            this.errno = errno;
            this.rewards = rewards;
        }
    }
}
