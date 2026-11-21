package com.pokemon.local.activitydaily;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日充值档位、实发积分及积分兑换 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:04
 */
@Service
@DependsOnDatabaseInitialization
public class DailyRechargeService {
    public static final int ITEM = 1;
    public static final int CHIP = 3;
    public static final int DIAMONDS = 104;
    private static final int ACTIVITY = 10021;
    private static final int TOKEN = 21051;
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    // 原 meiri_proto，门槛后按类型、编号、数量排列。
    private static final int[][] TIERS = {
            {0, 1, 22019, 5, 104, 0, 1000, 1, 21051, 50},
            {7000, 1, 20003, 30, 104, 0, 2000, 1, 21051, 100},
            {35000, 1, 20003, 70, 104, 0, 3000, 1, 21051, 100},
            {70000, 1, 34404, 1, 104, 0, 5000, 1, 21051, 150},
            {150000, 1, 34404, 1, 104, 0, 8000, 1, 21051, 150},
            {210000, 1, 34404, 1, 104, 0, 10000, 1, 21051, 200},
            {350000, 1, 34404, 2, 104, 0, 15000, 1, 21051, 300}
    };
    // 原 jifen_proto，依次为奖励类型、编号、数量和积分成本。
    private static final int[][] EXCHANGES = {
            {3, 54016, 10, 200}, {3, 54017, 10, 200}, {3, 54018, 10, 200},
            {3, 53007, 10, 300}, {3, 53008, 10, 300}, {3, 53009, 10, 300}, {3, 53010, 10, 300},
            {1, 21016, 1, 1000}, {1, 21038, 1, 300}, {1, 21041, 1, 500}
    };
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ActivityService activities;

    /**
     * 使用活动周期与玩家真实订单余额。
     *
     * @param jdbc 存档读取与写入
     * @param roles 角色锁与钻石发放
     * @param inventory 活动道具和积分
     * @param activities 运营活动周期
     */
    DailyRechargeService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory, ActivityService activities) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.activities = activities;
    }

    /**
     * 初始化每日领取、兑换与精灵碎片持久化表。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("activity/recharge-daily.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取活动当前实际累计和当日档位。
     *
     * @param roleId 角色编号
     * @return 已支付金额换算出的原显示值及各档已领状态
     */
    public Map<String, Object> daily(String roleId) {
        Window window = window();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errno", window.active ? 0 : 100013);
        if (!window.active) {
            return result;
        }
        Set<Integer> claimed = claimed(roleId, window);
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (int i = 0; i < TIERS.length; i++) {
            int[] tier = TIERS[i];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("num", tier[0]);
            row.put("rew", Arrays.stream(tier).skip(1).boxed().collect(Collectors.toList()));
            row.put("flag", claimed.contains(i + 1) ? 1 : 0);
            tiers.add(row);
        }
        result.put("total", total(roleId, window));
        result.put("info", tiers);
        return result;
    }

    /**
     * 查询活动积分商品目录及开放状态。
     *
     * @return 原客户端活动商品列表
     */
    public Map<String, Object> shop() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean active = window().active;
        status.put("errno", active ? 0 : 100013);
        if (active) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (int[] offer : EXCHANGES) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("type", offer[0]);
                fields.put("id", offer[1]);
                fields.put("num", offer[2]);
                fields.put("costnum", offer[3]);
                entries.add(fields);
            }
            status.put("info", entries);
        }
        return status;
    }

    /**
     * 结算当天已满足门槛的充值福利。
     *
     * @param roleId 角色编号
     * @param selected 奖励档位
     * @return 原客户端领取结果和实发奖励
     */
    @Transactional
    public Result claim(String roleId, long selected) {
        if (selected < 1 || selected > TIERS.length) {
            return new Result(100021, Collections.emptyList());
        }
        roles.lockRole(roleId);
        Window window = window();
        int tier = (int) selected;
        int[] reward = TIERS[tier - 1];
        if (!window.active || total(roleId, window) < reward[0]) {
            return new Result(100012, Collections.emptyList());
        }
        if (claimed(roleId, window).contains(tier)) {
            return new Result(100011, Collections.emptyList());
        }
        if (!canAdd(roleId, reward[2], reward[3], false) || !canAdd(roleId, reward[8], reward[9], false)
                || (inventory.ordinaryUsed(roleId) == inventory.ordinaryCapacity(roleId) - 1
                        && inventory.count(roleId, reward[2]) == 0 && inventory.count(roleId, reward[8]) == 0)) {
            return new Result(100015, Collections.emptyList());
        }
        jdbc.update("INSERT INTO local_daily_recharge_claim (role_id,period_start,business_day,tier) VALUES (?,?,?,?)",
                roleId, window.period.start, window.date, tier);
        inventory.grant(roleId, reward[2], reward[3]);
        roles.addCurrency(roleId, 0, reward[6], 0);
        inventory.grant(roleId, TOKEN, reward[9]);
        return new Result(0, Arrays.asList(new Award(ITEM, reward[2], reward[3]), new Award(DIAMONDS, 0, reward[6]),
                new Award(ITEM, TOKEN, reward[9])));
    }

    /**
     * 从持久积分余额扣除一次实际兑换的成本。
     *
     * @param roleId 角色编号
     * @param requestId 当前连接的请求回执编号
     * @param selected 原兑换档位
     * @return 原协议兑换结果及本次实发奖励
     */
    @Transactional
    public Result exchange(String roleId, String requestId, long selected) {
        if (selected < 1 || selected > EXCHANGES.length) {
            return new Result(100021, Collections.emptyList());
        }
        roles.lockRole(roleId);
        Integer previous = jdbc.query("SELECT exchange_index FROM local_daily_recharge_exchange "
                + "WHERE role_id=? AND request_id=?", (row, index) -> row.getInt(1), roleId, requestId)
                .stream().findFirst().orElse(null);
        if (previous != null) {
            return new Result(previous == selected ? 0 : 100021, Collections.emptyList());
        }
        if (!window().active) {
            return new Result(100013, Collections.emptyList());
        }
        int[] offer = EXCHANGES[(int) selected - 1];
        long owned = inventory.count(roleId, TOKEN);
        if (owned < offer[3]) {
            return new Result(100005, Collections.emptyList());
        }
        if (offer[0] == ITEM && !canAdd(roleId, offer[1], offer[2], owned == offer[3])) {
            return new Result(100015, Collections.emptyList());
        }
        if (!inventory.spendMaterials(roleId, TOKEN, offer[3], 0, 0)) {
            throw new IllegalStateException("结算期间活动积分余额变化");
        }
        if (offer[0] == CHIP) {
            int updated = jdbc.update("UPDATE local_elf_chip SET quantity=quantity+? WHERE role_id=? AND config_id=?",
                    offer[2], roleId, offer[1]);
            if (updated == 0) {
                jdbc.update("INSERT INTO local_elf_chip (role_id,config_id,quantity) VALUES (?,?,?)", roleId, offer[1], offer[2]);
            }
        } else {
            inventory.grant(roleId, offer[1], offer[2]);
        }
        jdbc.update("INSERT INTO local_daily_recharge_exchange (role_id,request_id,exchange_index) VALUES (?,?,?)",
                roleId, requestId, selected);
        return new Result(0, Collections.singletonList(new Award(offer[0], offer[1], offer[2])));
    }

    /**
     * 查询已入账的精灵碎片背包。
     *
     * @param roleId 角色编号
     * @return 碎片模板与数量快照
     */
    public List<Award> chips(String roleId) {
        return jdbc.query("SELECT config_id,quantity FROM local_elf_chip WHERE role_id=? ORDER BY config_id",
                (row, index) -> new Award(CHIP, row.getInt(1), row.getInt(2)), roleId);
    }

    /**
     * 查询原活动内北京时间当日已确认的模拟充值金额。
     *
     * @param roleId 角色编号
     * @param window 当前参与日
     * @return 原客户端展示的充值钻石单位
     */
    private long total(String roleId, Window window) {
        Long cents = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM local_recharge_activity_order "
                + "WHERE role_id=? AND confirmed_at>=? AND confirmed_at<?", Long.class, roleId, window.start, window.end);
        return Math.multiplyExact(cents, 10);
    }

    /**
     * 查询参与日的领取记录。
     *
     * @param roleId 角色编号
     * @param window 当前参与日
     * @return 已领取档位
     */
    private Set<Integer> claimed(String roleId, Window window) {
        return new HashSet<>(jdbc.query("SELECT tier FROM local_daily_recharge_claim "
                + "WHERE role_id=? AND period_start=? AND business_day=?",
                (row, index) -> row.getInt(1), roleId, window.period.start, window.date));
    }

    /**
     * 判断奖励道具是否能完整存入背包。
     *
     * @param roleId 角色编号
     * @param itemId 道具配置
     * @param quantity 发放数量
     * @param clearSlot 本次消费是否释放一个背包格
     * @return 是否可发放
     */
    private boolean canAdd(String roleId, int itemId, int quantity, boolean clearSlot) {
        long owned = inventory.count(roleId, itemId);
        return owned <= Integer.MAX_VALUE - quantity
                && (owned > 0 || clearSlot || inventory.ordinaryUsed(roleId) < inventory.ordinaryCapacity(roleId));
    }

    /**
     * 计算当前活动周期和北京时间业务日的交集。
     *
     * @return 当前日锁定后的活动窗口
     */
    private Window window() {
        ActivityPeriod period = activities.cycle(ACTIVITY);
        Instant now = Instant.now();
        LocalDate date = now.atZone(GAME_ZONE).toLocalDate();
        long start = Math.max(period.start, date.atStartOfDay(GAME_ZONE).toEpochSecond());
        long end = Math.min(period.end, date.plusDays(1).atStartOfDay(GAME_ZONE).toEpochSecond());
        return new Window(period, now.getEpochSecond() >= start && now.getEpochSecond() < end,
                date.toString(), start * 1000, end * 1000);
    }

    /**
     * 同一个业务日用于订单统计和活动领取的范围。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:04
     */
    private static final class Window {
        final ActivityPeriod period;
        final boolean active;
        final String date;
        final long start;
        final long end;

        /**
         * 保存活动周期与当前北京时间自然日交集。
         *
         * @param period 当前活动周期
         * @param active 当前是否能够领取
         * @param date 北京时间业务日期
         * @param start 统计开始时间戳（毫秒）
         * @param end 统计截止时间戳（毫秒）
         */
        Window(ActivityPeriod period, boolean active, String date, long start, long end) {
            this.period = period;
            this.active = active;
            this.date = date;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 原客户端展示的实际奖励条目。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:04
     */
    public static final class Award {
        public final int type;
        public final int id;
        public final int quantity;

        /**
         * 记录一种实际发放的奖励。
         *
         * @param type 原始奖励类别
         * @param id 道具或碎片配置编号
         * @param quantity 本次发放数量
         */
        Award(int type, int id, int quantity) {
            this.type = type;
            this.id = id;
            this.quantity = quantity;
        }
    }

    /**
     * 一次请求的状态和本次新发放的奖励。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:04
     */
    public static final class Result {
        public final int errno;
        public final List<Award> awards;

        /**
         * 记录原协议状态和实际资产变化。
         *
         * @param errno 原协议状态代码
         * @param awards 本次实际发放的奖励
         */
        Result(int errno, List<Award> awards) {
            this.errno = errno;
            this.awards = awards;
        }
    }
}
