package com.pokemon.local.gateway;

import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.eden.EdenEncounterService;
import com.pokemon.local.tower.TowerService;
import com.pokemon.local.guild.GuildEncounterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.hangup.HangupMap;
import com.pokemon.local.quest.ClaimResult;
import com.pokemon.local.quest.QuestReward;
import com.pokemon.local.quest.QuestService;
import com.pokemon.local.quest.QuestSnapshot;
import com.pokemon.local.quest.api.entity.QuestTask;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.scene.LocalScene;
import com.pokemon.local.scene.SceneActor;
import com.pokemon.local.scene.SceneEncounterState;
import com.pokemon.local.scene.SceneService;
import com.pokemon.local.scene.TravelResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原游戏地图、动作、任务和章节消息适配
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:48
 */
@Service
final class WorldGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> ENCOUNTER_DATA = new TypeReference<Map<String, Object>>() {};

    private final FeastGameFeature feast;
    private final com.pokemon.local.arena.WorldBossService worldBoss;
    private final SceneService scenes;
    private final QuestService quests;
    private final com.pokemon.local.activitydaily.DailyMissionService dailyMissions;
    private final PassportGameFeature passport;
    private final RocketGameFeature rocket;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectProvider<SceneEncounterState> encounters;
    private final BattleGameFeature battle;
    private final HangupGameFeature hangup;
    private final DojoCombatService dojoCombat;
    private final GuildEncounterService guildEncounters;
    private final ObjectMapper json;
    private final BagSnapshot bags;
    private final EdenEncounterService eden;
    private final TowerService tower;
    private final TowerGameFeature towerPanel;
    private final HomeGameFeature homePanel;
    private final com.pokemon.local.activity.LuckyBagService luckyBag;
    private final com.pokemon.local.home.OrangeService orange;

    /**
     * 初始化游戏世界协议适配
     *
     * @param feast 主城节日麋鹿
     * @param worldBoss 原地图世界首领
     * @param scenes 场景存档与传送服务
     * @param quests 本地剧情与任务服务
     * @param dailyMissions 原每日任务真实进度
     * @param roles 角色存档服务
     * @param inventory 背包和物品类型
     * @param encounters 已击败怪物组数据源
     * @param battle 副本当前状态推送
     * @param hangup 冒险挂机地图快照
     * @param dojoCombat 道馆场景击杀记录
     * @param guildEncounters 场景强盗和强盗首领状态
     * @param json 场景协议投影工具
     * @param bags 任务奖励发放后的背包快照
     * @param eden 神兽岛动态目标
     * @param tower 洞穴守卫和宝箱
     * @param towerPanel 原洞穴楼层推送
     * @param homePanel 家园饲育屋状态
     * @param rocket 火箭队赛季任务
     * @param passport 通行证任务
     * @param luckyBag 福袋每日任务
     * @param orange 群岛守卫
     */
    WorldGameFeature(FeastGameFeature feast, com.pokemon.local.arena.WorldBossService worldBoss, SceneService scenes, QuestService quests, com.pokemon.local.activitydaily.DailyMissionService dailyMissions, RoleService roles, InventoryService inventory,
                     ObjectProvider<SceneEncounterState> encounters, BattleGameFeature battle, HangupGameFeature hangup,
                     DojoCombatService dojoCombat, GuildEncounterService guildEncounters, ObjectMapper json, EdenEncounterService eden, TowerService tower, TowerGameFeature towerPanel, HomeGameFeature homePanel, com.pokemon.local.home.OrangeService orange,
                     BagSnapshot bags, com.pokemon.local.activity.LuckyBagService luckyBag, PassportGameFeature passport, RocketGameFeature rocket) {
        this.rocket=rocket;
        this.passport = passport;
        this.luckyBag = luckyBag;
        this.feast = feast;
        this.worldBoss=worldBoss;
        this.scenes = scenes;
        this.quests = quests;
        this.dailyMissions = dailyMissions;
        this.roles = roles;
        this.inventory = inventory;
        this.encounters = encounters;
        this.battle = battle;
        this.hangup = hangup;
        this.dojoCombat = dojoCombat;
        this.guildEncounters = guildEncounters;
        this.json = json;
        this.bags = bags;
        this.eden = eden;
        this.tower = tower;
        this.towerPanel=towerPanel;
        this.homePanel=homePanel;
        this.orange=orange;
    }

    /**
     * 处理已登录角色的本地地图与任务操作
     *
     * @param role 当前连接认证的角色
     * @param request 解码后的请求
     * @param output 原协议应答与推送
     * @return 是否为本功能的请求
     * @throws IOException 发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case 5:
                move(role, request);
                return true;
            case 6:
                stop(role, request, output);
                return true;
            case 7:
                // 原查询只获取同屏其他角色，单人场景没有其他角色可推送。
                return true;
            case 8:
                travel(role, request, output, number(request.body, "id", 0));
                return true;
            case 3040:
                HangupMap target = HangupMap.byId(number(request.body, "sid", 0));
                if (target == null) {
                    output.send(request, data("errno", SceneService.INVALID_PORTAL));
                } else {
                    travel(role, request, output, target.door);
                }
                return true;
            case 2210:
                // 已接入的场景没有由服务端生成的可消费场景事件。
                output.send(request, data("errno", SceneService.UNKNOWN_EVENT));
                return true;
            case 1201:
                long taskId = number(request.body, "taskid", 0);
                if (taskId >= 50001 && taskId <= 50018) {
                    passport.claimTask(role, request, output, (int) taskId);
                    return true;
                }
                sendClaim(role, request, output, "taskid", taskId, quests.submitTask(role.id, taskId),
                        "task.task_submit");
                return true;
            case 1202:
                long chapter = number(request.body, "idx", 0);
                sendClaim(role, request, output, "idx", chapter, quests.claimChapter(role.id, chapter),
                        "task.chapter_submit");
                return true;
            default:
                return false;
        }
    }

    /**
     * 推送真实首章和主线任务快照
     *
     * @param role 当前连接认证的角色
     * @param output 原协议推送出口
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        QuestSnapshot snapshot = quests.snapshot(role.id);
        List<Map<String, Object>> list = new ArrayList<>();
        for (QuestTask task : snapshot.tasks) {
            list.add(data("id", task.id, "state", task.state, "progress", task.progress));
        }
        luckyBag.participate(role.id,61000);
        list.addAll(luckyBag.tasks(luckyBag.panel(role)));
        list.addAll(passport.currentTasks(role));
        list.addAll(rocket.tasks(role));
        list.addAll(dailyMissions.snapshot(role.id));
        output.push(100, data("list", list, "chapter", snapshot.chapter));
    }

    /**
     * 从已保存的位置推送当前场景、NPC 和还未击败的怪物
     *
     * @param role 已认证角色或最新的传送后存档
     * @param output 原协议推送出口
     * @throws IOException 发送失败
     */
    void synchronizeScene(LocalRole role, GameReply output) throws IOException {
        LocalRole saved = roles.findById(role.id).orElse(role);
        LocalScene scene = scenes.findScene(saved.sceneId).orElse(null);
        List<Map<String, Object>> npcs = new ArrayList<>();
        List<Map<String, Object>> monsters = new ArrayList<>();
        if (scene != null) {
            for (SceneActor npc : scene.npcs) {
                npcs.add(actor(npc));
            }
            SceneEncounterState cleared = encounters.getIfAvailable();
            Set<Integer> clearedIds = scene.id == SceneService.FIRST_DUNGEON && cleared != null
                    ? cleared.clearedEncounters(saved.id) : Collections.emptySet();
            for (SceneActor monster : scene.monsters) {
                if (!clearedIds.contains(monster.sid) && !dojoCombat.cleared(saved.id, scene.id, monster.sid)) {
                    monsters.add(actor(monster));
                }
            }
        }
        for (JsonNode actor : feast.scene(saved, output)) {
            monsters.add(json.convertValue(actor, ENCOUNTER_DATA));
        }
        for (JsonNode actor : eden.sceneActors(saved)) {
            monsters.add(json.convertValue(actor, ENCOUNTER_DATA));
        }
        List<Map<String,Object>> others=new ArrayList<>();
        for(JsonNode actor:orange.actors(saved)){List<Map<String,Object>> target=actor.path("sid").asInt()<830000?others:monsters;target.add(json.convertValue(actor,ENCOUNTER_DATA));}
        for(JsonNode actor:tower.actors(saved,false)) monsters.add(json.convertValue(actor,ENCOUNTER_DATA));
        for(JsonNode actor:tower.actors(saved,true)) others.add(json.convertValue(actor,ENCOUNTER_DATA));
        for (JsonNode actor : guildEncounters.actors(saved, false)) {
            monsters.add(json.convertValue(actor, ENCOUNTER_DATA));
        }
        List<Map<String, Object>> bandits = new ArrayList<>();
        for (JsonNode actor : guildEncounters.actors(saved, true)) {
            bandits.add(json.convertValue(actor, ENCOUNTER_DATA));
        }
        output.push(4, data("id", saved.sceneId, "pos", position(saved.x, saved.y),
                "npcs", npcs, "monsters", monsters, "others", others,
                "worldboss", json.convertValue(worldBoss.scene(saved),List.class), "guildbandit", bandits,
                "repnpc", Collections.emptyList()));
    }

    /**
     * 解码起点和终点并更新已接受的移动起点
     *
     * @param role 当前认证角色
     * @param request 客户端移动请求
     */
    private void move(LocalRole role, SprotoProtocol.Request request) {
        Map<String, Object> start = structure(request.body, "start");
        Map<String, Object> goal = structure(request.body, "goal");
        scenes.startMove(role.id, number(request.body, "id", -1),
                number(start, "x", -1), number(start, "y", -1),
                number(goal, "x", -1), number(goal, "y", -1));
    }

    /**
     * 保存停步坐标，失效时将本地视图校正到服务器存档
     *
     * @param role 当前认证角色
     * @param request 客户端停步请求
     * @param output 原协议推送出口
     * @throws IOException 发送失败
     */
    private void stop(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Map<String, Object> point = structure(request.body, "pos");
        Optional<LocalRole> stored = scenes.stopMove(role.id, number(request.body, "id", -1),
                number(point, "x", -1), number(point, "y", -1));
        if (!stored.isPresent()) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(20, data("id", saved.sceneId, "pos", position(saved.x, saved.y)));
        }
    }

    /**
     * 确认可用传送配置并推送提交后的新场景
     *
     * @param role 当前认证角色
     * @param request 原游戏传送请求
     * @param output 原协议应答和推送
     * @param portalId 传送配置编号
     * @throws IOException 发送失败
     */
    private void travel(LocalRole role, SprotoProtocol.Request request, GameReply output, long portalId)
            throws IOException {
        TravelResult result = scenes.travel(role.id, portalId);
        output.send(request, data("errno", result.errno));
        if (result.errno == 0) {
            if (HangupMap.byDoor(result.role.sceneId) != null) {
                hangup.push(role.id, output);
            }
            battle.pushDungeon(result.role, output);
            if (portalId==1045 || TowerService.isScene(role.sceneId) || portalId==1103) towerPanel.synchronize(result.role,output);
            if(portalId==230014)output.push(111,data("list",Collections.emptyList(),"isNull",true));
            homePanel.synchronize(result.role,output);
            if(result.role.sceneId==80011 || com.pokemon.local.home.OrangeService.scene(result.role.sceneId)){
                output.push(827,json.convertValue(orange.snapshot(result.role.id),ENCOUNTER_DATA));
            }
            synchronizeScene(result.role, output);
        }
    }

    /**
     * 发送结算结果，只有新发放的资产才进入奖励推送
     *
     * @param role 当前认证角色
     * @param request 原游戏领奖请求
     * @param output 原协议消息出口
     * @param key 请求中使用的编号字段
     * @param id 请求提交的任务或章节编号
     * @param result 已提交的领取结果
     * @param flags 原协议奖励用途标识
     * @throws IOException 发送失败
     */
    private void sendClaim(LocalRole role, SprotoProtocol.Request request, GameReply output, String key, long id,
                           ClaimResult result, String flags) throws IOException {
        output.send(request, data("errno", result.errno, key, id));
        if (!result.changed) {
            return;
        }
        QuestReward reward = result.reward;
        List<Map<String, Object>> items = new ArrayList<>();
        reward.items.forEach((itemId, quantity) -> items.add(data(
                "id", itemId, "n", quantity, "sid", Integer.toString(itemId))));
        output.push(14, data("flags", flags, "adds", data("coins", reward.coins, "diamonds", reward.diamonds,
                "exp", reward.exp, "items", items)));
        if (!reward.items.isEmpty() || !reward.equipment.isEmpty()) {
            bags.push(role.id, output);
        }
        synchronize(role, output);
    }

    /**
     * 从已解码字段中读取嵌套的 Sproto 结构
     *
     * @param body 请求字段
     * @param key 嵌套字段名
     * @return 已解码结构，缺省时为空映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> structure(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    /**
     * 从底包实体记录构造场景推送字段
     *
     * @param actor 场景 NPC 或怪物记录
     * @return 原协议对象字段
     */
    private Map<String, Object> actor(SceneActor actor) {
        return data("sid", actor.sid, "uid", actor.uid, "pos", position(actor.x, actor.y),
                "face", actor.face, "state", 0, "iscommon", true);
    }

    /**
     * 把服务器单位的坐标转成原协议结构
     *
     * @param x 横坐标
     * @param y 纵坐标
     * @return 位置结构
     */
    private Map<String, Object> position(int x, int y) {
        return data("x", x, "y", y);
    }
}
