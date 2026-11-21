package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activitydaily.DailyVitalityService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新春基金 Service，按当周真实订单与每日活跃记录结算免费和激活奖励。
 * @author tongruiyuan
 * @since 2026-11-20 10:16
 */
@Service
public class NewYearFundService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final DailyVitalityService vitality;
    private final InventoryService inventory;
    private final JsonNode config;

    /**
     * 连接本地资产、任务及原基金配置。
     * @param jdbc 业务存档
     * @param roles 当前角色与钱包
     * @param vitality 每日活跃流水
     * @param inventory 物品与精灵碎片
     * @param json 原配置解析器
     * @throws IOException 随包配置读取失败
     */
    public NewYearFundService(JdbcTemplate jdbc, RoleService roles, DailyVitalityService vitality,
                              InventoryService inventory, ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.roles = roles;
        this.vitality = vitality;
        this.inventory = inventory;
        try (InputStream in = new ClassPathResource("activity/new-year-fund.json").getInputStream()) {
            config = json.readTree(in);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_new_year_fund(role_id VARCHAR(100) NOT NULL,"
                + "week_start BIGINT NOT NULL,award_key INTEGER NOT NULL,PRIMARY KEY(role_id,week_start,award_key))");
    }

    /**
     * 获取当前轮次的开始时间。
     * @return 北京时间周一零点的 Unix 秒
     */
    public long weekStart() {
        return LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE).toEpochSecond();
    }

    /**
     * 查看原页面要求的轮次、激活标识、活跃度和已领档位。
     * @param id 当前角色
     * @param start 本周开始时间
     * @return 查询时的实际进度
     */
    public State state(String id, long start) {
        Set<Integer> awards = new HashSet<>(jdbc.queryForList(
                "SELECT award_key FROM local_new_year_fund WHERE role_id=? AND week_start=?", Integer.class, id, start));
        int flag = awards.contains(0) ? 2 : 0;
        if (flag == 0 && !jdbc.queryForList("SELECT id FROM local_game_payment_order WHERE role_id=? AND paysid=9"
                + " AND confirmed_at>=? AND confirmed_at<? LIMIT 1", String.class,
                id, start * 1000, (start + 604800L) * 1000).isEmpty()) {
            flag = 1;
        }
        long points = vitality.weeklyPoints(id, Instant.ofEpochSecond(start).atZone(ZONE).toLocalDate());
        return new State(flag, points, awards);
    }

    /**
     * 领取本周激活包或所有达标未领档位，补发免费先领后的付费差额。
     * @param id 当前角色
     * @param preview 原连接最后展示的轮次
     * @param unlock 是否领取单笔充值激活包
     * @param index 点击的档位，一键领取时为零
     * @return 是否有奖励实际到账
     */
    @Transactional
    public boolean claim(String id, long preview, boolean unlock, long index) {
        if (roles.lockRole(id).level < 30 || preview != weekStart()) {
            return false;
        }
        State state = state(id, preview);
        if (unlock) {
            if (state.flag != 1) return false;
            grant(id, preview, 0, config.get("activation"));
            return true;
        }
        JsonNode tiers = config.get("tiers");
        if (index < 0 || index > tiers.size()
                || (index > 0 && tiers.get((int) index - 1).get("points").asLong() > state.points)) {
            return false;
        }
        boolean granted = false;
        for (int i = 1; i <= tiers.size(); i++) {
            JsonNode tier = tiers.get(i - 1);
            if (tier.get("points").asLong() > state.points) break;
            if (!state.awards.contains(i)) {
                grant(id, preview, i, tier.get("free"));
                granted = true;
            }
            if (state.flag == 2 && !state.awards.contains(-i)) {
                grant(id, preview, -i, tier.get("paid"));
                granted = true;
            }
        }
        return granted;
    }

    /**
     * 发放单档奖励，使用签名档位区分免费、激活与已付费分支。
     * @param id 角色编号
     * @param start 本周开始时间
     * @param key 激活包为零、免费为正数、付费为负数
     * @param row 原奖励三元组
     */
    private void grant(String id, long start, int key, JsonNode row) {
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i += 3) {
            int type = row.get(i).asInt(), asset = row.get(i + 1).asInt(), count = row.get(i + 2).asInt();
            switch (type) {
                case 1: items.merge(asset, (long) count, Long::sum); break;
                case 3: inventory.grantChip(id, asset, count); break;
                case 104: roles.addCurrency(id, 0, count, 0); break;
                case 118: inventory.grantHomeCoins(id, count); break;
                default: throw new IllegalStateException("未知基金奖励类别 " + type);
            }
        }
        inventory.grantItemsOrMail(id, "new-year-fund:" + start + ":" + key, items);
        jdbc.update("INSERT INTO local_new_year_fund(role_id,week_start,award_key) VALUES(?,?,?)", id, start, key);
    }

    /**
     * 基金本周的真实状态和每档领取记录。
     * @author tongruiyuan
     * @since 2026-11-20 10:16
     */
    public static final class State {
        /** 原客户端所用标志，0 未满足，1 可激活，2 已激活。 */
        public final int flag;
        /** 当周已累计的任务活跃。 */
        public final long points;
        /** 已实际发放的档位分支。 */
        public final Set<Integer> awards;

        /**
         * 保存本次查询的状态。
         * @param flag 激活标志
         * @param points 任务活跃
         * @param awards 已发放档位
         */
        private State(int flag, long points, Set<Integer> awards) {
            this.flag = flag;
            this.points = points;
            this.awards = awards;
        }
    }
}
