package com.pokemon.local.battle;

import org.springframework.core.io.ClassPathResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.pokemon.local.activity.LuckyBagService;
import com.pokemon.local.home.HomeService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宝可梦巢穴的每日难度、二维地图、连续战斗和一次性掉落。
 * @author tongruiyuan
 * @since 2026-11-19 22:07
 */
@Service
public class NestService {
    private final FixedStageCombatService combat;
    private final RoleService roles;
    private final HomeService receipts;
    private final InventoryService bag;
    private final LuckyBagService lucky;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode den,rosters;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    /** 加载巢穴规则和原怪物面板。
     * @param combat 固定挑战战斗
     * @param roles 角色状态
     * @param receipts 请求回执
     * @param bag 背包
     * @param lucky 节日福袋活动进度
     * @param jdbc 存档
     * @param json 编解码
     */
    public NestService(FixedStageCombatService combat,RoleService roles,HomeService receipts,InventoryService bag,LuckyBagService lucky,JdbcTemplate jdbc,ObjectMapper json) {
        this.combat=combat;this.roles=roles;this.receipts=receipts;this.bag=bag;this.lucky=lucky;this.jdbc=jdbc;this.json=json;
        try(InputStream map=new ClassPathResource("battle/nest.json").getInputStream();
            InputStream npcs=new ClassPathResource("battle/nest-rosters.json").getInputStream()) {
            den=json.readTree(map);rosters=json.readTree(npcs);
        } catch(IOException e) {throw new IllegalStateException("巢穴资源无效",e);}
    }

    /** 读取持久对象。
     * @param value 存档文本
     * @return 游戏状态
     */
    private ObjectNode decode(String value) {
        try{return (ObjectNode)json.readTree(value);}catch(IOException e){throw new IllegalStateException("巢穴存档损坏",e);}
    }

    /** 保存角色巢穴进度。
     * @param id 角色编号
     * @param s 当前状态
     */
    private void save(String id,ObjectNode s) {
        jdbc.update("MERGE INTO local_nest_state(role_id,state_json) KEY(role_id) VALUES(?,?)",id,s.toString());
    }

    /** 当日 12/4/2 个入口按洗牌分配难度。
     * @param id 角色编号
     * @return 新旧存档
     */
    private ObjectNode state(String id) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_nest_state WHERE role_id=?",String.class,id);
        ObjectNode s=rows.isEmpty()?json.createObjectNode():decode(rows.get(0));
        long day=LocalDate.now(ZONE).toEpochDay();
        if(s.path("day").asLong()!=day) {
            s.removeAll();
            s.put("day",day).put("used",0);
            List<Integer> grades=new ArrayList<>();
            for(int i=0;i<18;i++) grades.add(i<12?1:i<16?2:3);
            Collections.shuffle(grades);
            s.set("grades",json.valueToTree(grades));
        }
        return s;
    }

    /** 构造原入口。
     * @param s 角色状态
     * @param sid 属性巢穴号
     * @return 入口快照
     */
    private ObjectNode mission(ObjectNode s,int sid) {
        int grade=s.path("grades").get(sid-1).asInt();
        int sample=den.path(String.valueOf(sid)).path("monster1").get(0).asInt();
        JsonNode actor=rosters.path(String.valueOf(grade)).path(String.valueOf(sample)).path("actors").path(0);
        int power=0;
        for(String field:Arrays.asList("hp_max","physical_attack","special_attack","physical_defense","special_defense","speed")) power+=actor.path(field).asInt();
        return json.createObjectNode().put("sid",sid).put("difficulty",grade).put("state",s.path("sid").asInt()==sid?1:0).put("power",power);
    }

    /** 查看十八个入口和次数。
     * @param s 存档
     * @param reply 原响应
     */
    private void list(ObjectNode s,ObjectNode reply) {
        ArrayNode missions=reply.putArray("nest_mission");
        for(int i=1;i<=18;i++) missions.add(mission(s,i));
        reply.put("num",3-s.path("used").asInt());
    }

    /** 随机选取本巢原池成员。
     * @param pool 原候选数组
     * @return 已选 ID
     */
    private int pick(JsonNode pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size())).asInt();
    }

    /** 生成 25 个原客户端格位，一起冻结敌人和拾取物。
     * @param s 存档
     * @param sid 巢穴号
     */
    private void enter(ObjectNode s,int sid) {
        JsonNode rule=den.path(String.valueOf(sid));
        List<ObjectNode> cells=new ArrayList<>();
        for(int kind=1;kind<=3;kind++) for(int j=0;j<rule.path("monsternum").get(kind-1).asInt();j++) {
            cells.add(json.createObjectNode().put("id",pick(rule.path("monster"+kind))).put("dead",1));
        }
        Collections.shuffle(cells);
        cells.get(0).put("power",1);
        cells.get(1).put("reply",1);
        int difficulty=mission(s,sid).path("difficulty").asInt();
        for(int j=0;j<3;j++) cells.get(2+j).put("special",pick(rule.path("event"+difficulty)));
        Collections.shuffle(cells);
        cells.add(12,json.createObjectNode().put("me",1));
        s.remove(Arrays.asList("fight","hp","target","buff","teamHp"));
        s.put("sid",sid).put("strength",8).put("pos",13).put("used",s.path("used").asInt()+1);
        s.putArray("reward");
        s.set("map",json.valueToTree(cells));
    }

    /** 返回原 S2045 重连数据。
     * @param id 角色
     * @return 快照或空值
     */
    public ObjectNode current(String id) {
        ObjectNode s=state(id);
        if(!s.has("sid")) return null;
        ObjectNode out=json.createObjectNode().put("strength",s.path("strength").asInt());
        out.set("nest_mission",mission(s,s.path("sid").asInt()));
        out.set("nest_monster",s.get("map"));out.set("reward",s.get("reward"));
        return out;
    }

    /** 为原导航上方的精灵显示连续战斗的剩余生命。
     * @param roleId 当前角色
     * @return 原队伍生命列表
     */
    public ObjectNode teamHp(String roleId) {
        ObjectNode s=state(roleId),result=json.createObjectNode().put("teamid",1);
        result.set("detail",s.has("teamHp")?s.get("teamHp"):json.createArrayNode());
        return result;
    }

    /** 查询当前角色可进行的巢穴次数。
     * @param roleId 角色编号
     * @return 达到门槛后的剩余次数
     */
    public int remaining(String roleId) {
        return roles.findById(roleId).filter(role->role.level>=85)
                .map(role->Math.max(0,3-state(roleId).path("used").asInt())).orElse(0);
    }

    /** 获取是否正在巢穴战斗。
     * @param id 角色
     * @return 有效战斗
     */
    public boolean fighting(String id) {return state(id).has("fight");}

    /** 返回错误或首次成功的原回执。
     * @param id 角色
     * @param cmd 命令编号
     * @param arg 巢穴号或方向
     * @param receipt 请求标识
     * @return 原响应与内部推送
     */
    @Transactional
    public ObjectNode handle(String id,int cmd,long arg,String receipt) {
        LocalRole role=roles.lockRole(id);
        ObjectNode fail=json.createObjectNode().put("errno",100021);
        if(role.level<85) return fail;
        ObjectNode s=state(id),out=json.createObjectNode().put("errno",0);
        String key="nest:"+cmd+":"+receipt;
        String previous=receipts.reply(id,key);
        if(previous!=null) {
            ObjectNode old=decode(previous);
            return old.path("arg").asLong()==arg?(ObjectNode)old.get("reply"):fail;
        }
        if(cmd==10038) {
            list(s,out);
        } else if(cmd==10039 && arg>=1 && arg<=18 && !s.has("sid") && s.path("used").asInt()<3) {
            enter(s,(int)arg);out.set("nest_monster",s.get("map").deepCopy());out.put("_strength",8);
        } else if(cmd==10042 && arg>=1 && arg<=18 && !s.has("sid")) {
            if(!bag.spendMaterials(id,8004,1,0,0) && !bag.spendMaterials(id,Collections.emptyMap(),0,100)) return fail;
            roll(s,(int)arg);out.set("nest_mission",mission(s,(int)arg));
        } else if(cmd==10041 && s.has("sid")) {
            roll(s,s.path("sid").asInt());
            s.remove(Arrays.asList("sid","map","reward","fight","target","hp","teamHp","buff","pos","strength"));
            list(s,out);out.set("_buff",buffList(s));
        } else if(cmd==10040 && arg>=1 && arg<=4 && s.has("sid")) {
            if(!move(role,s,(int)arg,out)) return fail;
        } else return fail;
        save(id,s);
        if(cmd!=10038) {
            ObjectNode wire=out.deepCopy();wire.remove(Arrays.asList("_strength","_event","_adds","_buff"));
            ObjectNode log=json.createObjectNode().put("arg",arg);log.set("reply",wire);
            receipts.record(id,key,log.toString());
        }
        return out;
    }

    /** 退出或手动刷新时重新抽取原难度。
     * @param s 原状态
     * @param sid 巢穴号
     */
    private void roll(ObjectNode s,int sid) {
        int value=ThreadLocalRandom.current().nextInt(9000);
        ((ArrayNode)s.get("grades")).set(sid-1,json.getNodeFactory().numberNode(value<6000?1:value<8000?2:3));
    }

    /** 按原方向邻接移动；未清除的怪物开启权威战斗。
     * @param role 角色
     * @param s 存档
     * @param direction 上下左右
     * @param out 响应
     * @return 目标格可用
     */
    private boolean move(LocalRole role,ObjectNode s,int direction,ObjectNode out) {
        int pos=s.path("pos").asInt(),dest=direction==1?pos-5:direction==2?pos+5:direction==3?pos-1:pos+1;
        if(dest<1 || dest>25 || direction>=3 && (pos-1)/5!=(dest-1)/5) return false;
        ObjectNode cell=(ObjectNode)s.path("map").get(dest-1);
        int sid=s.path("sid").asInt(),difficulty=s.path("grades").get(sid-1).asInt();
        if(s.has("fight")) {
            if(s.path("target").asInt()!=dest) return false;
            writeFight((ObjectNode)s.get("fight"),out);
            return true;
        }
        int enemy=cell.path("id").asInt();
        if(enemy==0 || cell.path("dead").asInt()==3) {
            relocate(s,dest);
            if(cell.has("special")) {
                int item=cell.remove("special").asInt();
                out.putObject("_event").put("sid",sid).put("grid",dest).put("itype",4);
                if(item<=4) {
                    int[] buffIds={0,7003,7000,7001,7002};
                    s.with("buff").put(String.valueOf(buffIds[item]),true);
                    out.set("_buff",buffList(s));
                } else grant(role.id,s,dest,item,out);
            }
            return true;
        }
        if(s.path("strength").asInt()<=0) return false;
        JsonNode cfg=rosters.path(String.valueOf(difficulty)).path(String.valueOf(enemy));
        if(!cfg.path("actors").isArray()) return false;
        ObjectNode fight=combat.start(role,sid,cfg);
        if(fight.path("errno").asInt()!=0) return false;
        if(cell.has("power") || cell.has("reply")) {
            boolean energy=cell.has("power");
            ObjectNode ev=out.putObject("_event").put("sid",sid).put("grid",dest).put("itype",energy?1:2);
            if(energy) s.put("strength",s.path("strength").asInt()+5);
            else s.remove("hp");
            cell.remove(energy?"power":"reply");
        }
        int alive=0;
        for(JsonNode node:fight.path("player").path("pokemon")) {
            ObjectNode actor=(ObjectNode)node;
            String uid=actor.path("id").asText();
            int hp=Math.min(actor.path("hp_max").asInt(),s.path("hp").path(uid).asInt(Integer.MAX_VALUE));
            actor.put("hp",hp);
            if(hp>0) alive++;
            for(String field:Arrays.asList("crit_chance","dodge_chance","add_skill_point_limit")) {
                int buff=field.equals("crit_chance")?7001:field.equals("dodge_chance")?7002:7003;
                if(s.path("buff").has(String.valueOf(buff))) {
                    actor.put(field,actor.path(field).asInt()+(buff==7003?10:800));
                    if(buff==7003) actor.put("sp",Math.min(110,actor.path("sp").asInt()+10));
                }
            }
        }
        if(alive==0) return false;
        s.put("strength",s.path("strength").asInt()-1).put("target",dest);
        s.set("fight",fight);
        lucky.participate(role.id,61002);
        out.put("_strength",s.path("strength").asInt());
        writeFight(fight,out);
        return true;
    }

    /** 裁剪原战斗响应保留的双方出场字段。
     * @param fight 本地战斗状态
     * @param out 开战响应
     */
    private void writeFight(ObjectNode fight,ObjectNode out) {
        out.put("seed",fight.path("seed").asInt()).put("weather",0);
        for(String field:Arrays.asList("player","monster")) {
            ObjectNode side=fight.get(field).deepCopy();
            for(JsonNode actor:side.path("pokemon")) ((ObjectNode)actor).remove(Arrays.asList("skill","targetLimit","physical","goldCarry", "homeStatues","baseDamage","type","bigger"));
            out.set(field,side);
        }
    }

    /** 设置当前唯一位置。
     * @param s 存档
     * @param pos 目标格，一基
     */
    private void relocate(ObjectNode s,int pos) {
        ((ObjectNode)s.path("map").get(s.path("pos").asInt()-1)).remove("me");
        ((ObjectNode)s.path("map").get(pos-1)).put("me",1);
        s.put("pos",pos);
    }

    /** 推进巢穴战斗，以精灵剩余生命判断终局。
     * @param id 当前角色
     * @param skill 技能
     * @param targets 目标
     * @param flag 动作标识
     * @param flee 是否撤退
     * @return 已发生的战斗及奖励事件
     */
    @Transactional
    public ObjectNode advance(String id,int skill,List<?> targets,int flag,boolean flee) {
        roles.lockRole(id);
        ObjectNode s=state(id),fight=(ObjectNode)s.get("fight"),out=json.createObjectNode();
        if(fight==null) return out;
        int result=combat.advance(fight,skill,targets,flag,flee);
        if(result<0) return out;
        if(!flee && s.path("buff").has("7000")) {
            for(JsonNode node:fight.path("player").path("pokemon")) {
                ObjectNode actor=(ObjectNode)node;
                int hp=actor.path("hp").asInt(),max=actor.path("hp_max").asInt();
                if(hp>0) actor.put("hp",Math.min(max,hp+Math.max(1,max/20)));
            }
        }
        if(result>0) {
            int pos=s.path("target").asInt(),sid=s.path("sid").asInt();
            out.put("outcome",result);
            ObjectNode hp=s.with("hp");
            ArrayNode status=s.putArray("teamHp");
            for(JsonNode actor:fight.path("player").path("pokemon")) {
                String uid=actor.path("id").asText();
                hp.put(uid,actor.path("hp").asInt());
                status.addObject().put("uid",uid).put("maxhp",actor.path("hp_max").asInt()).put("crthp",actor.path("hp").asInt());
            }
            if(result==1) {
                ObjectNode cell=(ObjectNode)s.path("map").get(pos-1);
                cell.put("dead",3);
                relocate(s,pos);
                out.putObject("_event").put("sid",sid).put("grid",pos).put("itype",3);
                grant(id,s,pos,0,out);
            }
            s.remove(Arrays.asList("fight","target"));
        }
        save(id,s);
        return out;
    }

    /** 打包当前巢穴的原增益列表。
     * @param s 当前地图
     * @return 原 S111
     */
    private ObjectNode buffList(ObjectNode s) {
        ObjectNode snapshot=json.createObjectNode();
        ArrayNode buffs=snapshot.putArray("list");
        s.path("buff").fieldNames().forEachRemaining(key->buffs.addObject().put("id",Integer.parseInt(key))
                .put("amass",1).put("round",s.path("strength").asInt()));
        return snapshot.put("isNull",buffs.isEmpty());
    }

    /** 获取重连时需恢复的药剂。
     * @param roleId 当前角色
     * @return 原增益推送
     */
    public ObjectNode buffs(String roleId) {return buffList(state(roleId));}

    /** 发放本巢对应的残片、结晶或宝箱奖励。
     * @param id 角色
     * @param s 进度
     * @param pos 清除格号
     * @param event 零为敌人，非零为对应宝箱编号
     * @param out 结果
     */
    private void grant(String id,ObjectNode s,int pos,int event,ObjectNode out) {
        int sid=s.path("sid").asInt();
        JsonNode rule=den.path(String.valueOf(sid)),items=rule.path("rew");
        int ordinary=items.get(1).asInt(),rare=items.get(4).asInt(),band=items.get(7).asInt();
        int crystal=items.get(10+3*ThreadLocalRandom.current().nextInt(3)).asInt();
        Map<Integer,Long> reward=new LinkedHashMap<>();
        if(event==0) {
            int enemy=s.path("map").get(pos-1).path("id").asInt();
            int tier=1;
            for(int i=2;i<=3;i++) for(JsonNode entry:rule.path("monster"+i)) if(entry.asInt()==enemy) tier=i;
            reward.put(ordinary,(long)tier);
            if(tier==2) reward.put(crystal,1L);
            long rareCount=(tier==3?1:0)+s.path("grades").get(sid-1).asInt()-1;
            if(rareCount>0) reward.put(rare,rareCount);
        } else if(event==5) reward.put(ordinary,1L);
        else if(event==6) {reward.put(ordinary,2L);reward.put(crystal,1L);}
        else if(event==7) {reward.put(rare,1L);reward.put(band,1L);}
        if(LocalDate.now(ZONE).getDayOfWeek().getValue()>=6) reward.replaceAll((k,n)->n*2);
        String key="nest:"+s.path("day").asText()+":"+s.path("used").asText()+":"+pos+":"+event;
        ObjectNode adds=out.putObject("_adds");
        ArrayNode sent=adds.putArray("items");
        bag.grantItemsOrMail(id,key,reward).forEach((i,n)->sent.addObject().put("id",i).put("n",n));
        ((ArrayNode)s.get("reward")).add(adds.deepCopy());
    }
}
