package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.CarnivalProgressMapper;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.dailydup.DailyDungeonService;
import com.pokemon.offline.dojo.DojoStationService;
import com.pokemon.offline.elf.ElfTravelService;
import com.pokemon.offline.home.OrangeService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.space.SpaceService;
import com.pokemon.offline.tower.TowerService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本机矿区开采、占位、采集和矿洞 NPC 掠夺 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:40
 */
@Service
public class MineService {
    private static final int POSITIONS=10;
    private static final String ACCOUNT="mine-account";
    private static final String ATTEMPT="attempt";
    private static final String MODE="mine_raid";
    private final ArenaMapper mapper;
    private final ObjectMapper json;
    private final RoleService roles;
    private final ArenaService arena;
    private final ArenaCombat combat;
    private final BattleMapper formations;
    private final CarnivalProgressMapper carnival;
    private final DailyDungeonService daily;
    private final SpaceService space;
    private final TowerService tower;
    private final OrangeService orange;
    private final ElfTravelService travel;
    private final DojoStationService dojoStation;
    private final GamePaymentService payments;
    private final JsonNode maps;
    private final JsonNode data;

    /**
     * 绑定持久矿位、实际编队、全局战斗互斥和活动进度。
     * @param mapper 角色竞技及矿洞存档
     * @param json 结构化数据编码器
     * @param roles 角色锁和金币资产
     * @param arena 已登记普通队伍
     * @param combat 本地战斗引擎
     * @param formations 角色实际阵法
     * @param carnival 七日目标流水
     * @param daily 日常副本及普通战斗互斥
     * @param space 时空挑战互斥
     * @param tower 洞穴战斗互斥
     * @param orange 橘子群岛战斗互斥
     * @param travel 旅行战斗互斥
     * @param dojoStation 道馆驻守战斗互斥
     * @param payments 矿位上显示的角色 VIP 等级
     * @throws IOException 无法读取随包原矿区配置
     */
    public MineService(ArenaMapper mapper,ObjectMapper json,RoleService roles,ArenaService arena,ArenaCombat combat,
                       BattleMapper formations,CarnivalProgressMapper carnival,DailyDungeonService daily,SpaceService space,
                       TowerService tower,OrangeService orange,ElfTravelService travel,DojoStationService dojoStation,
                       GamePaymentService payments) throws IOException {
        this.mapper=mapper; this.json=json; this.roles=roles; this.arena=arena; this.combat=combat; this.formations=formations;
        this.carnival=carnival; this.daily=daily; this.space=space; this.tower=tower; this.orange=orange; this.travel=travel;
        this.dojoStation=dojoStation;
        this.payments=payments;
        try (InputStream m=new ClassPathResource("scene/mine-maps.json").getInputStream();
             InputStream d=new ClassPathResource("arena/mine.json").getInputStream()) {
            maps=json.readTree(m); data=json.readTree(d);
        }
    }

    /** 按角色锁操作矿洞，并返回本次需要同步的状态。
     * @param id 角色编号
     * @param action 请求编号，零为登录同步
     * @param scene 矿区场景
     * @param pos 场内一基矿位
     * @param team 普通编队号
     * @param target 原客户端看到的驻守者编号
     * @return 原协议应答及资产变化
     */
    @Transactional
    public ObjectNode act(String id,int action,int scene,int pos,int team,String target) {
        LocalRole role=roles.lockRole(id);
        long now=Instant.now().getEpochSecond();
        ObjectNode state=mapper.load(id,ACCOUNT);
        MineClock.advance(state,now);
        ObjectNode result=json.createObjectNode();
        ObjectNode out=result.putObject("response").put("errno",0);
        ObjectNode lease=(ObjectNode)state.get("lease");
        JsonNode region=maps.path(Integer.toString(scene));
        boolean canEnter=region.isArray() && role.stage>=region.get(1).asInt() && role.level>=Math.max(25,region.get(2).asInt());
        if (action==0) {
            result.set("panel",panel(state));
        } else if (role.level<25) {
            out.put("errno",2003);
        } else if (action==129) {
            ArrayNode areas=out.putArray("allinfo");
            Iterator<String> keys=maps.fieldNames();
            while (keys.hasNext()) {
                int area=Integer.parseInt(keys.next());
                ArrayNode spots=layout(id,area,state.path("day").asLong());
                int occupied=0;
                boolean rare=false;
                for (int i=0; i<spots.size(); i++) {
                    JsonNode spot=spots.get(i);
                    boolean owned=at(lease,area,i+1);
                    if (owned || occupied(state,spot,area,i+1)) occupied++;
                    if (isRare(owned ? lease.path("type").asInt() : spot.path("type").asInt())) rare=true;
                }
                areas.addObject().put("idx",area).put("occupy",occupied).put("total",POSITIONS).put("israre",rare);
            }
        } else if (action==125) {
            pay(role,state,result);
            out.put("mining_sec",state.path("worked").asInt());
            result.set("panel",panel(state));
        } else if (!canEnter || role.sceneId!=scene || action!=126 && (pos<1 || pos>POSITIONS)) {
            out.put("errno",2003);
        } else {
            ArrayNode spots=board(role,state,scene);
            if (action==126) {
                out.put("idx",scene);
                out.set("minelist",visible(role,state,scene,spots));
            } else if (action==124 && at(lease,scene,pos)) {
                pay(role,state,result);
                state.remove("lease");
                out.put("mining_sec",state.path("worked").asInt());
                result.set("quit",panel(state));
            } else if (action==122 && at(lease,scene,pos)) {
                out.put("lasttime",28800-state.path("worked").asInt());
            } else if (action==122 && (lease==null || state.path("captures").has(scene+":"+pos))
                    && !occupied(state,spots.get(pos-1),scene,pos)
                    && state.path("worked").asInt()<28800) {
                ArrayNode players=arena.players(role,ArenaService.ARENA,0,team);
                if (players.isEmpty()) out.put("errno",2003);
                else {
                    if (lease!=null) pay(role,state,result);
                    state.with("captures").remove(scene+":"+pos);
                    ObjectNode holder=state.putObject("lease");
                    holder.put("scene",scene).put("pos",pos).put("team",team).put("started",now)
                            .put("power",combat.power(players)).put("rate",new int[]{1000,1500,2500}[region.get(1).asInt()])
                            .put("type",spots.get(pos-1).path("type").asInt());
                    out.put("lasttime",28800-state.path("worked").asInt());
                    result.set("panel",panel(state));
                    if (lease!=null && lease.path("scene").asInt()==scene) {
                        int old=lease.path("pos").asInt();
                        result.putObject("previous").put("idx",scene).put("pos",old)
                                .set("info",visible(role,state,scene,spots).get(old-1));
                    }
                }
            } else if (action==123) {
                raid(role,state,result,spots.get(pos-1),scene,pos,team,target,now);
            } else {
                out.put("errno",2003);
            }
            if ((action==122 || action==124) && out.path("errno").asInt()==0) {
                ObjectNode change=result.putObject("change").put("idx",scene).put("pos",pos);
                change.set("info",visible(role,state,scene,spots).get(pos-1));
            }
        }
        mapper.save(id,ACCOUNT,state);
        return result;
    }

    /** 从角色待领采矿账本支付当日余额。
     * @param role 持锁角色
     * @param state 矿洞状态
     * @param result 资产推送数据
     */
    private void pay(LocalRole role,ObjectNode state,ObjectNode result) {
        int amount=MineClock.collect(state);
        if (amount>0) {
            roles.addCurrency(role.id,amount,0,0);
            result.put("award",amount).put("coins",roles.findById(role.id).orElse(role).coins);
        }
    }

    /** 生成 S431 的今日计时和占位快照。
     * @param state 已推进的账户
     * @return 原客户端矿洞状态
     */
    private ObjectNode panel(ObjectNode state) {
        ObjectNode out=json.createObjectNode().put("times",state.path("targets").size()).put("mining_sec",state.path("worked").asInt());
        JsonNode lease=state.path("lease");
        if (!lease.isMissingNode()) out.put("idx",lease.path("scene").asInt()).put("pos",lease.path("pos").asInt())
                .put("type",lease.path("type").asInt()).put("starttime",state.path("cursor").asLong())
                .put("timestamp",lease.path("started").asLong());
        return out;
    }

    /** 按原模板在十个真实矿位抽矿，冻结两名本机矿工签位。
     * @param owner 角色编号
     * @param scene 原矿区场景
     * @param day 本批营业日
     * @return 按场内序号排列的矿种、NPC编号和原阵容引用
     */
    private ArrayNode layout(String owner,int scene,long day) {
        Random random=new Random((31L*owner.hashCode()+scene)*1000003L+day);
        int first=random.nextInt(POSITIONS);
        int second=(first+1+random.nextInt(POSITIONS-1))%POSITIONS;
        int pick=random.nextInt(6);
        JsonNode pool=data.path("pools").path(Integer.toString(scene));
        ArrayNode list=json.createArrayNode();
        for (int i=0;i<POSITIONS;i++) {
            ObjectNode spot=list.addObject().put("type",pool.get(random.nextInt(pool.size())).asInt());
            if(i==first || i==second) {
                int roster=(pick+(i==first ? 0 : 1))%6;
                spot.put("roster",data.path("rosters").get(roster).asText())
                        .put("uid","npc:mine:"+day+":"+scene+":"+(i+1))
                        .put("name","[本机NPC] 矿工"+(roster+1));
            }
        }
        return list;
    }

    /** 保存当前营业日首次进入时的参照阵容，换队不会重抽驻守者。
     * @param role 当前角色
     * @param state 当前矿洞账户
     * @param scene 矿区场景
     * @return 含冻结 NPC 队伍的矿位列表
     */
    private ArrayNode board(LocalRole role,ObjectNode state,int scene) {
        String key="mine-board:"+scene;
        long day=state.path("day").asLong();
        ObjectNode board=mapper.load(role.id,key);
        boolean changed=board.path("day").asLong(-1)!=day;
        if (changed) {
            board.put("day",day);
            board.set("spots",layout(role.id,scene,day));
        }
        ArrayNode spots=(ArrayNode)board.path("spots");
        ArrayNode reference=null;
        for (JsonNode spot : spots) {
            if (!spot.has("uid") || spot.has("team")) continue;
            if (reference==null) reference=arena.players(role,ArenaService.ARENA,0,1);
            if (reference.isEmpty()) continue;
            ObjectNode team=combat.opposition(spot.path("roster").asText(),spot.path("uid").asText(),reference,
                    maps.path(Integer.toString(scene)).get(1).asInt(),reference.get(0).path("level").asInt());
            combat.addTrainingFallback(team);
            ((ObjectNode)spot).set("team",team);
            changed=true;
        }
        if (changed) mapper.save(role.id,key,board);
        return spots;
    }

    /** 检查当前矿洞账号是否占用指定矿位。
     * @param lease 已冻结的位置，可为空
     * @param scene 矿区
     * @param pos 序号
     * @return 是否为自己的矿位
     */
    private boolean at(JsonNode lease,int scene,int pos) {
        return lease!=null && lease.path("scene").asInt()==scene && lease.path("pos").asInt()==pos;
    }

    /** 确认本批 NPC 尚未被击败并腾出矿位。
     * @param state 角色矿洞状态
     * @param spot 当日矿位快照
     * @param scene 原矿区
     * @param pos 矿位序号
     * @return 是否为有效驻守者
     */
    private boolean occupied(ObjectNode state,JsonNode spot,int scene,int pos) {
        return spot.has("uid") && !state.path("vacant").path(scene+":"+pos).asBoolean();
    }

    /** 原矿表以每组十九个普通、十九个稀有排列。
     * @param type 原矿模板编号
     * @return 是否为稀有矿
     */
    private boolean isRare(int type) { return (type-1001)%38>=19; }

    /** 将实际占位转换为原协议一基数组。
     * @param role 当前角色
     * @param state 矿洞存档
     * @param scene 矿区
     * @param spots 原位序快照
     * @return 原 mineData 列表
     */
    private ArrayNode visible(LocalRole role,ObjectNode state,int scene,ArrayNode spots) {
        ArrayNode out=json.createArrayNode();
        JsonNode lease=state.get("lease");
        for (int i=0;i<spots.size();i++) {
            JsonNode spot=spots.get(i);
            ObjectNode row=out.addObject().put("type",spot.path("type").asInt());
            if (at(lease,scene,i+1)) {
                row.put("type",lease.path("type").asInt()).put("uid",role.id).put("name",role.name)
                        .put("sex",role.sex).put("vip",payments.vip(role.id).level).put("dress",0).put("power",lease.path("power").asLong())
                        .put("timestamp",lease.path("started").asLong());
            } else if (occupied(state,spot,scene,i+1)) {
                row.put("uid",spot.path("uid").asText()).put("name",spot.path("name").asText())
                        .put("sex",0).put("vip",0).put("dress",0).put("power",spot.path("team").path("power").asLong())
                        .put("timestamp",state.path("day").asLong()-1800);
            }
        }
        return out;
    }

    /** 对真实驻矿 NPC 建立一次战斗，记录每日次数和七日任务。
     * @param role 当前角色
     * @param state 矿洞账户
     * @param result 本次请求返回的数据
     * @param spot 选中的矿位
     * @param scene 所在矿区
     * @param pos 矿位
     * @param team 攻击者的实际队伍
     * @param target 原界面所见的 NPC 编号
     * @param now 当前时间戳
     */
    private void raid(LocalRole role,ObjectNode state,ObjectNode result,JsonNode spot,int scene,int pos,int team,String target,long now) {
        ObjectNode out=(ObjectNode)result.path("response");
        ObjectNode prior=mapper.load(role.id,ATTEMPT);
        if (MODE.equals(prior.path("mode").asText()) && now-prior.path("started").asLong()<=1800
                && prior.path("scene").asInt()==scene && prior.path("pos").asInt()==pos
                && prior.path("target").asText().equals(target) && prior.path("team").asInt()==team
                && !prior.has("advanced")) {
            opening(out,prior);
            result.set("panel",panel(state));
            return;
        }
        if (!occupied(state,spot,scene,pos) || !spot.has("team") || !spot.path("uid").asText().equals(target)
                || at(state.get("lease"),scene,pos) || state.path("targets").has(target) || state.path("targets").size()>=2
                || daily.otherFightActive(role.id) || daily.active(role.id) || space.active(role.id) || tower.active(role.id)
                || travel.active(role.id) || orange.active(role.id) || dojoStation.active(role.id)) {
            out.put("errno",2003); return;
        }
        ArrayNode players=arena.players(role,ArenaService.ARENA,0,team);
        if (players.isEmpty()) {out.put("errno",2003); return;}
        ObjectNode party=combat.formation(role.id,formations.formationId(role.id),false,players);
        combat.addTrainingFallback(party);
        String id=UUID.randomUUID().toString();
        ObjectNode fight=json.createObjectNode().put("id",id).put("mode",MODE).put("started",now)
                .put("seed",(id.hashCode()&Integer.MAX_VALUE)|1).put("weather",0).put("arenaDamageVersion",2)
                .put("day",state.path("day").asLong()).put("scene",scene).put("pos",pos).put("target",target)
                .put("team",team).put("roster",spot.path("roster").asText()).put("budget",1500);
        fight.set("player",party); fight.set("monster",spot.path("team").deepCopy());
        mapper.save(role.id,ATTEMPT,fight);
        state.with("targets").put(target,id);
        carnival.record(role.id,105,1);
        opening(out,fight);
        result.set("panel",panel(state));
    }

    /** 为初次挑战和未开打的重试返回同一冻结阵容。
     * @param out 原挑战应答
     * @param fight 已持久化的战斗
     */
    private void opening(ObjectNode out,ObjectNode fight) {
        out.put("seed",fight.path("seed").asInt()).put("weather",0);
        for (String side : new String[]{"player","monster"}) {
            ObjectNode wire=fight.path(side).deepCopy();
            for (JsonNode member : wire.path("pokemon")) ((ObjectNode)member).remove(Arrays.asList("goldCarry", "homeStatues","baseDamage","targetLimit","physical"));
            out.set(side,wire);
        }
    }

    /** 查询本机矿洞是否占有当前战斗通道。
     * @param id 角色编号
     * @return 是否有可继续的矿洞战斗
     */
    public boolean active(String id) {
        JsonNode fight=mapper.load(id,ATTEMPT);
        return MODE.equals(fight.path("mode").asText()) && Instant.now().getEpochSecond()-fight.path("started").asLong()<=1800;
    }

    /** 根据真实行动结算矿工退场、当日预算及金币。
     * @param id 角色编号
     * @param skill 选用技能
     * @param targets 实际选择的战斗成员编号
     * @param flag 自动或手动标记
     * @param retreat 是否主动退出
     * @return 本场战斗和矿位变化，未进行矿洞战斗时返回空
     */
    @Transactional
    public ObjectNode turn(String id,int skill,List<?> targets,int flag,boolean retreat) {
        LocalRole role=roles.lockRole(id);
        ObjectNode fight=mapper.load(id,ATTEMPT);
        long now=Instant.now().getEpochSecond();
        if (!MODE.equals(fight.path("mode").asText()) || now-fight.path("started").asLong()>1800) return null;
        int outcome=retreat ? 2 : combat.action(fight,skill,targets,flag);
        ObjectNode result=json.createObjectNode().put("outcome",outcome);
        if (outcome==0) mapper.save(id,ATTEMPT,fight.put("advanced",true));
        if (outcome<=0) return result;
        mapper.remove(id,ATTEMPT);
        ObjectNode state=mapper.load(id,ACCOUNT);
        MineClock.advance(state,now);
        if (outcome==1) {
            int budget=fight.path("budget").asInt();
            roles.addCurrency(id,budget,0,0);
            result.put("award",budget).put("coins",roles.findById(id).orElse(role).coins);
            if (state.path("day").asLong()==fight.path("day").asLong()) {
                int scene=fight.path("scene").asInt(), pos=fight.path("pos").asInt();
                state.with("vacant").put(scene+":"+pos,true);
                state.with("captures").put(scene+":"+pos,true);
                ObjectNode update=result.putObject("change").put("idx",scene).put("pos",pos);
                update.set("info",visible(role,state,scene,board(role,state,scene)).get(pos-1));
            }
        }
        mapper.save(id,ACCOUNT,state);
        result.set("panel",panel(state));
        return result;
    }
}
