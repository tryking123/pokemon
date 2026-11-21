package com.pokemon.local.activitydaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原日活任务的自然日进度、单任务领奖与重试去重。
 * @author tongruiyuan
 * @since 2026-11-20 21:20
 */
@Service
public class DailyMissionService {
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final DailyVitalityEvents events;
    private final DiamondSpendMapper spending;
    private final JsonNode rules;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    /**
     * 装载原日活配置和已提交行为流水。
     * @param jdbc 本地存档
     * @param roles 角色事务锁
     * @param inventory 实物发放
     * @param events 活跃积分账本
     * @param spending 钻石扣费流水
     * @param json 原配置解析
     * @throws IOException 无法读取配置
     */
    public DailyMissionService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
            DailyVitalityEvents events, DiamondSpendMapper spending, ObjectMapper json) throws IOException {
        this.jdbc=jdbc;this.roles=roles;this.inventory=inventory;this.events=events;this.spending=spending;
        try(java.io.InputStream in=new ClassPathResource("activity/daily-missions.json").getInputStream()) {rules=json.readTree(in);}
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_daily_mission_claim(role_id VARCHAR(36) NOT NULL,day_start BIGINT NOT NULL,task_id INTEGER NOT NULL,PRIMARY KEY(role_id,day_start,task_id))");
    }

    /**
     * 读取原每日任务的真实进度；尚无本地行为来源的项目保持未完成。
     * @param roleId 角色编号
     * @return 原任务列表
     */
    public List<Map<String,Object>> snapshot(String roleId) {
        long start=LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond(),end=start+86400;
        Map<Integer,Long> counts=new HashMap<>();
        counts.put(40001,spending.totalBetween(roleId,start*1000,end*1000));
        counts.put(40002,jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_quicken WHERE role_id=? AND reward_day=?",Long.class,roleId,LocalDate.now(ZONE).toString()));
        counts.put(40003,jdbc.queryForObject("SELECT COUNT(*) FROM local_gacha_draw WHERE role_id=? AND draw_type=1 AND committed_at>=? AND committed_at<?",Long.class,roleId,start,end));
        counts.put(40004,jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN draw_type=3 THEN 10 ELSE 1 END),0) FROM local_gacha_draw WHERE role_id=? AND draw_type IN (2,3) AND committed_at>=? AND committed_at<?",Long.class,roleId,start,end));
        counts.put(40009,jdbc.queryForObject("SELECT COUNT(*) FROM local_personal_guild_donation WHERE role_id=? AND created_at>=? AND created_at<?",Long.class,roleId,start,end));
        for(long[] row:events.counts(roleId,start,end)) {
            if(row[0]==15)counts.merge(40007,row[1],Long::sum);
            if(row[0]==17)counts.merge(40012,row[1],Long::sum);
        }
        Set<Integer> claimed=new HashSet<>(jdbc.queryForList("SELECT task_id FROM local_daily_mission_claim WHERE role_id=? AND day_start=?",Integer.class,roleId,start));
        List<Map<String,Object>> list=new ArrayList<>();
        for(JsonNode cfg:rules) {
            int id=cfg.path("id").asInt(),goal=cfg.path("goal").asInt();
            long progress=Math.min(goal,counts.getOrDefault(id,0L));
            Map<String,Object> task=new LinkedHashMap<>();
            task.put("id",id);task.put("progress",(int) progress);task.put("state",claimed.contains(id)?0:progress>=goal?2:1);
            list.add(task);
        }
        return list;
    }

    /**
     * 领取当天真实完成的单任务奖励；同一天同任务只发一次。
     * @param roleId 角色编号
     * @param taskId 原任务编号
     * @return 错误码，零表示成功或已领取
     */
    @Transactional
    public int claim(String roleId,int taskId) {
        roles.lockRole(roleId);
        Map<String,Object> task=snapshot(roleId).stream().filter(x->Integer.valueOf(taskId).equals(x.get("id"))).findFirst().orElse(null);
        if(task==null)return 5001;
        int state=(Integer)task.get("state");
        if(state==0)return 0;
        if(state!=2)return 100032;
        long day=LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond();
        JsonNode rule=null;for(JsonNode r:rules)if(r.path("id").asInt()==taskId)rule=r;
        JsonNode rewards=rule.path("rewards");
        Map<Integer,Long> items=new LinkedHashMap<>();long coins=0,diamonds=0,points=0;
        for(int i=0;i<rewards.size();i+=3) {
            int type=rewards.get(i).asInt(),id=rewards.get(i+1).asInt();long n=rewards.get(i+2).asLong();
            if(type==1)items.merge(id,n,Math::addExact);
            else if(type==103)coins+=n;else if(type==104)diamonds+=n;else if(type==106)points+=n;
            else throw new IllegalStateException("日活奖励类型不受支持");
        }
        inventory.grantItemsOrMail(roleId,"daily-mission:"+day+":"+taskId,items);
        roles.addCurrency(roleId,Math.toIntExact(coins),Math.toIntExact(diamonds),0);
        events.record(roleId,DailyVitalityEvents.MAIL_POINTS,"daily-mission:"+day+":"+taskId,Math.toIntExact(points));
        jdbc.update("INSERT INTO local_daily_mission_claim(role_id,day_start,task_id) VALUES(?,?,?)",roleId,day,taskId);
        return 0;
    }
}
