package com.pokemon.local.activity;

import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 钻石福袋每日任务、每周福气和开袋回执。
 * @author tongruiyuan
 * @since 2026-11-19 21:58
 */
@Service
public class LuckyBagService {
    private final ActivityService calendar;
    private final RoleService roles;
    private final JdbcTemplate jdbc;

    /** 绑定活动日历、真实任务和角色钱包。
     * @param calendar 周期服务
     * @param roles 角色事务
     * @param jdbc 本地数据库
     */
    public LuckyBagService(ActivityService calendar, RoleService roles, JdbcTemplate jdbc) {
        this.calendar = calendar;
        this.roles = roles;
        this.jdbc = jdbc;
    }

    /** 给本轮当天的对应动作建立一次可领取资格。
     * @param id 角色编号
     * @param task 原任务编号
     */
    @Transactional
    public void participate(String id, int task) {
        LocalRole role = roles.lockRole(id);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod p = calendar.cycleAt(10074, now);
        if (role.level < 30 || now < p.start || task < 61000 || task > 61003) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_lucky_task WHERE role_id=? AND period_start=? AND day_no=? AND task_id=?",
                Integer.class, id, p.start, p.day, task) == 0) {
            jdbc.update("INSERT INTO local_lucky_task(role_id,period_start,day_no,task_id,claimed) VALUES(?,?,?,?,0)",
                    id, p.start, p.day, task);
        }
    }

    /** 返回当前周期福气余额。
     * @param id 角色编号
     * @param start 稳定轮次起点
     * @return 实领福气减去已开袋消耗
     */
    private int balance(String id, long start) {
        int points = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN task_id=61000 THEN 10 ELSE 30 END),0) FROM local_lucky_task WHERE role_id=? AND period_start=? AND claimed=1",
                Integer.class, id, start);
        int bags = jdbc.queryForObject("SELECT COUNT(*) FROM local_lucky_bag WHERE role_id=? AND period_start=?", Integer.class, id, start);
        return points - 100 * bags;
    }

    /** 构造 S100/101 的四条日任务及 S460 当轮余额。
     * @param role 已登录角色
     * @return 当前原表季号、福气与任务
     */
    @Transactional
    public Map<String,Object> panel(LocalRole role) {
        long now = Instant.now().getEpochSecond();
        ActivityPeriod p = calendar.cycleAt(10074, now);
        Map<String,Object> state = new LinkedHashMap<>();
        List<Map<String,Object>> tasks = new ArrayList<>();
        boolean active = role.level >= 30 && now >= p.start;
        state.put("season", active ? 1 : 0);
        state.put("period", p.start);
        state.put("day", p.day);
        state.put("tasks", tasks);
        state.put("felicity", active ? balance(role.id,p.start) : 0);
        if (!active) return state;
        Map<Integer,Integer> states = new LinkedHashMap<>();
        for (int[] row : jdbc.query("SELECT task_id,claimed FROM local_lucky_task WHERE role_id=? AND period_start=? AND day_no=?",
                (rs,n) -> new int[]{rs.getInt(1), 2+rs.getInt(2)}, role.id,p.start,p.day)) {
            states.put(row[0],row[1]);
        }
        for (int task=61000;task<=61003;task++) {
            int current = states.getOrDefault(task,1);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", task);
            row.put("state", current);
            row.put("progress", current >= 2 ? 1 : 0);
            tasks.add(row);
        }
        return state;
    }

    /** 获取当日任务的原消息列表。
     * @param panel 当前面板
     * @return 任务列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> tasks(Map<String,Object> panel) {
        return (List<Map<String,Object>>) panel.get("tasks");
    }

    /** 接受原客户端自动提交的已完成任务，按日唯一获取福气。
     * @param id 角色编号
     * @param task 原任务编号
     * @return 本次实得福气，零表示不可领取
     */
    @Transactional
    public int submit(String id, int task) {
        LocalRole role = roles.lockRole(id);
        ActivityPeriod p = calendar.cycle(10074);
        if (role.level < 30 || Instant.now().getEpochSecond() < p.start) return 0;
        int changed = jdbc.update("UPDATE local_lucky_task SET claimed=1 WHERE role_id=? AND period_start=? AND day_no=? AND task_id=? AND claimed=0",
                id, p.start,p.day,task);
        return changed == 1 ? (task == 61000 ? 10 : 30) : 0;
    }

    /** 验证福气、扣除一百点并将抽中的钻石计入真实钱包。
     * @param id 角色编号
     * @param receipt 已认证连接请求
     * @return 实发钻石，零表示不可领取
     */
    @Transactional
    public int open(String id,String receipt) {
        LocalRole role = roles.lockRole(id);
        ActivityPeriod p = calendar.cycle(10074);
        if (role.level < 30 || Instant.now().getEpochSecond() < p.start || balance(id,p.start) < 100) return 0;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_lucky_bag WHERE role_id=? AND receipt=?",Integer.class,id,receipt)>0) return 0;
        int roll = ThreadLocalRandom.current().nextInt(10000);
        int diamonds = roll < 7000 ? 888 : roll < 9400 ? 1888 : roll < 9900 ? 3888 : 8888;
        jdbc.update("INSERT INTO local_lucky_bag(role_id,receipt,period_start,diamonds) VALUES(?,?,?,?)",id,receipt,p.start,diamonds);
        roles.addCurrency(id,0,diamonds,0);
        return diamonds;
    }
}
