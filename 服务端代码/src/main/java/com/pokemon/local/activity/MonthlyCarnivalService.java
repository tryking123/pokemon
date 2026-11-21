package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 月度狂欢 Service，按当天的真实签到顺序领取三十档奖励。
 *
 * @author tongruiyuan
 * @since 2026-11-20 12:48
 */
@Service
public class MonthlyCarnivalService {
    private final JdbcTemplate jdbc;
    private final ActivityService calendar;
    private final InventoryService inventory;
    private final RoleService roles;
    private final List<Map<Integer, Long>> rewards = new ArrayList<>();

    /**
     * 从随包配置读取每日道具，准备本地领奖表。
     * @param jdbc 本地存档
     * @param calendar 活动周期
     * @param inventory 道具和溢出邮件
     * @param roles 角色状态与事务锁
     * @param json 奖励配置解析器
     * @throws IOException 配置读取错误
     */
    public MonthlyCarnivalService(JdbcTemplate jdbc, ActivityService calendar, InventoryService inventory,
                                 RoleService roles, ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.inventory = inventory;
        this.roles = roles;
        try (InputStream input = new ClassPathResource("activity/monthly-carnival.json").getInputStream()) {
            int[][] rows = json.readValue(input, int[][].class);
            if (rows.length != 30) throw new IllegalStateException("月度狂欢需配置三十档");
            for (int[] row : rows) {
                Map<Integer, Long> items = new LinkedHashMap<>();
                if (row.length == 0 || row.length % 2 != 0) throw new IllegalStateException("月度奖档格式错误");
                for (int i = 0; i < row.length; i += 2) {
                    if (row[i + 1] < 1) throw new IllegalStateException("月度奖品数量无效");
                    items.merge(row[i], (long) row[i + 1], Long::sum);
                }
                rewards.add(items);
            }
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_monthly_carnival(role_id VARCHAR(36) NOT NULL,"
                + "cycle_start BIGINT NOT NULL,tier INTEGER NOT NULL,claim_day BIGINT NOT NULL,"
                + "PRIMARY KEY(role_id,cycle_start,tier),UNIQUE(role_id,claim_day))");
    }

    /** 获取月度狂欢当前轮次。
     * @return 当前的活动时段
     */
    public ActivityPeriod period() {
        return calendar.cycle(20002);
    }

    /** 列出本期已经领取的原奖档。
     * @param role 角色编号
     * @param start 轮次起点
     * @return 已领取档位
     */
    private List<Integer> claimed(String role, long start) {
        return jdbc.queryForList("SELECT tier FROM local_monthly_carnival WHERE role_id=? AND cycle_start=? ORDER BY tier",
                Integer.class, role, start);
    }

    /** 判断角色是否进行了今天的普通签到。
     * @param role 角色编号
     * @param now 当前秒时间戳
     * @return 今天是否已正常签到
     */
    private boolean signed(String role, long now) {
        ActivityPeriod sign = calendar.cycleAt(10006, now);
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_attendance_day WHERE role_id=? AND cycle_start=?"
                + " AND day_index=? AND patch_receipt IS NULL", Integer.class, role, sign.start, sign.day) > 0;
    }

    /** 将实际数量组装为原版物品预览或到账提示。
     * @param items 道具编号与数量
     * @return 原附件结构
     */
    private Map<String, Object> attachment(Map<Integer, Long> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        items.forEach((id, quantity) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("n", quantity);
            // 原 ghelper.parse_reward 将携带 type 的物品视为技能书；普通奖励只传模板与数量。
            list.add(item);
        });
        return Collections.singletonMap("items", list);
    }

    /** 读取原 C320 阶段任务或 C322 三十日页面。
     * @param role 角色编号
     * @param period 已展示的周期
     * @param missions 是否读取任务摘要
     * @return 原请求响应体
     */
    public Map<String, Object> panel(String role, ActivityPeriod period, boolean missions) {
        long now = Instant.now().getEpochSecond();
        List<Integer> done = claimed(role, period.start);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        result.put("errno", 0);
        result.put("total", done.size());
        result.put("info", rows);
        if (missions) {
            result.put("idx", done);
            for (int tier = 0; tier < rewards.size(); tier++) {
                Map<String, Object> task = new LinkedHashMap<>();
                List<Long> amounts = new ArrayList<>();
                rewards.get(tier).forEach((id, count) -> Collections.addAll(amounts, 1L, id.longValue(), count));
                task.put("aim", tier + 1);
                task.put("awardlist", amounts);
                rows.add(task);
            }
        } else {
            for (Map<Integer, Long> row : rewards) rows.add(attachment(row));
            long day = Math.floorDiv(now + 28800L, 86400L);
            boolean today = claimedToday(role, day);
            // 已领显示上一档为已完成；未签到时只显示已得的进度，不预置当天资格。
            boolean eligible = done.size() < rewards.size() && !today && signed(role, now);
            result.put("day", eligible ? done.size() + 1 : done.size());
            result.put("award", eligible ? 0 : 1);
            result.put("endtime", period.end);
        }
        return result;
    }

    /** 检查当天是否已领取。
     * @param role 角色编号
     * @param day 北京时间自然日序号
     * @return 是否已领取
     */
    private boolean claimedToday(String role, long day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_monthly_carnival WHERE role_id=? AND claim_day=?",
                Integer.class, role, day) > 0;
    }

    /** 核实本日签到并在同一事务内发放当前档位；满包时复用道具邮件。
     * @param role 角色编号
     * @param shown 最近展示的周期起点
     * @param tier 请求的档位
     * @return 实际入包的奖励附件，资格拒绝时为空引用
     */
    @Transactional
    public Map<String, Object> claim(String role, long shown, long tier) {
        roles.lockRole(role);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod cycle = calendar.cycleAt(20002, now);
        long day = Math.floorDiv(now + 28800L, 86400L);
        if (now < cycle.start || now >= cycle.end || cycle.start != shown || tier < 1 || tier > rewards.size()
                || tier != claimed(role, shown).size() + 1L || claimedToday(role, day) || !signed(role, now)) return null;
        jdbc.update("INSERT INTO local_monthly_carnival(role_id,cycle_start,tier,claim_day) VALUES(?,?,?,?)",
                role, shown, tier, day);
        Map<Integer, Long> gained = inventory.grantItemsOrMail(role, "monthly:" + shown + ":" + tier, rewards.get((int) tier - 1));
        return attachment(gained);
    }
}
