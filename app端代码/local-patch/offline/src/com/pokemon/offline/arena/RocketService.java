package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.dailydup.DailyDungeonService;
import com.pokemon.offline.dojo.DojoStationService;
import com.pokemon.offline.elf.ElfTravelService;
import com.pokemon.offline.home.OrangeService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.space.SpaceService;
import com.pokemon.offline.tower.TowerService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 火箭队匹配、车轮战、每日次数及原赛季事务 Service。
 * @author tongruiyuan
 * @since 2026-11-20 12:47
 */
@Service
public class RocketService {
    private static final String MATCH="rocket:match";
    private final ArenaMapper store;
    private final RoleService roles;
    private final InventoryService inventory;
    private final RocketTeams teams;
    private final RocketSeason season;
    private final ObjectMapper json;
    private final BountyService bounty;
    private final DailyDungeonService daily;
    private final SpaceService space;
    private final TowerService tower;
    private final OrangeService orange;
    private final ElfTravelService travel;
    private final DojoStationService station;
    private final MineService mine;

    /**
     * 连接赛季账本、编队及共用原战斗协议的玩法。
     * @param store 竞技状态存档
     * @param roles 角色事务锁
     * @param inventory 钻石及道具
     * @param teams 三队出战校验及快照
     * @param season 赛季规则和累计
     * @param json 协议对象编码器
     * @param bounty 悬赏战斗状态
     * @param daily 日常副本及共用战斗状态
     * @param space 异次元状态
     * @param tower 洞穴状态
     * @param orange 群岛状态
     * @param travel 旅行状态
     * @param station 驻防状态
     * @param mine 矿洞争夺状态
     */
    RocketService(ArenaMapper store,RoleService roles,InventoryService inventory,RocketTeams teams,RocketSeason season,
                  ObjectMapper json,BountyService bounty,DailyDungeonService daily,SpaceService space,TowerService tower,
                  OrangeService orange,ElfTravelService travel,DojoStationService station,MineService mine) {
        this.store=store;this.roles=roles;this.inventory=inventory;this.teams=teams;this.season=season;this.json=json;
        this.bounty=bounty;this.daily=daily;this.space=space;this.tower=tower;this.orange=orange;this.travel=travel;this.station=station;this.mine=mine;
    }

    /**
     * 返回本地赛季起点。
     * @return 北京时间周一的 Unix 秒
     */
    public long week() {return season.week();}

    /**
     * 返回活动总览中当日仍可用于匹配的次数。
     * @param roleId 角色编号
     * @return 实际可用次数
     */
    public int remaining(String roleId) {
        if(season.phase(week())!=2)return 0;
        ObjectNode day=store.load(roleId,season.day());
        return Math.max(0,season.rules.path("free").asInt()+day.path("buy").asInt()-day.path("used").asInt());
    }

    /**
     * 读取本轮面板、任务和客户端显示日期。
     * @param role 当前角色
     * @return 原 S457 及 S100 任务
     */
    public ObjectNode panel(String role) {
        long week=week(); ObjectNode result=json.createObjectNode(),panel=season.previous(role,week);
        ObjectNode day=store.load(role,season.day());
        panel.put("times",day.path("used").asInt()).put("buytimes",day.path("buy").asInt())
                .put("season",season.number(week)).put("status",season.phase(week))
                .put("startutc",week+19*3600).put("endutc",week+6*86400+22*3600);
        panel.set("team",teams.load(role));result.set("panel",panel);result.set("tasks",season.tasks(role,week));return result;
    }

    /**
     * 检查是否有可恢复的本轮已开战挑战。
     * @param id 角色编号
     * @return 是否需由火箭队接管回合
     */
    public boolean active(String id) {
        ObjectNode match=store.load(id,MATCH);
        return match.has("fight") && match.path("week").asLong()==week() && match.path("expiry").asLong()>Instant.now().getEpochSecond();
    }

    /**
     * 登录时恢复旧引擎卡住的已扣次比赛，使开放期内的角色可从原预览重打当前小局。
     * @param roleId 角色编号
     */
    @Transactional
    public void reconnect(String roleId) {
        roles.lockRole(roleId);
        ObjectNode match = store.load(roleId, MATCH);
        if (!match.has("fight")) return;
        if (match.path("roundEngine").asInt() == 0) {
            if (match.path("week").asLong() == week() && season.phase(week()) == 2) {
                match.remove("fight");
                match.put("roundEngine", 1).put("expiry", Instant.now().getEpochSecond() + 1200);
                store.save(roleId, MATCH, match);
            } else {
                store.remove(roleId, MATCH);
            }
        } else if (active(roleId)) {
            turn(roleId, 0, Collections.emptyList(), -1, true);
        }
    }

    /**
     * 检查角色是否正在其他占用 C3001/C3002 的玩法中。
     * @param role 角色编号
     * @return 是否正在其他战斗
     */
    private boolean busy(String role) {
        return bounty.active(role)||daily.otherFightActive(role)||daily.active(role)||mine.active(role)||space.active(role)
                ||tower.active(role)||orange.active(role)||travel.active(role)||station.active(role);
    }

    /**
     * 校验操作时间、连接轮次和幂等凭据，在同一角色事务内变更。
     * @param id 认证角色标识
     * @param cmd 原请求号
     * @param input 原请求字段
     * @param preview 连接已展示的周起点
     * @param receipt 连接及请求联合凭据
     * @return 原协议响应
     */
    @Transactional
    public ObjectNode act(String id,int cmd,JsonNode input,long preview,String receipt) {
        LocalRole role=roles.lockRole(id);long week=week();ObjectNode out=json.createObjectNode().put("errno",1);
        if(role.level<55)return out;
        ObjectNode day=store.load(id,season.day());
        if(cmd==330)return season.ranking(role,week,input.path("page").asInt(1),input.path("size").asInt(20));
        if(cmd==334){out.put("errno",0);out.set("loglist",store.load(id,"rocket:logs").withArray("loglist"));return out;}
        if(preview!=week)return out;
        if(cmd==336)return out.put("errno",season.claim(id,week)?0:1);
        if(cmd==1201)return out.put("errno",season.task(id,week,input.path("taskid").asInt())?0:1).put("taskid",input.path("taskid").asInt());
        ObjectNode match=store.load(id,MATCH);
        long now=Instant.now().getEpochSecond();
        boolean pending=match.path("week").asLong()==week && match.path("expiry").asLong()>now;
        if(cmd==331) {
            int idx=input.path("idx").asInt();
            JsonNode slots=input.has("elflist")?input.get("elflist"):json.createArrayNode();
            if(!pending && teams.set(id,idx,input.path("tactics").asInt(1),slots))out.put("errno",0);
            return out.put("idx",idx);
        }
        if(season.phase(week)!=2)return out;
        if(cmd==335) {
            int number=day.path("buy").asInt();JsonNode costs=season.rules.path("buy");
            if(number>=costs.size() || day.path("receipts").has(receipt))return out;
            if(!inventory.spendMaterials(id,Collections.emptyMap(),0,costs.get(number).asInt()))return out.put("errno",100012);
            day.put("buy",number+1);day.with("receipts").put(receipt,true);store.save(id,season.day(),day);
            return out.put("errno",0);
        }
        if(cmd==332) {
            if(busy(id))return out;
            if(day.path("matched").has(receipt) && (!pending || !day.path("matched").path(receipt).asText().equals(match.path("id").asText())))return out;
            if(pending)return match.path("preview").deepCopy();
            int used=day.path("used").asInt();
            if(used>=season.rules.path("free").asInt()+day.path("buy").asInt())return out;
            match=teams.match(role,season.stats(id,week).path("rate").asInt(1),season.rules.path("pool"));
            if(match==null)return out;
            match.put("roundEngine",1).put("week",week).put("expiry",now+1200);
            day.put("used",used+1);day.with("matched").put(receipt,match.path("id").asText());store.save(id,season.day(),day);store.save(id,MATCH,match);
            return match.path("preview").deepCopy();
        }
        if(cmd==333 && pending && !busy(id)) {
            if(!match.has("fight") && !teams.next(match))return out;
            store.save(id,MATCH,match);return match.path("fight").deepCopy();
        }
        return out;
    }

    /**
     * 校验当前回合，向下一队传递实际余血，双方最后一队退出后才结算整场。
     * @param id 当前角色
     * @param skill 真实技能
     * @param targets 原客户端选择的目标
     * @param flag 手动或自动标志
     * @param quit 是否退出整场
     * @return 包含下一轮或最终结果的通知
     */
    @Transactional
    public ObjectNode turn(String id,int skill,List<?> targets,int flag,boolean quit) {
        LocalRole role=roles.lockRole(id);
        ObjectNode event=json.createObjectNode(),match=store.load(id,MATCH);
        boolean terminal = !quit && flag == 3;
        if (!match.has("fight") || !terminal && !active(id)) return event;
        // 全场身份、种子及成员必须先通过检查；迟到的上一场不能清掉当前场。
        int result = quit ? 2 : terminal ? teams.finishRound(match, skill, targets) : 0;
        if (result < 0 || !quit && (flag < -1 || flag > 1 && !terminal)) return event;
        if (match.path("week").asLong() != week() || season.phase(week()) != 2
                || match.path("expiry").asLong() <= Instant.now().getEpochSecond()) {
            long savedWeek = match.path("week").asLong();
            ObjectNode stats = season.stats(id, savedWeek);
            event.putObject("result").put("result",0).put("score",stats.path("score").asLong())
                    .put("rate",stats.path("rate").asInt(1)).put("rank",season.rank(id,savedWeek));
            store.remove(id,MATCH);
            return event;
        }
        // 原舞台负责技能、天赋、伤害与胜负。普通回合仅续期。
        match.put("expiry",Instant.now().getEpochSecond()+1200);
        if(result==0){store.save(id,MATCH,match);return event;}
        teams.carry(match,result);
        if(!quit && teams.next(match)) {
            store.save(id,MATCH,match);event.set("next",match.path("fight"));return event;
        }
        event.set("result",season.finish(role,week(),match,!quit && match.path("b").asInt()>=3));
        store.remove(id,MATCH);
        return event;
    }
}
