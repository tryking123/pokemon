package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.battle.FixedStageCombatService;
import com.pokemon.local.dailydup.DailyDungeonService;
import com.pokemon.local.dojo.DojoStationService;
import com.pokemon.local.elf.ElfTravelService;
import com.pokemon.local.home.OrangeService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.space.SpaceService;
import com.pokemon.local.tower.TowerService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 悬赏首领的实际伤害、独立积分余额、兑换与自然周期排行 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 11:01
 */
@Service
public class BountyService {
    private static final int ACTIVITY=10042;
    private static final String STATE="bounty-account";
    private static final String MODE="bounty_boss";
    private static final String FIGHT="attempt";
    private final RoleService roles;
    private final ArenaMapper store;
    private final ActivityService calendar;
    private final InventoryService inventory;
    private final FixedStageCombatService combat;
    private final DailyDungeonService daily;
    private final SpaceService space;
    private final TowerService tower;
    private final OrangeService orange;
    private final ElfTravelService travel;
    private final DojoStationService station;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode config;

    /** 绑定原活动时段、角色事务、实际编队及竞技通道。
     * @param roles 角色和钱包
     * @param store 本机首领状态
     * @param calendar 活动 10042 时间窗
     * @param inventory 奖品、邮件及钻石扣款
     * @param combat 固定怪物队伍回合引擎
     * @param daily 普通战斗与每日副本互斥
     * @param space 时空关卡互斥
     * @param tower 洞穴互斥
     * @param orange 群岛互斥
     * @param travel 旅行互斥
     * @param station 道馆驻守互斥
     * @param jdbc 各实际角色的周期得分查询
     * @param json 随包配置和原协议字段
     * @throws IOException 读取已核验首领表失败
     */
    public BountyService(RoleService roles,ArenaMapper store,ActivityService calendar,InventoryService inventory,
                         FixedStageCombatService combat,DailyDungeonService daily,SpaceService space,TowerService tower,
                         OrangeService orange,ElfTravelService travel,DojoStationService station,JdbcTemplate jdbc,ObjectMapper json) throws IOException {
        this.roles=roles; this.store=store; this.calendar=calendar; this.inventory=inventory; this.combat=combat;
        this.daily=daily; this.space=space; this.tower=tower; this.orange=orange; this.travel=travel;
        this.station=station; this.jdbc=jdbc; this.json=json;
        try (InputStream file=new ClassPathResource("battle/bounty.json").getInputStream()) { config=json.readTree(file); }
    }

    /** 处理原首领、商店、榜、领奖和购买协议，并补寄已关闭的周期榜奖。
     * @param id 当前角色
     * @param action 原协议编号，零只补结算
     * @param index 商品或奖励的一基编号
     * @param refresh 刷新方式，1 免费、2 付费
     * @param seen 已送达面板的周期，防止跨周旧面板消费
     * @param connection 当前游戏连接编号
     * @param session 单连接递增的请求号
     * @return 协议应答、周期和已提交资产的附加通知
     */
    @Transactional
    public ObjectNode act(String id,int action,int index,int refresh,long seen,String connection,long session) {
        LocalRole role=roles.lockRole(id);
        ObjectNode state=store.load(id,STATE);
        ObjectNode result=json.createObjectNode();
        ObjectNode out=result.putObject("response").put("errno",0);
        if (action==0 && state.isEmpty()) return result;
        long now=Instant.now().getEpochSecond();
        ActivityPeriod period=calendar.cycleAt(ACTIVITY,now);
        if (now<period.start || now>=period.end) {out.put("errno",2003); return result;}
        if (state.path("week").asLong()!=period.start) {
            lockRanking();
            if (state.has("week")) {
                closeWeek(role,state.path("week").asLong(),result,now);
                if (MODE.equals(store.load(id,FIGHT).path("mode").asText())) store.remove(id,FIGHT);
            }
            if (action==0) {store.remove(id,STATE); return result;}
            state=json.createObjectNode().put("week",period.start);
            draw(id,state,false,now);
        }
        result.put("week",period.start);
        long day=Math.floorDiv(now+28800,86400);
        if (state.path("day").asLong(-1)!=day) state.put("day",day).put("used",0).put("bought",0);
        String signature=action+":"+index+":"+refresh;
        JsonNode last=state.path("receipts").path(connection);
        boolean mutation=action>=10016;
        if(mutation && !last.isMissingNode() && session<=last.path("session").asLong()) {
            if(session!=last.path("session").asLong() || !signature.equals(last.path("signature").asText())) out.put("errno",2003);
            else if(action!=10016) result.set("response",last.path("response"));
            else {
                ObjectNode previous=store.load(id,FIGHT);
                if(MODE.equals(previous.path("mode").asText()) && !previous.has("advanced") && now<previous.path("expiry").asLong()) opening(previous,out);
                else out.put("errno",2003);
            }
            return result;
        }
        if (action>=10016 && seen!=period.start) out.put("errno",2003);
        else {
            switch(action) {
                case 0: break;
                case 10012: panel(state,out,true); break;
                case 10013: shop(state,out); break;
                case 10014: rank(role,period.start,state,out); break;
                case 10015: milestones(state,out); break;
                case 10016: start(role,state,out,now); break;
                case 10017:
                    if (active(id) || refresh!=1 && refresh!=2 || refresh==1 && now<state.path("next").asLong()
                            || refresh==2 && !inventory.spendMaterials(id,Collections.emptyMap(),0,5000)) {
                        out.put("errno",2003); break;
                    }
                    draw(id,state,refresh==2,now);
                    panel(state,out,false);
                    if(refresh==2) result.put("wallet",true);
                    break;
                case 10018: redeem(role,state,index,result); break;
                case 10019: claim(role,state,index,result); break;
                case 10020:
                    int bought=state.path("bought").asInt();
                    if (bought>=2 || !inventory.spendMaterials(id,Collections.emptyMap(),0,300)) out.put("errno",2003);
                    else {
                        state.put("bought",bought+1);
                        out.put("num",attempts(state)).put("buy_num",1-bought);
                        result.put("wallet",true);
                    }
                    break;
                default: out.put("errno",2003);
            }
        }
        if(mutation && out.path("errno").asInt()==0) {
            ObjectNode receipt=state.with("receipts").putObject(connection).put("session",session).put("signature",signature);
            if(action!=10016) receipt.set("response",out);
        }
        store.save(id,STATE,state);
        if(result.has("wallet")) result.put("diamonds",roles.findById(id).orElse(role).diamonds);
        return result;
    }

    /** 在多角色并行结算时序列化最终榜单读取和得分更新。
     */
    private void lockRanking() {
        jdbc.queryForObject("SELECT starts_at FROM local_activity_calendar WHERE id=1 FOR UPDATE",Long.class);
    }

    /** 剩余真实挑战次数。
     * @param state 当前角色的首领账本
     * @return 今日基础、购买和已用次数合计
     */
    private int attempts(JsonNode state) {return 3+state.path("bought").asInt()-state.path("used").asInt();}

    /** 使用底包两个独立的万分权重池生成并冻结目标。
     * @param id 角色编号
     * @param state 当前周期账本
     * @param paid 是否从付费池抽取
     * @param now 目标生成时刻
     */
    private void draw(String id,ObjectNode state,boolean paid,long now) {
        int serial=state.path("serial").asInt()+1;
        int roll=new java.util.Random(state.path("week").asLong()+id.hashCode()*100003L+serial*7919L).nextInt(10000);
        for(int key=750001; key<=750050; key++) {
            JsonNode target=config.path("targets").path(String.valueOf(key));
            roll-=target.path(paid ? "paid" : "free").asInt();
            if(roll>=0) continue;
            state.put("serial",serial).put("boss",key).put("next",now+14400);
            ObjectNode life=state.putObject("life");
            int total=0;
            for(JsonNode actor:target.path("actors")) {
                int hp=actor.path("hp_max").asInt();
                life.put(actor.path("position").asText(),hp);
                total+=hp;
            }
            state.put("max",total).put("hp",total);
            return;
        }
        throw new IllegalStateException("悬赏首领的抽取权重不完整");
    }

    /** 构造原详情或目标刷新应答，使预览与实际生命、积分及击杀奖一致。
     * @param s 角色账本
     * @param out 原协议应答
     * @param full 是否下发挑战和购买次数
     */
    private void panel(ObjectNode s,ObjectNode out,boolean full) {
        int boss=s.path("boss").asInt(),hp=s.path("hp").asInt(),max=s.path("max").asInt();
        JsonNode row=config.path("targets").path(String.valueOf(boss));
        out.put("monsterid",boss).put("star",row.path("star").asInt()).put("all_hp",hp).put("all_hp_max",max)
                .put("next_time",s.path("next").asLong()).put("integral",(int)(20L*hp/max));
        out.set("reward",row.path("reward"));
        if(full) out.put("num",attempts(s)).put("buy_num",2-s.path("bought").asInt());
    }

    /** 生成周内兑换次数、余额和准确价格。
     * @param s 角色账本
     * @param out 商店响应
     */
    private void shop(ObjectNode s,ObjectNode out) {
        out.put("wallet",s.path("wallet").asInt());
        ArrayNode offers=out.putArray("info");
        for(JsonNode rule:config.path("offers")) {
            ObjectNode row=offers.addObject().put("price1",rule.path("cost").path("quantity").asInt())
                    .put("times",rule.path("limit").asInt()).put("use_num",s.path("shop").path(rule.path("id").asText()).asInt());
            row.set("itemid",rewards(rule));
            row.putArray("price").add(1).add(34129).add(row.path("price1").asInt());
        }
    }

    /** 返回各本周积分档及已领取档号。
     * @param s 角色账本
     * @param out 累计奖励响应
     */
    private void milestones(ObjectNode s,ObjectNode out) {
        out.put("total",s.path("earned").asInt());
        ArrayNode rows=out.putArray("info"), history=out.putArray("history");
        int i=0;
        for(JsonNode rule:config.path("milestones")) {
            rows.addObject().put("times",rule.path("need").asInt()).set("reward",rewards(rule));
            if(s.path("claims").has(String.valueOf(++i)))history.add(i);
        }
    }

    /** 按排行、商品或累计档规则构建原奖励三元组。
     * @param rule 数值规划档位
     * @return 原协议物品三元组
     */
    private ArrayNode rewards(JsonNode rule) {
        ArrayNode rows=json.createArrayNode();
        for(JsonNode item:rule.path("rewards")) rows.add(1).add(item.path("id").asInt()).add(item.path("quantity").asInt());
        return rows;
    }

    /** 发放完整奖品，背包满时由已有服务补寄。
     * @param id 角色编号
     * @param receipt 本次领取的稳定回执号
     * @param rule 奖品规则
     * @param result 本次实际入包明细
     */
    private void deliver(String id,String receipt,JsonNode rule,ObjectNode result) {
        Map<Integer,Long> items=new java.util.LinkedHashMap<>();
        for(JsonNode item:rule.path("rewards")) items.put(item.path("id").asInt(),item.path("quantity").asLong());
        ArrayNode direct=result.with("adds").putArray("items");
        inventory.grantItemsOrMail(id,receipt,items).forEach((key,n)->direct.addObject().put("id",key)
                .put("type",inventory.itemType(key)).put("n",n));
        result.put("mail",true);
    }

    /** 兑换扣可用分，累计分与名次保持其实际来源。
     * @param role 角色
     * @param state 周期账本
     * @param idx 一基商品号
     * @param result 原响应和入包通知
     */
    private void redeem(LocalRole role,ObjectNode state,int idx,ObjectNode result) {
        ObjectNode out=(ObjectNode)result.get("response");
        JsonNode offer=config.path("offers").path(idx-1);
        int count=state.path("shop").path(String.valueOf(idx)).asInt();
        int price=offer.path("cost").path("quantity").asInt();
        int balance=state.path("wallet").asInt();
        if(offer.isMissingNode() || count>=offer.path("limit").asInt() || price>balance) {out.put("errno",2003);return;}
        state.with("shop").put(String.valueOf(idx),count+1);
        state.put("wallet",balance-price);
        deliver(role.id,"bounty-shop:"+state.path("week").asLong()+":"+idx+":"+count,offer,result);
        out.put("integral",balance-price).put("index",idx).put("num",count+1);
    }

    /** 领取准确达到的累计分档，每周每档只发一次。
     * @param role 角色
     * @param state 周期账本
     * @param idx 一基档号
     * @param result 原响应和入包通知
     */
    private void claim(LocalRole role,ObjectNode state,int idx,ObjectNode result) {
        ObjectNode out=(ObjectNode)result.get("response");
        JsonNode rule=config.path("milestones").path(idx-1);
        if(rule.isMissingNode() || state.path("earned").asInt()<rule.path("need").asInt() || state.path("claims").has(String.valueOf(idx))) {
            out.put("errno",2003);return;
        }
        state.with("claims").put(String.valueOf(idx),true);
        deliver(role.id,"bounty-mile:"+state.path("week").asLong()+":"+idx,rule,result);
        out.put("index",idx);
    }

    /** 获取真实参赛者按得分、达到时间及 UID 稳定排序的榜单。
     * @param role 查询角色
     * @param week 周期起点
     * @return 同服真实正分角色
     */
    private List<ObjectNode> standings(LocalRole role,long week) {
        List<String> stored=jdbc.queryForList("SELECT s.state_data FROM local_arena_state s JOIN local_game_role r ON r.id=s.role_id WHERE s.state_key=? AND r.realm_id=?",
                String.class,"bounty-rank:"+week,role.realmId);
        List<ObjectNode> rows=new ArrayList<>();
        for(String entry:stored) {
            try {rows.add((ObjectNode)json.readTree(entry));}
            catch(IOException ex) {throw new IllegalStateException("无法读取悬赏积分榜",ex);}
        }
        rows.sort(Comparator.comparingInt((ObjectNode r)->r.path("integral").asInt()).reversed()
                .thenComparingLong(r->r.path("reached").asLong()).thenComparing(r->r.path("uid").asText()));
        return rows;
    }

    /** 按实际积分和名次选择周榜奖励。
     * @param place 一基名次
     * @param score 实际积分
     * @return 该名次奖励；未达到门槛时为空
     */
    private JsonNode tier(int place,int score) {
        if(score<config.path("rank").path("minimum").asInt())return null;
        for(JsonNode rule:config.path("rank").path("tiers")) if(place>=rule.path("from").asInt() && place<=rule.path("to").asInt())return rule;
        return null;
    }

    /** 下发本机上榜角色及自己的真实名次。
     * @param role 当前角色
     * @param week 当前周
     * @param state 我的积分账本
     * @param out 排行榜应答
     */
    private void rank(LocalRole role,long week,ObjectNode state,ObjectNode out) {
        out.put("my_integral",state.path("earned").asInt()).put("my_rank",0);
        ArrayNode list=out.putArray("rank");
        int i=0;
        for(ObjectNode row:standings(role,week)) {
            i++;
            if(row.path("uid").asText().equals(role.id)) out.put("my_rank",i);
            if(i<=10) {
                row.remove("reached");
                JsonNode rule=tier(i,row.path("integral").asInt());
                row.set("reward",rule==null ? json.createArrayNode() : rewards(rule));
                list.add(row);
            }
        }
    }

    /** 根据闭榜后不再变动的得分，补寄角色真实所得周奖。
     * @param role 当前角色
     * @param week 已结束周期
     * @param result 邮件提示标记
     * @param now 当前时间戳
     */
    private void closeWeek(LocalRole role,long week,ObjectNode result,long now) {
        int i=0;
        for(ObjectNode row:standings(role,week)) {
            i++;
            if(!role.id.equals(row.path("uid").asText())) continue;
            JsonNode rule=tier(i,row.path("integral").asInt());
            if(rule!=null) {
                List<Integer> items=new ArrayList<>();
                for(JsonNode field:rewards(rule)) items.add(field.asInt());
                inventory.createRewardMail(role.id,"bounty-rank:"+week+":"+role.id,"悬赏首领周榜奖励","本周实际积分排名第"+i+"名。",now,now+604800,items);
                result.put("mail",true);
            }
            break;
        }
    }

    /** 检查悬赏战斗通道是否仍有效。
     * @param id 角色编号
     * @return 已开始且未超时的悬赏战斗
     */
    public boolean active(String id) {
        JsonNode fight=store.load(id,FIGHT);
        return MODE.equals(fight.path("mode").asText()) && fight.path("expiry").asLong()>Instant.now().getEpochSecond();
    }

    /** 冻结真实主队及本只首领的剩余生命，正式开场才消耗一次。
     * @param role 本机角色
     * @param state 当前首领资料
     * @param out 原挑战应答
     * @param now 当前时间戳
     */
    private void start(LocalRole role,ObjectNode state,ObjectNode out,long now) {
        ObjectNode prior=store.load(role.id,FIGHT);
        if(MODE.equals(prior.path("mode").asText()) && now<prior.path("expiry").asLong() && !prior.has("advanced")
                && state.path("week").equals(prior.path("week")) && state.path("serial").equals(prior.path("serial"))) {
            opening(prior,out);return;
        }
        if(state.path("hp").asInt()<=0 || attempts(state)<=0 || daily.otherFightActive(role.id) || daily.active(role.id)
                || space.active(role.id) || tower.active(role.id) || orange.active(role.id) || travel.active(role.id) || station.active(role.id)) {
            out.put("errno",2003);return;
        }
        int boss=state.path("boss").asInt();
        ObjectNode fight=combat.start(role,boss,config.path("targets").path(String.valueOf(boss)));
        if(fight.path("errno").asInt()!=0) {out.put("errno",2003);return;}
        fight.put("mode",MODE).put("id",java.util.UUID.randomUUID().toString()).put("started",now).put("expiry",now+1800)
                .put("week",state.path("week").asLong()).put("serial",state.path("serial").asInt()).put("initial",state.path("hp").asInt());
        for(JsonNode member:fight.path("monster").path("pokemon")) ((ObjectNode)member).put("hp",state.path("life").path(member.path("position").asText()).asInt());
        state.put("used",state.path("used").asInt()+1);
        store.save(role.id,FIGHT,fight);
        opening(fight,out);
    }

    /** 将双方已冻结且存活的真实单位编码入原编队。
     * @param fight 已存档的本场战斗
     * @param out 原挑战应答
     */
    private void opening(ObjectNode fight,ObjectNode out) {
        out.put("seed",fight.path("seed").asInt()).put("weather",0);
        for(String key:Arrays.asList("player","monster")) {
            ObjectNode team=fight.path(key).deepCopy();
            java.util.Iterator<JsonNode> members=team.path("pokemon").elements();
            while(members.hasNext()) {
                ObjectNode member=(ObjectNode)members.next();
                if(member.path("hp").asInt()<=0) members.remove();
                else member.remove(Arrays.asList("goldCarry", "homeStatues","targetLimit","physical","baseDamage","enemy","skill"));
            }
            out.set(key,team);
        }
    }

    /** 仅根据已提交的实际回合损血结算积分和击杀奖励。
     * @param id 角色编号
     * @param skill 客户端选择的技能
     * @param targets 选择的本场目标 UID
     * @param flag 自动、手动或空同步标记
     * @param retreat 是否撤退
     * @return 对局结束状态及真正到账的奖励
     */
    @Transactional
    public ObjectNode turn(String id,int skill,List<?> targets,int flag,boolean retreat) {
        LocalRole role=roles.lockRole(id);
        ObjectNode fight=store.load(id,FIGHT);
        long now=Instant.now().getEpochSecond();
        if(!MODE.equals(fight.path("mode").asText()) || now>=fight.path("expiry").asLong()) return null;
        int outcome=combat.advance(fight,skill,targets,flag,retreat);
        ObjectNode result=json.createObjectNode().put("outcome",outcome);
        if(outcome==0)store.save(id,FIGHT,fight.put("advanced",true));
        if(outcome<=0)return result;
        store.remove(id,FIGHT);
        if(retreat)return result;
        lockRanking();
        ObjectNode state=store.load(id,STATE);
        ActivityPeriod period=calendar.cycleAt(ACTIVITY,Instant.now().getEpochSecond());
        if(state.path("week").asLong()!=period.start || !state.path("week").equals(fight.path("week"))
                || !state.path("serial").equals(fight.path("serial")))return result;
        int remaining=0;
        for(JsonNode member:fight.path("monster").path("pokemon")) {
            int hp=Math.max(0,member.path("hp").asInt());
            state.with("life").put(member.path("position").asText(),hp);remaining+=hp;
        }
        int damage=Math.max(0,fight.path("initial").asInt()-remaining);
        int points=(int)Math.min(20,20L*damage/state.path("max").asInt());
        state.put("hp",remaining);
        if(points>0) {
            state.put("wallet",state.path("wallet").asInt()+points).put("earned",state.path("earned").asInt()+points);
            ObjectNode rank=json.createObjectNode().put("uid",id).put("name",role.name).put("sec",role.sex)
                    .put("icon",role.avatar).put("integral",state.path("earned").asInt()).put("reached",now);
            store.save(id,"bounty-rank:"+period.start,rank);
        }
        if(outcome==1 && remaining==0 && damage>0) {
            int diamonds=config.path("targets").path(state.path("boss").asText()).path("reward").get(2).asInt();
            roles.addCurrency(id,0,diamonds,0);
            result.put("diamonds",roles.findById(id).orElse(role).diamonds);
            result.putObject("adds").put("diamonds",diamonds);
        }
        store.save(id,STATE,state);
        return result;
    }
}
