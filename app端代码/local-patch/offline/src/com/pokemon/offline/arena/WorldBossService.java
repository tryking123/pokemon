package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.battle.FixedStageCombatService;
import com.pokemon.offline.dailydup.DailyDungeonService;
import com.pokemon.offline.dojo.DojoStationService;
import com.pokemon.offline.elf.ElfTravelService;
import com.pokemon.offline.home.OrangeService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.space.SpaceService;
import com.pokemon.offline.tower.TowerService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 普通及大师世界首领的本机共享生命、真实伤害和周奖 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:54
 */
@Service
public class WorldBossService {
    private static final int NORMAL=9, MASTER=23, CITY=71002;
    private static final String FIGHT="attempt", MODE="world-boss";
    private final RoleService roles;
    private final ArenaMapper store;
    private final FixedStageCombatService combat;
    private final ActivityService calendar;
    private final RocketService rocket;
    private final InventoryService inventory;
    private final DailyDungeonService daily;
    private final SpaceService space;
    private final TowerService tower;
    private final OrangeService orange;
    private final ElfTravelService travel;
    private final DojoStationService station;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode roster, rules;

    /** 绑定首领原始阵容、奖励规则、战斗通道和存档。
     * @param roles 角色锁及钱包
     * @param store 角色战斗存档
     * @param combat 服务端回合
     * @param rocket 火箭队战斗互斥
     * @param calendar 原活动时间
     * @param inventory 奖励与邮件
     * @param daily 冒险与副本互斥
     * @param space 时空战斗
     * @param tower 洞穴战斗
     * @param orange 群岛战斗
     * @param travel 旅行战斗
     * @param station 道馆战斗
     * @param jdbc 同区首领和排行
     * @param json 原协议对象
     * @throws IOException 缺少首领配置
     */
    public WorldBossService(RoleService roles,ArenaMapper store,FixedStageCombatService combat,ActivityService calendar,RocketService rocket,
            InventoryService inventory,DailyDungeonService daily,SpaceService space,TowerService tower,OrangeService orange,
            ElfTravelService travel,DojoStationService station,JdbcTemplate jdbc,ObjectMapper json) throws IOException {
        this.rocket=rocket;this.roles=roles;this.store=store;this.combat=combat;this.calendar=calendar;this.inventory=inventory;this.daily=daily;
        this.space=space;this.tower=tower;this.orange=orange;this.travel=travel;this.station=station;this.jdbc=jdbc;this.json=json;
        try(InputStream in=new ClassPathResource("battle/world-boss-rosters.json").getInputStream();
            InputStream values=new ClassPathResource("battle/world-boss-rewards.json").getInputStream()) {
            roster=json.readTree(in).required("rosters");rules=json.readTree(values);
        }
    }

    /**
     * 北京时间的日序号。
     * @param now 时间戳
     * @return 自1970年首日起的天数
     */
    private long day(long now){return Math.floorDiv(now+28800,86400);}
    /**
     * 以周一开始的自然周序号。
     * @param now 时间戳
     * @return 周序号
     */
    private long week(long now){return Math.floorDiv(day(now)+3,7);}
    /**
     * 本期首领组号。
     * @param mode 活动号
     * @param week 周序号
     * @return 冻结的阵容编号
     */
    private int group(int mode,long week){return mode==NORMAL?300001+(int)Math.floorMod(week,3):1;}
    /**
     * 每日两场大师首领独立刷新生命。
     * @param mode 活动号
     * @param now 时间戳
     * @return 场次号
     */
    private long slot(int mode,long now){return 2*day(now)+(mode==MASTER && Math.floorMod(now+28800,86400)>=21*3600?1:0);}
    /**
     * 构建原地图目标唯一编号。
     * @param realm 区服
     * @param mode 活动号
     * @param now 时间戳
     * @return 目标编号
     */
    private String target(int realm,int mode,long now){return "wb-"+realm+"-"+mode+"-"+slot(mode,now);}
    /** 原协议所需对象结构。 @return 新对象 */
    private ObjectNode obj(){return json.createObjectNode();}
    /** 序列化同服关血与同周排行的处理顺序。 */
    private void lock(){jdbc.queryForObject("SELECT starts_at FROM local_activity_calendar WHERE id=1 FOR UPDATE",Long.class);}
    /**
     * 读取当前区服的首领周状态。
     * @param realm 区服
     * @param mode 模式
     * @param week 周次
     * @return 血量及榜
     */
    private ObjectNode world(int realm,int mode,long week){
        List<String> rows=jdbc.queryForList("SELECT state_data FROM local_world_boss WHERE realm_id=? AND mode_id=? AND week_id=?",String.class,realm,mode,week);
        try{return rows.isEmpty()?obj():(ObjectNode)json.readTree(rows.get(0));}
        catch(IOException ex){throw new IllegalStateException("世界首领存档损坏",ex);}
    }
    /**
     * 写回已持锁的区服账本。
     * @param realm 区服
     * @param mode 模式
     * @param week 周次
     * @param value 数据
     */
    private void save(int realm,int mode,long week,ObjectNode value){
        jdbc.update("MERGE INTO local_world_boss(realm_id,mode_id,week_id,state_data) KEY(realm_id,mode_id,week_id) VALUES (?,?,?,?)",realm,mode,week,value.toString());
    }
    /**
     * 为新的原活动场次初始化场内生命。
     * @param w 本周记录
     * @param mode 活动号
     * @param now 时间戳
     */
    private void life(ObjectNode w,int mode,long now){
        long slot=slot(mode,now);
        if(w.path("slot").asLong(-1)==slot)return;
        ObjectNode hp=w.putObject("life");int sum=0;
        for(JsonNode elf:roster.path(String.valueOf(group(mode,week(now)))).path("actors")){
            int qty=elf.required("hp_max").asInt();hp.put(elf.required("position").asText(),qty);sum+=qty;
        }
        w.put("slot",slot).put("hp",sum).put("max",sum);
    }
    /**
     * 获取仍能挑战的地图对象。
     * @param role 当前角色
     * @return 原 S4/S801 的 worldboss 对象列表
     */
    public ArrayNode scene(LocalRole role){
        long now=Instant.now().getEpochSecond();ArrayNode items=json.createArrayNode();
        if(role.sceneId!=CITY || role.level<1 || !calendar.overviewOpenAt(NORMAL,now))return items;
        ObjectNode w=world(role.realmId,NORMAL,week(now));life(w,NORMAL,now);
        if(w.path("hp").asInt()>0) {
            ObjectNode actor=items.addObject().put("sid", group(NORMAL,week(now))).put("uid",target(role.realmId,NORMAL,now))
                .put("level",28).put("hp",w.path("hp").asInt()).put("hpmax",w.path("max").asInt()).put("face",0);
            actor.putObject("pos").put("x",832).put("y",512);
        }
        return items;
    }
    /**
     * 每角色每模式独立剩余机会。
     * @param p 角色周账本
     * @return 今日次数
     */
    private int remaining(JsonNode p){return rules.path("freePerModeDay").asInt()+p.path("bought").asInt()-p.path("used").asInt();}
    /** 读取活动概览的剩余次数，不提前创建角色活动记录。
     * @param id 角色编号
     * @param mode 原活动号
     * @return 本日尚可挑战的次数
     */
    public int remaining(String id,int mode){
        ObjectNode p=store.load(id,"world-player:"+mode);long now=Instant.now().getEpochSecond();
        return p.path("day").asLong(-1)==day(now)?remaining(p):rules.path("freePerModeDay").asInt();
    }
    /**
     * 按真实伤害、达到时刻、UID稳定排序。
     * @param w 同区周账
     * @return 已造成实伤的角色
     */
    private List<ObjectNode> ranks(JsonNode w){
        List<ObjectNode> result=new ArrayList<>();
        for(JsonNode r:w.path("ranks"))if(r.path("damage").asLong()>0)result.add((ObjectNode)r);
        result.sort(Comparator.comparingLong((ObjectNode r)->r.path("damage").asLong()).reversed()
                .thenComparingLong(r->r.path("at").asLong()).thenComparing(r->r.path("uid").asText()));return result;
    }
    /**
     * 生成邮件收据。
     * @param key 带角色、周期与事项的业务键
     * @return UUID邮件号
     */
    private String receipt(String key){return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();}
    /**
     * 固定三元组数组转 Java 列表。
     * @param flat 原资产表
     * @return 可发的邮件附件
     */
    private List<Integer> flat(JsonNode flat){List<Integer> list=new ArrayList<>();for(JsonNode n:flat)list.add(n.asInt());return list;}
    /**
     * 档位奖励表转物品三元组。
     * @param rule 奖项数组
     * @return 可发的邮件附件
     */
    private List<Integer> items(JsonNode rule){
        List<Integer> list=new ArrayList<>();for(JsonNode n:rule){list.add(1);list.add(n.required("id").asInt());list.add(n.required("quantity").asInt());}return list;
    }
    /**
     * 邮寄一份固定业务收据，作为玩家可读的奖励来源。
     * @param role 角色
     * @param key 收据
     * @param title 名称
     * @param rewards 附件
     * @param now 发放时刻
     */
    private void mail(LocalRole role,String key,String title,List<Integer> rewards,long now){
        inventory.createWorldBossMail(role.id,receipt(role.id+":"+key),title,rewards,now);
    }
    /**
     * 到期补寄仍未领取的普通伤害奖、各自周榜和普通原幸运奖。
     * @param role 角色
     * @param mode 活动号
     * @param p 已结束周账
     * @param now 当前时间
     */
    private void close(LocalRole role,int mode,ObjectNode p,long now){
        long old=p.path("week").asLong();ObjectNode w=world(role.realmId,mode,old);
        String key=mode+":"+old+":";
        for(JsonNode v:p.path("pending"))mail(role,"damage:"+v.path("id").asText(),"世界首领伤害奖",items(rules.path("highestTierRewards").get(v.path("tier").asInt())),now);
        List<ObjectNode> rank=ranks(w);
        for(int i=0;i<rank.size();i++)if(role.id.equals(rank.get(i).path("uid").asText())){
            for(JsonNode tier:rules.path("weeklyPlaceRanks").path(mode==NORMAL?"regular":"master"))if(i+1>=tier.get(0).asInt() && i+1<=tier.get(1).asInt()){
                mail(role,key+"rank","世界首领周榜第"+(i+1)+"名",flat(tier.get(2)),now);break;
            }
            break;
        }
        if(mode==NORMAL){
            Collections.shuffle(rank,new Random(old*8191+role.realmId));
            for(int i=0;i<Math.min(3,rank.size());i++)if(role.id.equals(rank.get(i).path("uid").asText()))
                mail(role,key+"lucky","世界首领幸运奖",flat(rules.path("lucky").path(String.valueOf(group(mode,old)))),now);
        }
    }
    /**
     * 角色周账升级和每日次数重置。
     * @param role 角色
     * @param mode 模式
     * @param now 时间
     * @return 当前周账
     */
    private ObjectNode personal(LocalRole role,int mode,long now){
        String key="world-player:"+mode;ObjectNode p=store.load(role.id,key);
        if(p.path("week").asLong(-1)!=week(now)){
            if(p.has("week"))close(role,mode,p,now);
            p=obj().put("week",week(now));
        }
        if(p.path("day").asLong(-1)!=day(now))p.put("day",day(now)).put("used",0).put("bought",0);
        return p;
    }
    /**
     * 读取或结算已经存在的过期周，不因登录产生无效参赛者。
     * @param role 角色
     * @return 是否完成旧周结算
     */
    @Transactional
    public boolean rollover(LocalRole role){
        long now=Instant.now().getEpochSecond();boolean changed=false;
        roles.lockRole(role.id);
        for(int mode:new int[]{NORMAL,MASTER}){
            ObjectNode p=store.load(role.id,"world-player:"+mode);
            if(p.has("week") && p.path("week").asLong()!=week(now)){
                lock();close(role,mode,p,now);store.remove(role.id,"world-player:"+mode);changed=true;
            }
        }
        return changed;
    }
    /**
     * 原邮件附件形状。
     * @param rewards 道具三元组
     * @return 原 attachment
     */
    private ObjectNode attach(List<Integer> rewards){
        ObjectNode result=obj();ArrayNode rows=result.putArray("items");
        for(int i=0;i<rewards.size();i+=3)rows.addObject().put("id",rewards.get(i+1)).put("n",rewards.get(i+2));
        return result;
    }
    /**
     * 下发活动原排行与当前场次真实生命。
     * @param role 角色
     * @param mode 活动号
     * @param p 角色周账
     * @param w 同服周账
     * @param now 时间
     * @param out 原响应
     */
    private void panel(LocalRole role,int mode,ObjectNode p,ObjectNode w,long now,ObjectNode out){
        int group=group(mode,week(now));ObjectNode row=out;
        if(mode==NORMAL){
            row=out.putArray("data").addObject().put("sid",group).put("uid",target(role.realmId,mode,now)).put("level",28)
                    .put("cur_hp",w.path("hp").asInt()).put("max_hp",w.path("max").asInt())
                    .put("remaining_time",Math.max(0,day(now)*86400-28800+72000-now));
            row.put("local_bought",p.path("bought").asInt()>0);
            row.put("reward_index",p.path("pending").size());
            JsonNode first=p.path("pending").path(0);
            row.set("jifen_reward",first.isMissingNode()?obj():attach(items(rules.path("highestTierRewards").get(first.path("tier").asInt()))));
            row.put("jifen",0);
        } else out.put("hp",w.path("hp").asInt()).put("hp_max",w.path("max").asInt()).put("my_damage",0).put("buy_num",p.path("bought").asInt());
        out.put("challenge_num",remaining(p));row.put("my_rank",0);
        ArrayNode ranks=row.putArray("rank"),tiers=row.putArray("reward_tab");
        for(JsonNode tier:rules.path("weeklyPlaceRanks").path(mode==NORMAL?"regular":"master")){
            tiers.addObject().put("rank_left",tier.get(0).asInt()).put("rank_right",tier.get(1).asInt()).set("rank_reward",attach(flat(tier.get(2))));
        }
        int i=0;
        for(ObjectNode r:ranks(w)){
            i++;if(r.path("uid").asText().equals(role.id))row.put("my_rank",i).put(mode==NORMAL?"jifen":"my_damage",r.path("damage").asLong());
            if(i<=10){
                ObjectNode entry=ranks.addObject().put("name",r.path("name").asText());
                if(mode==NORMAL)entry.put("uid",r.path("uid").asText()).put("ranking",i).put("data",r.path("damage").asLong());
                else entry.put("damage",r.path("damage").asLong()).put("svrid",role.realmId);
            }
        }
    }
    /**
     * 写入大师原累计场数及实领下标。
     * @param p 角色账本
     * @param out 主动应答或 S10006
     */
    private void milestones(JsonNode p,ObjectNode out){
        out.put("total",p.path("count").asInt());ArrayNode claims=out.putArray("reward");
        p.path("claims").fieldNames().forEachRemaining(k->claims.add(Integer.parseInt(k)));
    }
    /**
     * 读取登录时的大师场数。
     * @param role 角色
     * @return S10006原字段
     */
    public ObjectNode summary(LocalRole role){ObjectNode p=store.load(role.id,"world-player:"+MASTER),out=obj();milestones(p,out);return out;}
    /**
     * 原战斗双方冻结快照。
     * @param fight 当前场次
     * @param out 开战响应
     */
    static void opening(ObjectNode fight,ObjectNode out){
        out.put("seed",fight.path("seed").asInt()).put("weather",0);
        if(fight.path("activity").asInt()==NORMAL)out.put("flag",true);
        for(String side:Arrays.asList("player","monster")){
            ObjectNode team=fight.path(side).deepCopy();Iterator<JsonNode> iter=team.path("pokemon").elements();
            while(iter.hasNext()){
                ObjectNode actor=(ObjectNode)iter.next();if(actor.path("hp").asInt()<=0)iter.remove();
                else actor.remove(Arrays.asList("goldCarry", "homeStatues","targetLimit","physical","baseDamage","enemy","skill"));
            }
            out.set(side,team);
        }
    }
    /**
     * 检查限时主城怪物与其他玩法共用的战斗占用。
     * @param id 本地角色编号
     * @return 是否有尚未结束的其他战斗
     */
    public boolean fightOccupied(String id) {
        return rocket.active(id) || daily.otherFightActive(id) || daily.active(id)
                || space.active(id) || tower.active(id) || orange.active(id) || travel.active(id) || station.active(id);
    }

    /**
     * 冻结有效挑战与阵容，扣减剩余次数。
     * @param role 角色
     * @param p 角色账本
     * @param w 区服血量
     * @param mode 活动
     * @param now 时间
     * @param pay 明示购买
     * @param result 结果
     */
    private void start(LocalRole role,ObjectNode p,ObjectNode w,int mode,long now,boolean pay,ObjectNode result){
        ObjectNode out=(ObjectNode)result.get("response");
        ObjectNode prev=store.load(role.id,FIGHT);
        if(MODE.equals(prev.path("mode").asText()) && prev.path("expiry").asLong()>now && !prev.path("advanced").asBoolean()
                && prev.path("activity").asInt()==mode && prev.path("slot").asLong()==slot(mode,now)){opening(prev,out);return;}
        if(w.path("hp").asInt()<=0 || mode==NORMAL && role.sceneId!=CITY || fightOccupied(role.id)){
            out.put("errno",2003);return;
        }
        int left=remaining(p);
        if(left<=0 && (!pay || mode!=NORMAL || p.path("bought").asInt()>=rules.path("buyMaxPerModeDay").asInt())){out.put("errno",2003);return;}
        int key=group(mode,week(now));ObjectNode fight=combat.start(role,key,roster.required(String.valueOf(key)));
        if(fight.path("errno").asInt()!=0){out.put("errno",2003);return;}
        if(left<=0){
            if(!buy(role,p,result)){out.put("errno",2003);return;}
        }
        fight.put("id",UUID.randomUUID().toString()).put("mode",MODE).put("started",now).put("activity",mode)
                .put("slot",slot(mode,now)).put("week",week(now)).put("advanced",false);
        int close=mode==NORMAL?20:Math.floorMod(now+28800,86400)<13*3600?13:22;
        fight.put("expiry",Math.min(now+1200,day(now)*86400-28800+close*3600));
        fight.set("initial",w.get("life").deepCopy());
        for(JsonNode actor:fight.path("monster").path("pokemon"))((ObjectNode)actor).put("hp",w.path("life").path(actor.path("position").asText()).asInt());
        p.put("used",p.path("used").asInt()+1);store.save(role.id,FIGHT,fight);opening(fight,out);
    }
    /**
     * 扣费并增加本模式一次机会。
     * @param role 角色
     * @param p 日账本
     * @param result 余额通知
     * @return 是否扣费
     */
    private boolean buy(LocalRole role,ObjectNode p,ObjectNode result){
        if(remaining(p)!=0 || p.path("bought").asInt()>=rules.path("buyMaxPerModeDay").asInt()
                || !inventory.spendMaterials(role.id,Collections.emptyMap(),0,rules.path("buyDiamonds").asInt()))return false;
        p.put("bought",p.path("bought").asInt()+1);result.put("diamonds",roles.findById(role.id).orElse(role).diamonds);return true;
    }
    /**
     * 处理详情、挑战、购买、逐场奖和大师累计奖励。
     * @param id 当前角色
     * @param cmd 原协议
     * @param uid 世界地图目标
     * @param idx 档位
     * @param pay 明示钻石支付
     * @param seen 面板签发场次
     * @param token 连接内请求号
     * @return 协议和资产推送
     */
    @Transactional
    public ObjectNode action(String id,int cmd,String uid,int idx,boolean pay,long seen,String token){
        LocalRole role=roles.lockRole(id);lock();long now=Instant.now().getEpochSecond();
        int mode=cmd<10000?NORMAL:MASTER;long week=week(now);ObjectNode p=personal(role,mode,now);
        ObjectNode w=world(role.realmId,mode,week);life(w,mode,now);
        ObjectNode result=obj().put("slot",slot(mode,now));ObjectNode out=result.putObject("response").put("errno",0);
        boolean query=cmd==3653 || cmd==10033;String signature=cmd+":"+idx+":"+uid+":"+pay;
        int split=token.lastIndexOf(':');
        String connection=token.substring(0,split);long session=Long.parseLong(token.substring(split+1));
        JsonNode last=p.path("receipts").path(connection);
        if(!query && session<=last.path("session").asLong(-1)){
            if(session!=last.path("session").asLong()){out.put("errno",2003);return result;}
            if(!signature.equals(last.path("signature").asText()))out.put("errno",2003);
            else if(cmd==3651||cmd==10034){
                ObjectNode old=store.load(id,FIGHT);
                if(MODE.equals(old.path("mode").asText()) && !old.path("advanced").asBoolean() && old.path("expiry").asLong()>now && old.path("id").equals(last.path("fightId")))opening(old,out);
                else out.put("errno",2003);
            }else out.setAll((ObjectNode)last.path("response"));
            return result;
        }
        if(role.level<1 || !query && cmd!=3652 && cmd!=10035 && !calendar.overviewOpenAt(mode,now)
                || !query && seen!=slot(mode,now) && !(cmd==3651 && !pay && remaining(p)>0 && target(role.realmId,mode,now).equals(uid)))out.put("errno",2003);
        else if(query)panel(role,mode,p,w,now,out);
        else if(cmd==3651 || cmd==10034){
            if(cmd==3651 && !target(role.realmId,mode,now).equals(uid))out.put("errno",2003);
            else start(role,p,w,mode,now,pay,result);
        }else if(cmd==10036){
            if(w.path("hp").asInt()<=0||!buy(role,p,result))out.put("errno",2003);else out.put("challenge_num",remaining(p));
        }else if(cmd==3652){
            ArrayNode pending=p.withArray("pending");
            if(!target(role.realmId,mode,now).equals(uid)||pending.isEmpty())out.put("errno",2003);
            else{
                JsonNode entry=pending.remove(0);mail(role,"damage:"+entry.path("id").asText(),"世界首领伤害奖",items(rules.path("highestTierRewards").get(entry.path("tier").asInt())),now);
                result.put("mail",true);
                out.set("next_reward",pending.isEmpty()?obj():attach(items(rules.path("highestTierRewards").get(pending.get(0).path("tier").asInt()))));
            }
        }else if(cmd==10035){
            JsonNode cfg=rules.path("masterParticipation");
            if(idx<1||idx>4||p.path("count").asInt()<cfg.path("counts").path(idx-1).asInt()||p.path("claims").has(String.valueOf(idx)))out.put("errno",2003);
            else{
                p.with("claims").put(String.valueOf(idx),true);milestones(p,out);
                mail(role,"master:"+week+":"+idx,"大师世界首领挑战奖",Arrays.asList(3,64147,cfg.path("elfChip64147").get(idx-1).asInt(),1,20003,cfg.path("item20003").get(idx-1).asInt()),now);
                result.put("mail",true);
            }
        }
        if(!query && out.path("errno").asInt()==0){
            ObjectNode receipt=p.with("receipts").putObject(connection).put("session",session).put("signature",signature);
            if(cmd==3651||cmd==10034)receipt.set("fightId",store.load(id,FIGHT).path("id"));
            else receipt.set("response",out.deepCopy());
        }
        store.save(id,"world-player:"+mode,p);return result;
    }
    /**
     * 是否接管该角色的回合。
     * @param id 角色
     * @return 仍可提交的世界首领战斗
     */
    public boolean active(String id){
        JsonNode f=store.load(id,FIGHT);return MODE.equals(f.path("mode").asText())&&f.path("expiry").asLong()>Instant.now().getEpochSecond();
    }
    /**
     * 真实服务端战斗结算和跨角色并发扣血。
     * @param id 角色
     * @param skill 技能
     * @param targets 目标
     * @param flag 原操作
     * @param retreat 撤退
     * @return 战斗推送结果
     */
    @Transactional
    public ObjectNode turn(String id,int skill,List<?> targets,int flag,boolean retreat){
        LocalRole role=roles.lockRole(id);ObjectNode fight=store.load(id,FIGHT);long now=Instant.now().getEpochSecond();
        ObjectNode result=obj();if(!MODE.equals(fight.path("mode").asText())||fight.path("expiry").asLong()<=now)return result;
        int outcome=combat.advance(fight,skill,targets,flag,retreat);result.put("outcome",outcome);
        if(outcome==0)store.save(id,FIGHT,fight.put("advanced",true));
        if(outcome<=0)return result;
        store.remove(id,FIGHT);if(retreat)return result;
        int mode=fight.path("activity").asInt();long week=week(now);
        if(fight.path("slot").asLong()!=slot(mode,now)||fight.path("week").asLong()!=week||!calendar.overviewOpenAt(mode,now))return result;
        lock();ObjectNode w=world(role.realmId,mode,week);life(w,mode,now);
        ObjectNode p=personal(role,mode,now),hp=w.with("life");int damage=0;
        for(JsonNode actor:fight.path("monster").path("pokemon")){
            String pos=actor.path("position").asText();
            int spent=Math.max(0,fight.path("initial").path(pos).asInt()-Math.max(0,actor.path("hp").asInt()));
            int valid=Math.min(hp.path(pos).asInt(),spent);damage+=valid;hp.put(pos,hp.path(pos).asInt()-valid);
        }
        if(damage<=0)return result;
        int left=w.path("hp").asInt()-damage;w.put("hp",left);p.put("count",p.path("count").asInt()+1);
        ObjectNode rank=w.with("ranks").with(id);rank.put("uid",id).put("name",role.name).put("at",now).put("damage",rank.path("damage").asLong()+damage);
        int tier=-1;JsonNode ratios=rules.path(mode==NORMAL?"regularDamageRatios":"limitedDamageRatios");
        for(int i=0;i<ratios.size();i++)if(100L*damage>=Math.round(ratios.get(i).asDouble()*100)*w.path("max").asLong())tier=i;
        if(tier>=0){
            if(mode==NORMAL)p.withArray("pending").addObject().put("id",fight.path("id").asText()).put("tier",tier);
            else{mail(role,"damage:"+fight.path("id").asText(),"大师世界首领伤害奖",items(rules.path("highestTierRewards").get(tier)),now);result.put("mail",true);}
        }
        if(left==0&&!p.path("firstKill").asBoolean()){
            mail(role,"clear:"+mode+":"+week,"世界首领本周首杀",items(rules.path("weeklyFirstClear")),now);p.put("firstKill",true);result.put("mail",true);
        }
        save(role.realmId,mode,week,w);store.save(id,"world-player:"+mode,p);
        if(mode==MASTER)milestones(p,result.putObject("summary"));
        return result;
    }
}
