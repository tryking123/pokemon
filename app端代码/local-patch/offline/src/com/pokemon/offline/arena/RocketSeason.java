package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;

/**
 * 火箭队周赛季、好友之外的本机真实排名、周任务和奖品账本。
 * @author tongruiyuan
 * @since 2026-11-20 12:45
 */
@Service
public class RocketSeason {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final long WEEK=604800L;
    private static final long CLOSE=6*86400L+22*3600L;
    private final JdbcTemplate jdbc;
    private final ArenaMapper store;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ObjectMapper json;
    final JsonNode rules;

    /**
     * 初始化随包赛季、排名、任务以及奖励表。
     * @param jdbc 角色数据库
     * @param store 竞技状态
     * @param inventory 资产账本
     * @param roles 角色锁和货币
     * @param json 随包配置解码器
     * @throws IOException 配置读取失败
     */
    RocketSeason(JdbcTemplate jdbc,ArenaMapper store,InventoryService inventory,RoleService roles,ObjectMapper json) throws IOException {
        this.jdbc=jdbc;this.store=store;this.inventory=inventory;this.roles=roles;this.json=json;
        try(InputStream input=new ClassPathResource("arena/rocket.json").getInputStream()) {rules=json.readTree(input);}
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_rocket_season(role_id VARCHAR(36) NOT NULL,week BIGINT NOT NULL,score BIGINT NOT NULL,rate INTEGER NOT NULL,stamp BIGINT NOT NULL,power BIGINT NOT NULL,PRIMARY KEY(role_id,week))");
    }

    /**
     * 获取当前周一北京时间零点。
     * @return Unix 秒数
     */
    public long week() {
        return LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZONE).toEpochSecond();
    }

    /**
     * 获取原客户端 98 期中的循环编号。
     * @param week 本周周一
     * @return 原始编号
     */
    int number(long week) {
        long origin=LocalDate.of(2026,11,16).atStartOfDay(ZONE).toEpochSecond();
        return 1+(int)Math.floorMod(Math.floorDiv(week-origin,WEEK),rules.path("seasonCount").asInt());
    }

    /**
     * 原周一19点起比赛，周日22点后封榜，23点开放奖励。
     * @param week 周一起点
     * @return 原客户端状态
     */
    int phase(long week) {
        long offset=Instant.now().getEpochSecond()-week;
        if(offset<19*3600)return 1;
        if(offset<CLOSE)return 2;
        return offset<CLOSE+3600?3:4;
    }

    /**
     * 读取当前北京自然日对应的独立次数状态键。
     * @return 原日键
     */
    String day() {return "rocket:day:"+LocalDate.now(ZONE).toEpochDay();}

    /**
     * 读取当前轮次的个人进度。
     * @param role 当前角色
     * @param week 周起点
     * @return 当前赛季状态
     */
    ObjectNode stats(String role,long week) {return store.load(role, "rocket:week:"+week);}

    /**
     * 保存本周其实发生的进度。
     * @param role 角色
     * @param week 本周
     * @param stats 当前赛季状态
     */
    void save(String role,long week,ObjectNode stats) {store.save(role,"rocket:week:"+week,stats);}

    /**
     * 确定可领取的最后一轮，周日结算期间覆盖旧奖。
     * @param week 本周
     * @return 可领取的赛季周一
     */
    private long claimWeek(long week) {
        int phase=phase(week);
        return phase==3? -1:phase==4?week:week-WEEK;
    }

    /**
     * 查询元老段位真实角色按积分及达到时刻的名次。
     * @param role 当前角色
     * @param week 赛季开始
     * @return 未上榜为零
     */
    int rank(String role,long week) {
        List<String> ids=jdbc.queryForList("SELECT role_id FROM local_rocket_season WHERE week=? AND rate=7 ORDER BY score DESC,stamp,role_id",String.class,week);
        return ids.indexOf(role)+1;
    }

    /**
     * 读取最近一轮有参与者的可领取排名。
     * @param role 当前角色
     * @param week 当前周
     * @return 含领取状态的原字段
     */
    ObjectNode previous(String role,long week) {
        ObjectNode out=json.createObjectNode().put("flag",0);
        long claim=claimWeek(week);
        if(claim<0)return out;
        ObjectNode stats=stats(role,claim);
        if(stats.path("played").asInt()>0)out.put("lastrank",rank(role,claim)).put("flag",stats.path("awarded").asBoolean()?1:0);
        return out;
    }

    /**
     * 结算一场完整车轮战的段位、积分、连胜负和真实榜单。
     * @param role 当前角色
     * @param week 周起点
     * @param match 冻结的匹配
     * @param win 是否胜利
     * @return S459 原结果
     */
    ObjectNode finish(LocalRole role,long week,ObjectNode match,boolean win) {
        ObjectNode stats=stats(role.id,week);
        int old=stats.path("rate").asInt(1), before=stats.path("played").asInt(), rate=old;
        JsonNode rule=rules.path("rates").get(String.valueOf(old));
        int delta=rule.path(win?"attackwin":"attacklose").asInt();
        if(win && before<rules.path("bonus").size())delta+=rules.path("bonus").get(before).asInt();
        int streak=win? Math.max(0,stats.path("streak").asInt())+1:Math.min(0,stats.path("streak").asInt())-1;
        if(win && rule.has("attackup") && streak>=rule.path("attackup").asInt()) {rate++;streak=0;}
        if(!win && rule.has("attackdown") && -streak>=rule.path("attackdown").asInt()) {rate--;streak=0;}
        long score=Math.max(0,stats.path("score").asLong()+delta), stamp=System.currentTimeMillis(), power=0;
        for(JsonNode team:match.path("allies"))power+=team.path("power").asLong();
        stats.put("rate",rate).put("best",Math.max(rate,stats.path("best").asInt(1))).put("streak",streak)
                .put("score",score).put("played",before+1).put("wins",stats.path("wins").asInt()+(win?1:0));
        jdbc.update("MERGE INTO local_rocket_season(role_id,week,score,rate,stamp,power) KEY(role_id,week) VALUES(?,?,?,?,?,?)",
                role.id,week,score,rate,stamp,power);
        ObjectNode history=store.load(role.id,"rocket:logs");
        ArrayNode logs=history.withArray("loglist");
        ObjectNode entry=json.createObjectNode().put("pos","attack").put("result",win?1:0).put("score",score).put("delta",delta)
                .put("rate",rate).put("time",stamp/1000).put("tuid",match.path("preview").path("tuid").asText())
                .put("svrid",1).put("name",match.path("preview").path("name").asText()).put("icon",1);
        logs.insert(0,entry);
        if(logs.size()>20)logs.remove(logs.size()-1);
        store.save(role.id,"rocket:logs",history);save(role.id,week,stats);
        return json.createObjectNode().put("result",win?1:0).put("score",score).put("rate",rate).put("rank",rank(role.id,week));
    }

    /**
     * 分页查询真正达到元老段位的本机角色。
     * @param role 当前角色
     * @param week 周起点
     * @param page 原请求页码
     * @param size 原请求页长
     * @return 原排行快照
     */
    ObjectNode ranking(LocalRole role,long week,int page,int size) {
        ObjectNode result=json.createObjectNode();
        if(page<1 || size<1 || size>100 || page>100000)return result.put("errno",1);
        ObjectNode stats=stats(role.id,week);
        result.put("errno",0).put("page",page).put("size",size).put("myscore",stats.path("score").asLong())
                .put("myrank",rank(role.id,week)).put("myrate",stats.path("rate").asInt(1));
        ArrayNode list=result.putArray("list");
        jdbc.query("SELECT r.role_id,g.name,g.realm_id,g.avatar,r.power,r.score,r.rate FROM local_rocket_season r JOIN local_game_role g ON g.id=r.role_id WHERE r.week=? AND r.rate=7 ORDER BY r.score DESC,r.stamp,r.role_id LIMIT ? OFFSET ?",
                row->{list.addObject().put("uid",row.getString(1)).put("name",row.getString(2)).put("svrid",row.getInt(3))
                        .put("icon",Math.max(1,row.getInt(4))).put("power",row.getLong(5)).put("score",row.getLong(6)).put("rate",row.getInt(7));},
                week,size,(page-1)*size);
        return result;
    }

    /**
     * 投影二十个原赛季任务。
     * @param role 当前角色
     * @param week 周起点
     * @return 原 S100/S101 任务行
     */
    ArrayNode tasks(String role,long week) {
        ObjectNode stats=stats(role,week);ArrayNode tasks=json.createArrayNode();
        for(int id=60001;id<=60020;id++) {
            JsonNode cfg=rules.path("tasks").path(String.valueOf(id));
            int kind=cfg.get(0).asInt(), target=cfg.get(1).asInt();
            int progress=stats.path(kind==55?"played":kind==56?"wins":"best").asInt();
            tasks.addObject().put("id",id).put("progress",Math.min(progress,target))
                    .put("state",stats.path("claimed").has(String.valueOf(id))?3:progress>=target?2:1);
        }
        return tasks;
    }

    /**
     * 领取一项本周达标的任务。
     * @param role 当前角色
     * @param week 已预览的赛季
     * @param id 原任务编号
     * @return 是否已发奖
     */
    boolean task(String role,long week,int id) {
        if(week!=week() || phase(week)!=2 || id<60001 || id>60020 || tasks(role,week).get(id-60001).path("state").asInt()!=2)return false;
        JsonNode rule=rules.path("tasks").path(String.valueOf(id));
        inventory.grantChip(role,rule.get(4).asInt(),rule.get(5).asLong());
        if(rule.get(2).asLong()>0)roles.addCurrency(role,0,rule.get(2).asLong(),0);
        ObjectNode stats=stats(role,week);stats.with("claimed").put(String.valueOf(id),true);save(role,week,stats);
        return true;
    }

    /**
     * 领取上赛季参加奖或真实名次对应的奖励。
     * @param role 当前角色
     * @param week 连接所展示赛季
     * @return 是否发奖
     */
    boolean claim(String role,long week) {
        long previous=claimWeek(week);
        if(week!=week() || previous<0)return false;
        ObjectNode stats=stats(role,previous);
        if(stats.path("played").asInt()==0 || stats.path("awarded").asBoolean())return false;
        int rank=rank(role,previous);
        JsonNode chosen=rules.path("awards").get(0);
        for(JsonNode award:rules.path("awards")) if(rank>0 && rank<=award.path("rank").asInt()) {chosen=award;break;}
        JsonNode items=chosen.path("items");Map<Integer,Long> goods=new LinkedHashMap<>();
        for(int i=0;i<items.size();i+=3) {
            int kind=items.get(i).asInt(),asset=items.get(i+1).asInt();long count=items.get(i+2).asLong();
            if(kind==1)goods.put(asset,count);
            else if(kind==3)inventory.grantChip(role,asset,count);
            else if(kind==104)roles.addCurrency(role,0,count,0);
            else if(kind==111) {
                ObjectNode wallet=store.load(role,"stats:"+ArenaService.ARENA);
                wallet.put("points",wallet.path("points").asLong()+count);store.save(role,"stats:"+ArenaService.ARENA,wallet);
            } else throw new IllegalStateException("火箭队奖励类型未配置 "+kind);
        }
        if(!goods.isEmpty())inventory.grantItemsOrMail(role,"rocket:"+previous,goods);
        stats.put("awarded",true);save(role,previous,stats);return true;
    }
}
