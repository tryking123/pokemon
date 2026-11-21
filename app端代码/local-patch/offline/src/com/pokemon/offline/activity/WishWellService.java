package com.pokemon.offline.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.activitydaily.DailyVitalityService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 许愿池 Service，按当日活跃锁定心愿并于后续自然日结算。
 *
 * @author tongruiyuan
 * @since 2026-11-19 17:27
 */
@Service
@DependsOnDatabaseInitialization
public class WishWellService {
    public static final int ACTIVITY = 10050;
    private static final long DAY = 86400;
    private final ActivityService calendar;
    private final ActivityMapper activity;
    private final DailyVitalityService vitality;
    private final InventoryService inventory;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final JsonNode offers;

    /**
     * 载入原奖表并绑定本地活跃、订单和资产服务。
     *
     * @param calendar 活动日历
     * @param activity 真实订单累计
     * @param vitality 日活跃积分
     * @param inventory 道具与溢出邮件
     * @param roles 角色事务与货币余额
     * @param jdbc 许愿存档
     * @param json 奖励配置读取器
     * @throws IOException 随包奖表不可读取
     */
    WishWellService(ActivityService calendar, ActivityMapper activity, DailyVitalityService vitality,
                    InventoryService inventory, RoleService roles, JdbcTemplate jdbc, ObjectMapper json)
            throws IOException {
        this.calendar = calendar;
        this.activity = activity;
        this.vitality = vitality;
        this.inventory = inventory;
        this.roles = roles;
        this.jdbc = jdbc;
        try (InputStream input = new ClassPathResource("activity/wish-well.json").getInputStream()) {
            offers = json.readTree(input);
        }
        if (!offers.isArray() || offers.size() != 10) {
            throw new IllegalArgumentException("许愿池原表应含十档");
        }
    }

    /** 初始化许愿日和选择明细存档。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("activity/wish-well.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 查询原活动窗口对应的北京自然日零点。
     *
     * @return 当前可展示的自然日起点，活动未开时返回负一
     */
    public long day() {
        long now = System.currentTimeMillis() / 1000;
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        return now < period.start ? -1 : period.start + (period.day - 1) * DAY;
    }

    /**
     * 读取尚未领取或当天已确认的心愿。
     *
     * @param roleId 当前角色编号
     * @param today 当天零点秒数
     * @return 许愿日、选择位图及领取状态
     */
    private List<long[]> wishes(String roleId, long today) {
        return jdbc.query("SELECT wish_day, choices, claimed FROM local_wish_well WHERE role_id=? "
                + "AND (claimed=0 OR wish_day=?) ORDER BY wish_day",
                (row, index) -> new long[]{row.getLong(1), row.getInt(2), row.getInt(3)}, roleId, today);
    }

    /**
     * 查询选择机会、十档奖品和冻结的心愿。
     *
     * @param roleId 当前角色编号
     * @param today 当前面板对应的北京自然日
     * @return 原许愿池面板字段
     */
    public Map<String, Object> status(String roleId, long today) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (today < 0 || today != day()) {
            fields.put("errno", 100013);
            return fields;
        }
        List<Map<String, Object>> info = new ArrayList<>();
        for (JsonNode offer : offers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sid", info.size() + 1);
            row.put("num", offer.get("num").intValue());
            row.put("time", offer.get("time").intValue());
            row.put("award", Arrays.asList(offer.get("award").get(0).intValue(), offer.get("award").get(1).intValue(),
                    offer.get("award").get(2).intValue()));
            info.add(row);
        }
        fields.put("errno", 0);
        fields.put("info", info);
        List<long[]> stored = wishes(roleId, today);
        fields.put("locknum", stored.isEmpty() ? choices(roleId) : 0);
        fields.put("getflag", 0);
        fields.put("double", 1);
        if (!stored.isEmpty()) {
            long[] wish = stored.get(0);
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < offers.size(); i++) {
                if ((wish[1] & (1 << i)) != 0) {
                    selected.add(i + 1);
                }
            }
            fields.put("lockthing", selected);
            fields.put("getflag", wish[0] < today && wish[2] == 0 ? 1 : 0);
            fields.put("double", multiplier(roleId, wish[0]));
            if (wish[0] < today) {
                fields.put("locknum", choices(roleId));
            }
        }
        return fields;
    }

    /**
     * 计算当日活跃可选择的不重复奖励个数。
     *
     * @param roleId 当前角色编号
     * @return 可选个数
     */
    private long choices(String roleId) {
        return Math.min(offers.size(), vitality.snapshot(roleId).points / offers.get(0).get("num").intValue());
    }

    /**
     * 检查许愿日内已确认订单是否触发双倍奖励。
     *
     * @param roleId 当前角色编号
     * @param day 许愿日零点
     * @return 奖励倍数
     */
    private int multiplier(String roleId, long day) {
        return activity.rechargeSum(roleId, day * 1000, (day + DAY) * 1000) > 0 ? 2 : 1;
    }

    /**
     * 冻结当天的选择，拒绝重复、越界和超过活跃额度的选择。
     *
     * @param roleId 当前角色编号
     * @param today 玩家打开面板的自然日
     * @param indexes 原界面从一开始的选择序号
     * @return 原协议错误码
     */
    @Transactional
    public int lock(String roleId, long today, List<?> indexes) {
        roles.lockRole(roleId);
        if (today < 0 || today != day()) {
            return 100013;
        }
        if (!wishes(roleId, today).isEmpty()) {
            return 100011;
        }
        if (indexes.isEmpty() || indexes.size() > choices(roleId)) {
            return 100012;
        }
        int mask = 0;
        for (Object index : indexes) {
            long n = index instanceof Number ? ((Number) index).longValue() : 0;
            if (n < 1 || n > offers.size() || (mask & (1 << (n - 1))) != 0) {
                return 100021;
            }
            mask |= 1 << (n - 1);
        }
        jdbc.update("INSERT INTO local_wish_well (role_id,wish_day,choices,claimed) VALUES(?,?,?,0)",
                roleId, today, mask);
        return 0;
    }

    /**
     * 领取较早自然日冻结的奖励，合并同模板数量并将溢出道具投递系统邮件。
     *
     * @param roleId 当前角色编号
     * @param today 当前面板对应的自然日起点
     * @return 成功时为实际奖励倍数，失败时为负错误码
     */
    @Transactional
    public int claim(String roleId, long today) {
        roles.lockRole(roleId);
        if (today < 0 || today != day()) {
            return -100013;
        }
        List<long[]> saved = wishes(roleId, today);
        if (saved.isEmpty() || saved.get(0)[0] >= today) {
            return -100012;
        }
        long[] wish = saved.get(0);
        int times = multiplier(roleId, wish[0]);
        Map<Integer, Long> items = new LinkedHashMap<>();
        long diamonds = 0;
        for (int i = 0; i < offers.size(); i++) {
            if ((wish[1] & (1 << i)) == 0) {
                continue;
            }
            JsonNode award = offers.get(i).get("award");
            long quantity = times * award.get(2).longValue();
            if (award.get(0).intValue() == 104) {
                diamonds += quantity;
            } else {
                items.merge(award.get(1).intValue(), quantity, Long::sum);
            }
        }
        inventory.grantItemsOrMail(roleId, "wish:" + wish[0], items);
        if (diamonds > 0) {
            roles.addCurrency(roleId, 0, diamonds, 0);
        }
        jdbc.update("UPDATE local_wish_well SET claimed=1 WHERE role_id=? AND wish_day=?", roleId, wish[0]);
        return times;
    }
}
