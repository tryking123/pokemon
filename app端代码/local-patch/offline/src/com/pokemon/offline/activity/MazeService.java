package com.pokemon.offline.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 宝可梦迷宫的地图、每周成绩、商店与结算 Service。
 * @author tongruiyuan
 * @since 2026-11-20 13:25
 */
@Service
public class MazeService {
    public static final int ACTIVITY = 10051;
    private final JsonNode rules;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ActivityService calendar;

    /** 读取本地迷宫配置并建立角色、动作与周榜存档。
     * @param jdbc 设备或 PC 的数据库
     * @param json 存档序列化器
     * @param inventory 钱包、背包与邮件
     * @param roles 实际角色
     * @param calendar 活动周期
     * @throws IOException 无法读取配置
     */
    public MazeService(JdbcTemplate jdbc,ObjectMapper json,InventoryService inventory,RoleService roles,ActivityService calendar)throws IOException{
        this.jdbc=jdbc;this.json=json;this.inventory=inventory;this.roles=roles;this.calendar=calendar;
        try(InputStream in=new ClassPathResource("activity/maze.json").getInputStream()){rules=json.readTree(in);}
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_maze_board(role_id VARCHAR(64) NOT NULL,season BIGINT NOT NULL,payload TEXT NOT NULL,score INTEGER NOT NULL,scored_at BIGINT NOT NULL,settled INTEGER NOT NULL,PRIMARY KEY(role_id,season))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_maze_action(role_id VARCHAR(64) NOT NULL,receipt VARCHAR(96) NOT NULL,PRIMARY KEY(role_id,receipt))");
    }

    /** 读取规则整数。 */
    private int n(String name){return rules.get(name).asInt();}

    /** 活动期信息。
     * @return 当前北京周及最后一小时展示时段
     */
    public ActivityPeriod period(){return calendar.cycleAt(ACTIVITY,Instant.now().getEpochSecond());}

    /** 等待本周最后一笔动作提交后再产生名次。 */
    private void lockWeek(){jdbc.queryForObject("SELECT starts_at FROM local_activity_calendar WHERE id=1 FOR UPDATE",Long.class);}

    /** 现有存档或新周起点。 */
    private ObjectNode load(String role,long season,long day){
        List<String> found=jdbc.queryForList("SELECT payload FROM local_maze_board WHERE season=? AND role_id=?",String.class,season,role);
        ObjectNode s;
        if(found.isEmpty()) s=json.createObjectNode().put("grid",1).put("floor",1).put("score",0).put("revision",0);
        else try{s=(ObjectNode)json.readTree(found.get(0));}catch(IOException ex){throw new IllegalStateException("迷宫存档异常",ex);}
        if(s.path("day").asLong(-1)!=day){s.put("day",day).put("left",n("free")).put("paid",0);}
        return s;
    }

    /** 在角色锁和周锁内更新完整进度。 */
    private void save(String role,long season,ObjectNode s){
        long when=s.path("scoredAt").asLong();
        if(jdbc.update("UPDATE local_maze_board SET payload=?,score=?,scored_at=? WHERE role_id=? AND season=?",s.toString(),s.path("score").asInt(),when,role,season)==0)
            jdbc.update("INSERT INTO local_maze_board(role_id,season,payload,score,scored_at,settled) VALUES(?,?,?,?,?,0)",role,season,s.toString(),s.path("score").asInt(),when);
    }

    /** 计算该层固定落格，旧存档可用相同周与层数复算。 */
    private ObjectNode tile(long season,int floor,int pos){
        ObjectNode row=json.createObjectNode().put("grid",pos).put("class",0);
        if(pos==8 || pos==16) row.put("class",2);
        if(pos==4 || pos==12 || pos==20){
            JsonNode pool=rules.get("tileRewards");
            int offset=(int)Math.floorMod(season/604800L+floor+(pos-4)/8,(long)pool.size());
            JsonNode reward=pool.get(offset);
            row.put("class",1).put("subclass_itype",reward.get(0).asInt()).put("subclass_id",reward.get(1).asInt()).put("subclass_num",reward.get(2).asInt());
        }
        return row;
    }

    /** 输出原控件的全部二十三个格子。 */
    private ArrayNode map(long season,int floor){
        ArrayNode cells=json.createArrayNode();for(int i=1;i<=n("cells");i++)cells.add(tile(season,floor,i));return cells;
    }

    /** 返回固定点数价格、当日剩余次数及地图状态。 */
    private ObjectNode mapInfo(long season,ObjectNode s){
        ObjectNode body=json.createObjectNode().put("errno",0).put("cur_grid",s.path("grid").asInt()).put("cost_num",n("fixedPrice"))
                .put("cur_customs",s.path("floor").asInt()).put("my_consumption",s.path("score").asInt()).put("cur_throw_num",s.path("left").asInt())
                .put("max_throw_num",n("free")).put("normal_cost_num",n("normalPrice")).put("add_integral",n("stepPoints"));
        body.set("map",map(season,s.path("floor").asInt()));return body;
    }

    /** 构造商店原货架，累计积分判资格，钻石付商品实价。 */
    private ObjectNode shop(ObjectNode s){
        ObjectNode body=json.createObjectNode().put("errno",0).put("my_consumption",s.path("score").asInt());
        ArrayNode rows=body.putArray("shop");int i=0;
        for(JsonNode rule:rules.get("offers")) {
            ObjectNode row=rows.addObject().put("integral",rule.get(0).asInt()).put("cost_num",rule.get(1).asInt()).put("limit",rule.get(2).asInt())
                    .put("discount",10).put("buy_num",s.path("buys").path(Integer.toString(++i)).asInt());
            row.putArray("reward").add(1).add(rule.get(3).asInt()).add(rule.get(4).asInt());
        }
        return body;
    }

    /** 真实角色榜单，同分先达到者优先。 */
    private List<ObjectNode> ranks(long season){
        return jdbc.query("SELECT e.role_id,r.name,r.sex,r.level,r.avatar,r.realm_id FROM local_maze_board e JOIN local_game_role r ON e.role_id=r.id "
                +"WHERE e.season=? AND e.score>=? ORDER BY e.score DESC,e.scored_at,e.role_id LIMIT 10",(rs,i)->{
                    ObjectNode entry=json.createObjectNode().put("uid",rs.getString(1)).put("name",rs.getString(2)).put("sex",rs.getInt(3))
                            .put("level",rs.getInt(4)).put("icon",rs.getInt(5)).put("dress",0).put("serverid",rs.getInt(6));
                    entry.set("reward",rules.get("rankAwards").get(i==0?0:i<3?1:2));return entry;},season,n("rankMinimum"));
    }

    /** 返回本周个人积分、前十成绩及各名次附件。 */
    private ObjectNode leaderboard(String role,long season,ObjectNode state){
        ObjectNode body=json.createObjectNode().put("errno",0).put("my_consumption",state.path("score").asInt()).put("inrank",0)
                .put("three_num",n("rankMinimum")).put("ten_num",n("rankMinimum"));
        ArrayNode rows=body.putArray("info");
        for(ObjectNode rank:ranks(season)){rows.add(rank);if(role.equals(rank.path("uid").asText()))body.put("inrank",1);}
        return body;
    }

    /** 结算完成周的实际成绩，邮件和完成标记在同一事务写入。
     * @param role 接收角色
     * @return 新产生的邮件数量
     */
    @Transactional
    public int settle(String role){
        roles.lockRole(role);lockWeek();long now=Instant.now().getEpochSecond();
        ActivityPeriod p=calendar.cycleAt(ACTIVITY,now);
        long through=now>=p.end?p.start:p.start-1;
        List<Long> seasons=jdbc.queryForList("SELECT season FROM local_maze_board WHERE role_id=? AND season<=? AND settled=0",Long.class,role,through);
        int sent=0;
        for(long season:seasons){
            int rank=0;
            for(ObjectNode row:ranks(season)){
                rank++;
                if(!role.equals(row.path("uid").asText()))continue;
                List<Integer> award=new ArrayList<>();for(JsonNode v:row.get("reward"))award.add(v.asInt());
                inventory.createRewardMail(role,"maze:"+season+":"+role,"宝可梦迷宫周榜","本周训练师获得第"+rank+"名，奖励已附于本邮件。",now,now+30L*86400,award);sent++;break;
            }
            jdbc.update("UPDATE local_maze_board SET settled=1 WHERE role_id=? AND season=?",role,season);
        }
        return sent;
    }

    /** 生产箱子面板实际可开次数与积分。 */
    private ObjectNode box(){return json.createObjectNode().put("open_integral",n("boxPoints")).put("open_cost",0).put("all_open_num",1).put("open_num",0);}

    /** 发放道具。背包放不下时使用可实际领取的奖励邮件。 */
    private void grant(String role,String receipt,int item,int amount,ObjectNode out){
        long direct=inventory.grantItemsOrMail(role,"maze:"+receipt,Collections.singletonMap(item,(long)amount)).getOrDefault(item,0L);
        if(direct>0)out.with("adds").putArray("items").addObject().put("id",item).put("n",direct);
        if(direct<amount)out.put("mailed",true);
    }

    /** 按格子、骰子、体力及周资格结算一次已展示的动作。
     * @param role 角色编号
     * @param protocol 原接口编号
     * @param req 原请求
     * @param seen 当前连接看过的周起点
     * @param revision 当前连接最近看到的地图版本
     * @param receipt 动作唯一凭据
     * @return 原协议应答及已发生的展示变更
     */
    @Transactional
    public ObjectNode act(String role,int protocol,JsonNode req,long seen,int revision,String receipt){
        roles.lockRole(role);lockWeek();long now=Instant.now().getEpochSecond();
        ActivityPeriod p=calendar.cycleAt(ACTIVITY,now);
        ObjectNode s=load(role,p.start,Math.floorDiv(now+28800,86400));
        ObjectNode out=json.createObjectNode().put("season",p.start).put("revision",s.path("revision").asInt());
        if(protocol==10028){out.set("reply",mapInfo(p.start,s));if(s.path("box").asBoolean())out.set("box",box());return out;}
        if(protocol==10026){out.set("reply",leaderboard(role,p.start,s));return out;}
        if(protocol==10027){out.set("reply",shop(s));return out;}
        ObjectNode reply=out.putObject("reply").put("errno",2002);
        if(now<p.start || now>=p.end || seen!=p.start || revision!=s.path("revision").asInt()
                || jdbc.queryForObject("SELECT COUNT(*) FROM local_maze_action WHERE role_id=? AND receipt=?",Integer.class,role,receipt)>0)return out;
        int cost=0;
        switch(protocol){
        case 10029:
            if(!roll(role,p.start,req,s,reply,out,receipt))return out;break;
        case 10030:
            if(!s.path("box").asBoolean())return out;
            s.put("box",false).put("score",s.path("score").asInt()+n("boxPoints"));
            JsonNode award=rules.get("boxReward");grant(role,receipt,award.get(1).asInt(),award.get(2).asInt(),out);
            out.with("adds").put("maze",n("boxPoints"));
            break;
        case 10031:
            if(s.path("left").asInt()>0 || s.path("paid").asInt()>=n("dailyPaidLimit"))return out;
            cost=n("normalPrice");if(!inventory.spendMaterials(role,0,0,0,cost))return out;
            s.put("paid",s.path("paid").asInt()+1).put("left",1);break;
        case 10032:
            int idx=req.path("index").asInt();
            if(idx<1 || idx>rules.get("offers").size())return out;
            JsonNode r=rules.get("offers").get(idx-1);ObjectNode bought=s.with("buys");String key=Integer.toString(idx);
            if(s.path("score").asInt()<r.get(0).asInt() || bought.path(key).asInt()>=r.get(2).asInt())return out;
            cost=r.get(1).asInt();if(!inventory.spendMaterials(role,0,0,0,cost))return out;
            grant(role,receipt,r.get(3).asInt(),r.get(4).asInt(),out);bought.put(key,bought.path(key).asInt()+1);break;
        default:return out;
        }
        if(cost>0)out.put("wallet",true);
        if(protocol==10029 || protocol==10030)s.put("scoredAt",System.currentTimeMillis());
        s.put("revision",s.path("revision").asInt()+1);save(role,p.start,s);
        out.put("revision",s.path("revision").asInt());reply.put("errno",0);
        jdbc.update("INSERT INTO local_maze_action(role_id,receipt) VALUES(?,?)",role,receipt);
        return out;
    }

    /** 只给实际走过的步数计分，在终点舍去剩余点数。 */
    private boolean roll(String role,long season,JsonNode req,ObjectNode s,ObjectNode reply,ObjectNode out,String receipt){
        boolean fixed=req.has("throw_num");int dice=fixed?req.path("throw_num").asInt():ThreadLocalRandom.current().nextInt(1,7);
        if(dice<1 || dice>6 || (fixed && !req.path("throw_num").canConvertToInt()))return false;
        if(fixed){
            if(s.path("paid").asInt()>=n("dailyPaidLimit") || !inventory.spendMaterials(role,0,0,0,n("fixedPrice")))return false;
            s.put("paid",s.path("paid").asInt()+1);out.put("wallet",true);
        }else{
            if(s.path("left").asInt()<=0)return false;
            s.put("left",s.path("left").asInt()-1);
        }
        s.put("box",false);
        int old=s.path("grid").asInt(),position=Math.min(n("cells"),old+dice),floor=s.path("floor").asInt();
        int score=s.path("score").asInt()+(position-old)*n("stepPoints");
        if(position==n("cells")){
            floor++;position=1;score+=n("floorPoints");reply.set("new_map",map(season,floor));
        } else{
            JsonNode cell=tile(season,floor,position);
            if(cell.path("class").asInt()==1)grant(role,receipt,cell.path("subclass_id").asInt(),cell.path("subclass_num").asInt(),out);
            if(cell.path("class").asInt()==2){s.put("box",true);out.set("box",box());}
        }
        s.put("floor",floor).put("grid",position).put("score",score);
        reply.put("throw_num",dice).put("my_consumption",score).put("cur_customs",floor);
        return true;
    }
}
