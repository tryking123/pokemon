package com.pokemon.offline.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activitylogin.LoginActivityService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 通行证赛季 Service，结算充值激活、等级奖励、商店和购买等级。
 * @author tongruiyuan
 * @since 2026-11-20 12:09
 */
@Service
public class PassportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_MONDAY = LocalDate.of(2026,11,16);
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final LoginActivityService dresses;
    private final ActivityMapper payments;
    private final JsonNode seasons;
    private final JsonNode missions;

    /**
     * 绑定通行证使用的原始底表与本地角色账本。
     * @param jdbc 角色数据库
     * @param roles 角色锁和货币
     * @param inventory 材料背包
     * @param dresses 外观授予记录
     * @param payments 纳入活动的已确认模拟订单
     * @param json 配置读取器
     * @throws IOException 读取随包配置失败
     */
    public PassportService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                           LoginActivityService dresses, ActivityMapper payments, ObjectMapper json) throws IOException {
        this.jdbc=jdbc; this.roles=roles; this.inventory=inventory; this.dresses=dresses; this.payments=payments;
        try (InputStream input=new ClassPathResource("activity/passport.json").getInputStream()) {
            JsonNode config=json.readTree(input);
            seasons=config.required("seasons"); missions=config.required("tasks");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_passport_action(role_id VARCHAR(100) NOT NULL,season_start BIGINT NOT NULL,action_key VARCHAR(128) NOT NULL,experience INTEGER NOT NULL,PRIMARY KEY(role_id,season_start,action_key))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_passport_cosmetic(role_id VARCHAR(100) NOT NULL,kind INTEGER NOT NULL,asset INTEGER NOT NULL,quantity BIGINT NOT NULL,PRIMARY KEY(role_id,kind,asset))");
    }

    /** 返回当前双周赛季起始的北京时间零点秒数。 */
    public long start() {
        long delta=LocalDate.now(ZONE).toEpochDay()-FIRST_MONDAY.toEpochDay();
        return FIRST_MONDAY.plusDays(Math.floorDiv(delta,14L)*14L).atStartOfDay(ZONE).toEpochSecond();
    }

    /**
     * 读取赛季对应的客户端配置编号。
     * @param start 赛季起始秒数
     * @return 原通行证赛季编号
     */
    public int season(long start) {
        long base=FIRST_MONDAY.atStartOfDay(ZONE).toEpochSecond();
        return (int)Math.floorMod(Math.floorDiv(start-base,14L*86400),seasons.size())+1;
    }

    /**
     * 读取当前赛季全部已提交的动作。
     * @param id 角色编号
     * @param start 赛季起始秒数
     * @return 动作与经验
     */
    private Map<String,Integer> actions(String id,long start) {
        Map<String,Integer> list=new LinkedHashMap<>();
        jdbc.query("SELECT action_key,experience FROM local_passport_action WHERE role_id=? AND season_start=?",
                row->{list.put(row.getString(1),row.getInt(2));},id,start);
        return list;
    }

    /**
     * 持锁记录本期一次动作。
     * @param id 角色
     * @param start 赛季起始
     * @param key 动作凭据
     * @param exp 新增经验
     */
    private void record(String id,long start,String key,int exp) {
        jdbc.update("INSERT INTO local_passport_action(role_id,season_start,action_key,experience) VALUES(?,?,?,?)",id,start,key,exp);
    }

    /**
     * 将累计经验换算为原表等级。
     * @param cfg 赛季配置
     * @param exp 累计经验
     * @return 可领取的最高等级
     */
    private int level(JsonNode cfg,long exp) {
        int level=0;
        for (JsonNode threshold:cfg.path("grade")) { if(exp<threshold.asLong())break; level++; }
        return level;
    }

    /**
     * 读取本期累计有效充值原口径。
     * @param id 角色编号
     * @param start 双周起始秒数
     * @return 协议充值进度
     */
    private long paid(String id,long start) {
        return payments.rechargeSum(id,start*1000,(start+14L*86400)*1000)*10L;
    }

    /**
     * 生成 S804 本期免费与付费领奖及兑换状态。
     * @param id 角色编号
     * @param start 当前赛季起始
     * @return 原协议快照
     */
    public Map<String,Object> panel(String id,long start) {
        int season=season(start); JsonNode cfg=seasons.required(Integer.toString(season));
        Map<String,Integer> actions=actions(id,start); long experience=actions.values().stream().mapToLong(Integer::longValue).sum();
        boolean unlocked=actions.containsKey("unlock"); List<Map<String,Integer>> record=new ArrayList<>(), convert=new ArrayList<>();
        for(int i=1;i<=cfg.path("grade").size();i++) if(actions.containsKey((unlocked?"honor:":"free:")+i))record.add(pair(i,1));
        for(int i=1;i<=cfg.path("shop").size();i++) {
            int n=0;while(actions.containsKey("shop:"+i+":"+(n+1)))n++;
            if(n>0) convert.add(pair(i,n));
        }
        Map<String,Object> panel=new LinkedHashMap<>();panel.put("season",season);panel.put("flag",unlocked?1:0);panel.put("level",level(cfg,experience));
        DateTimeFormatter clock=DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/mm/ss").withZone(ZONE);
        panel.put("opentime",clock.format(java.time.Instant.ofEpochSecond(start)));
        panel.put("endtime",clock.format(java.time.Instant.ofEpochSecond(start+14L*86400-1)));
        panel.put("exp",experience);panel.put("progress",paid(id,start));panel.put("record",record);panel.put("convert",convert);return panel;
    }

    /**
     * 编码原档位及次数。
     * @param id 档位
     * @param n 次数
     * @return 字段对
     */
    private Map<String,Integer> pair(int id,int n) {
        Map<String,Integer> row=new LinkedHashMap<>();row.put("sid",id);row.put("num",n);return row;
    }

    /**
     * 通过同一事务提交客户端请求，防止旧赛季预览与重放造成二次扣费。
     * @param id 角色主键
     * @param displayed 已展示赛季起始秒数
     * @param cmd 请求编号
     * @param arg 档位、兑换序号或购买级数
     * @param auto 是否领取全部已达标档位
     * @param receipt 连接和请求组成的唯一凭据
     * @return 原协议错误码
     */
    @Transactional
    public int act(String id,long displayed,int cmd,long arg,boolean auto,String receipt) {
        if(roles.lockRole(id).level<30 || displayed!=start())return 1;
        Map<String,Integer> history=actions(id,displayed);
        if(history.containsKey("req:"+receipt))return 100011;
        JsonNode cfg=seasons.required(String.valueOf(season(displayed)));
        long exp=history.values().stream().mapToLong(Integer::longValue).sum(); int level=level(cfg,exp);
        int max=cfg.path("grade").size();
        if(cmd==227) {
            if(history.containsKey("unlock"))return 1;
            if(paid(id,displayed)<cfg.path("recharge").get(1).asLong())return 100003;
            grant(id,displayed,"unlock",cfg.path("reward2"));
        } else if(cmd==228) {
            if(!auto && (arg<1||arg>level))return 1;
            boolean given=false;
            for(int i=1;i<=level;i++) {
                if(!auto && i!=arg)continue;
                if(!history.containsKey("free:"+i)) {grant(id,displayed,"free:"+i,cfg.path("reward").get(i-1));given=true;}
                if(history.containsKey("unlock")&&!history.containsKey("honor:"+i)) {grant(id,displayed,"honor:"+i,cfg.path("reward1").get(i-1));given=true;}
            }
            if(!given)return 1;
        } else if(cmd==229) {
            if(arg<1||arg>cfg.path("shop").size())return 1;
            JsonNode sale=cfg.path("shop").get((int)arg-1);int use=1;
            while(history.containsKey("shop:"+arg+":"+use))use++;
            if(use>sale.get(3).asInt())return 1;
            if(!inventory.spendMaterials(id,sale.get(1).asInt(),sale.get(2).asLong(),0,0))return 100012;
            String key="shop:"+arg+":"+use;
            grantOne(id,displayed,key,sale.get(4).asInt(),sale.get(5).asInt(),sale.get(6).asLong());record(id,displayed,key,0);
        } else if(cmd==230) {
            if(arg<1||arg>max-level)return 1;
            if(!inventory.spendMaterials(id,Collections.emptyMap(),0,cfg.path("cost").get(0).asLong()*arg))return 100012;
            int cap=cfg.path("grade").get(max-1).asInt();record(id,displayed,"req:"+receipt,(int)Math.min(cap-exp,cfg.path("cost").get(1).asLong()*arg));return 0;
        } else return 1;
        record(id,displayed,"req:"+receipt,0);return 0;
    }

    /**
     * 发放一个原奖档的全部奖励。
     * @param id 角色
     * @param start 轮次
     * @param key 奖档
     * @param row 平铺的奖品三元组
     */
    private void grant(String id,long start,String key,JsonNode row) {
        for(int i=0;i<row.size();i+=3)grantOne(id,start,key+":"+i,row.get(i).asInt(),row.get(i+1).asInt(),row.get(i+2).asLong());
        record(id,start,key,0);
    }

    /**
     * 按真实资产类型入账。
     * @param id 角色
     * @param start 轮次
     * @param key 来源
     * @param type 资产种类
     * @param asset 模板
     * @param quantity 数量
     */
    private void grantOne(String id,long start,String key,int type,int asset,long quantity) {
        switch(type) {
            case 1: inventory.grantItemsOrMail(id,"passport:"+start+":"+key,Collections.singletonMap(asset,quantity));break;
            case 104: roles.addCurrency(id,0,quantity,0);break;
            case 6: dresses.grantDress(id,asset);break;
            case 120:
            case 125:
                if(jdbc.update("UPDATE local_passport_cosmetic SET quantity=quantity+? WHERE role_id=? AND kind=? AND asset=?",quantity,id,type,asset)==0)
                    jdbc.update("INSERT INTO local_passport_cosmetic(role_id,kind,asset,quantity) VALUES(?,?,?,?)",id,type,asset,quantity);
                break;
            default: throw new IllegalStateException("未知的通行证资产类别 "+type);
        }
    }

    /**
     * 返回已到账的时装币。
     * @param id 角色主键
     * @return 时装币余额
     */
    public long fashionCoins(String id) {
        long earned = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM local_passport_cosmetic WHERE role_id=? AND kind=125",Long.class,id);
        return Math.addExact(earned, inventory.currencyBalance(id, com.pokemon.offline.inventory.api.entity.SystemMailReward.FASHION_COINS));
    }

    /**
     * 原任务按每日、自然周或整个赛季重置。
     * @param start 赛季
     * @param group 类型
     * @return 计数窗口起点
     */
    private long taskStart(long start,int group) {
        LocalDate today=LocalDate.now(ZONE);
        if(group==2)return start;
        LocalDate date=group==0?today:today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return Math.max(start,date.atStartOfDay(ZONE).toEpochSecond());
    }

    /**
     * 查询原客户端本赛季十六项任务状态。
     * @param id 角色
     * @param start 赛季起始
     * @return S100 或 S101 任务数据
     */
    public List<Map<String,Object>> tasks(String id,long start) {
        JsonNode cfg=seasons.required(String.valueOf(season(start)));Map<String,Integer> history=actions(id,start);
        List<Map<String,Object>> list=new ArrayList<>();
        for(int group=0;group<3;group++) {
            long from=taskStart(start,group);
            for(JsonNode row:cfg.path(group==0?"task":"task"+group)) {
                int task=row.asInt();JsonNode rule=missions.required(String.valueOf(task));
                long value=rule.get(1).asLong();
                int state=history.containsKey("task:"+task+":"+from)?3:2;
                Map<String,Object> item=new LinkedHashMap<>();item.put("id",task);item.put("progress",value);item.put("state",state);list.add(item);
            }
        }
        return list;
    }

    /**
     * 验证并领取当前任务所给的通行证经验。
     * @param id 角色编号
     * @param task 任务编号
     * @param displayed 连接展示的赛季
     * @return 实际所得经验，负数代表已领或未达标
     */
    @Transactional
    public int claimTask(String id,int task,long displayed) {
        if(roles.lockRole(id).level<30||displayed!=start())return -1;
        JsonNode cfg=seasons.required(String.valueOf(season(displayed)));
        for(int group=0;group<3;group++)for(JsonNode row:cfg.path(group==0?"task":"task"+group)) {
            if(row.asInt()!=task)continue;
            long from=taskStart(displayed,group);String key="task:"+task+":"+from;
            Map<String,Integer> history=actions(id,displayed);JsonNode rule=missions.required(String.valueOf(task));
            if(history.containsKey(key))return -1;
            int total=history.values().stream().mapToInt(Integer::intValue).sum();
            int exp=Math.max(0,Math.min(rule.get(2).asInt(),cfg.path("grade").get(cfg.path("grade").size()-1).asInt()-total));
            record(id,displayed,key,exp);return exp;
        }
        return -1;
    }
}
