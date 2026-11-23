package com.pokemon.offline.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.arena.DnfService;
import com.pokemon.offline.dojo.DojoConfig;
import com.pokemon.offline.dojo.DojoService;
import com.pokemon.offline.guild.PersonalGuildService;
import com.pokemon.offline.hangup.HangupMap;
import com.pokemon.offline.eden.EdenService;
import com.pokemon.offline.tower.TowerService;
import com.pokemon.offline.platform.beans.factory.ObjectProvider;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.io.IOException;
import java.io.InputStream;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地研究所、主城与首个副本的地图服务
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
@Service
public class SceneService {

    public static final int LAB = 71001;
    public static final int CITY = 71002;
    public static final int FIRST_DUNGEON = 20020;
    public static final int GUILD_HALL = 10018;
    public static final int GUILD_DUNGEON = 72401;
    public static final int CELL_SIZE = 32;

    public static final int INVALID_PORTAL = 100045;
    public static final int CLOSED_PORTAL = 100046;
    public static final int UNKNOWN_EVENT = 100059;

    private final RoleService roles;
    private final AdventureProgressService adventure;
    private final HangupService hangup;
    private final DojoConfig dojoConfig;
    private final DojoService dojo;
    private final PersonalGuildService guilds;
    private final EdenService eden;
    private final com.pokemon.offline.home.HomeService home;
    private final ObjectProvider<com.pokemon.offline.home.OrangeService> orange;
    private final ObjectProvider<TowerService> tower;
    private final ObjectProvider<DnfService> dnf;
    private final Map<Integer, LocalScene> scenes = new HashMap<>();
    private final Map<Long, Integer> minePortals = new HashMap<>();
    private final Map<Integer, JsonNode> mineMaps = new HashMap<>();

    /**
     * 载入底包 scene、fly 配置及实际 TileMap 尺寸
     *
     * @param roles 角色位置和返回点存档服务
     * @param adventure 当前冒险进度，用于挂机地图解锁
     * @param hangup 冒险挂机记录
     * @param dojoConfig 底包道馆与传送配置
     * @param dojo 道馆进度及门槛
     * @param guilds 本机公会成员关系
     * @param eden 神兽岛的开放窗口和等级
     * @param tower 洞穴楼层和实时进度
     * @param home 家园首次进入存档
     * @param orange 群岛地图进度
     * @param dnf 地下城资格和当前层数
     * @param json 矿洞场景配置解析器
     * @throws IOException 随包地图配置不可读
     */
    public SceneService(RoleService roles, AdventureProgressService adventure, HangupService hangup,
                        DojoConfig dojoConfig, DojoService dojo, PersonalGuildService guilds, EdenService eden, ObjectProvider<TowerService> tower, com.pokemon.offline.home.HomeService home,ObjectProvider<com.pokemon.offline.home.OrangeService> orange, ObjectProvider<DnfService> dnf, ObjectMapper json) throws IOException {
        this.roles = roles;
        this.adventure = adventure;
        this.hangup = hangup;
        this.dojoConfig = dojoConfig;
        this.dojo = dojo;
        this.guilds = guilds;
        this.eden = eden;
        this.tower = tower;
        this.home = home;
        this.orange=orange;
        this.dnf = dnf;
        for (int id = 12000; id <= 12004; id++) {
            scenes.put(id, new LocalScene(id, 23, id == 12001 ? 38 : 36,
                    Collections.emptyList(), Collections.emptyList()));
        }
        for(int island=0;island<8;island++)for(int room=1;room<=5;room++){int id=81000+island*100+room;scenes.put(id,new LocalScene(id,30,40,Collections.emptyList(),Collections.emptyList()));}
        scenes.put(80011,new LocalScene(80011,30,50,Collections.singletonList(new SceneActor(10102,"80011-10102",5,43,0)),Collections.emptyList()));
        scenes.put(80012,new LocalScene(80012,30,20,Collections.singletonList(new SceneActor(10101,"80012-10101",25,5,1)),Collections.emptyList()));

        scenes.put(40001,new LocalScene(40001,38,33,Collections.emptyList(),Collections.emptyList()));
        scenes.put(LAB, new LocalScene(LAB, 17, 21, Collections.singletonList(
                new SceneActor(2007, LAB + "-2007", 9, 11, 0)), Collections.emptyList()));
        scenes.put(CITY, new LocalScene(CITY, 50, 45, Arrays.asList(
                new SceneActor(2113, CITY + "-2113", 13, 24, 0),
                new SceneActor(2042, CITY + "-2042", 38, 24, 0),
                new SceneActor(2115, CITY + "-2115", 33, 34, 0)), Collections.emptyList()));
        scenes.put(GUILD_HALL, new LocalScene(GUILD_HALL, 41, 34,
                Arrays.asList(
                        new SceneActor(2036, GUILD_HALL + "-2036", 25, 27, 0),
                        new SceneActor(2037, GUILD_HALL + "-2037", 33, 22, 0),
                        new SceneActor(2038, GUILD_HALL + "-2038", 32, 10, 2),
                        new SceneActor(2035, GUILD_HALL + "-2035", 29, 14, 0),
                        new SceneActor(2116, GUILD_HALL + "-2116", 24, 21, 0),
                        new SceneActor(2028, GUILD_HALL + "-2028", 10, 23, 3),
                        new SceneActor(2029, GUILD_HALL + "-2029", 15, 13, 0),
                        new SceneActor(2030, GUILD_HALL + "-2030", 16, 12, 1),
                        new SceneActor(2031, GUILD_HALL + "-2031", 9, 21, 2),
                        new SceneActor(2032, GUILD_HALL + "-2032", 7, 19, 3),
                        new SceneActor(2033, GUILD_HALL + "-2033", 23, 16, 3),
                        new SceneActor(2034, GUILD_HALL + "-2034", 14, 7, 1)), Collections.emptyList()));
        scenes.put(GUILD_DUNGEON, new LocalScene(GUILD_DUNGEON, 51, 49,
                Collections.emptyList(), Collections.emptyList()));
        scenes.put(FIRST_DUNGEON, new LocalScene(FIRST_DUNGEON, 31, 38, Collections.emptyList(), Arrays.asList(
                monster(110007, 15, 7), monster(110008, 22, 14), monster(110009, 17, 18),
                monster(110010, 15, 26), monster(110012, 15, 32))));
        for (int land = 1; land <= 3; land++) {
            scenes.put(80000 + land, new LocalScene(80000 + land, 32, land == 2 ? 33 : 32,
                    Collections.emptyList(), Collections.emptyList()));
        }
        for (int map=11001;map<=11010;map++) {
            scenes.put(map,new LocalScene(map,17,31,Collections.emptyList(),Collections.emptyList()));
        }
        for (HangupMap map : HangupMap.values()) {
            scenes.put(map.door, new LocalScene(map.door, map.width, map.height,
                    Collections.emptyList(), Collections.emptyList()));
        }
        try (InputStream stream = new ClassPathResource("scene/mine-maps.json").getInputStream()) {
            json.readTree(stream).fields().forEachRemaining(entry -> {
                int scene = Integer.parseInt(entry.getKey());
                JsonNode config = entry.getValue();
                minePortals.put(config.get(0).asLong(), scene);
                mineMaps.put(scene, config);
                scenes.put(scene, new LocalScene(scene, config.get(5).asInt(), config.get(6).asInt(),
                        Collections.emptyList(), Collections.emptyList()));
            });
        }
        dojoConfig.dimensions().fields().forEachRemaining(entry -> {
            int sceneId = Integer.parseInt(entry.getKey());
            int dojoId = sceneId - 71100;
            JsonNode stage = dojoConfig.stage(dojoId);
            List<SceneActor> monsters = new ArrayList<>();
            if (stage != null) {
                JsonNode groups = stage.get(3);
                for (int i = 0; i < groups.size(); i++) {
                    JsonNode group = groups.get(i);
                    monsters.add(new SceneActor(group.get(0).asInt(), dojoConfig.groupInstanceId(dojoId, i),
                            group.get(1).asInt(), group.get(2).asInt(), 0));
                }
            }
            scenes.put(sceneId, new LocalScene(sceneId, entry.getValue().get(0).asInt(),
                    entry.getValue().get(1).asInt(), Collections.emptyList(), monsters));
        });
    }

    /**
     * 查询已接入的场景配置
     *
     * @param sceneId 场景编号
     * @return 客户端地图、NPC 和怪物配置
     */
    public Optional<LocalScene> findScene(int sceneId) {
        return Optional.ofNullable(scenes.get(sceneId));
    }

    /**
     * 按角色的实际所在场景记录已验证的移动起点
     *
     * @param roleId 当前角色
     * @param sceneId 请求中的场景
     * @param startX 起点横坐标
     * @param startY 起点纵坐标
     * @param goalX 目标横坐标
     * @param goalY 目标纵坐标
     * @return 保存后的角色，无效请求时返回空值
     */
    @Transactional
    public Optional<LocalRole> startMove(String roleId, long sceneId, long startX, long startY, long goalX, long goalY) {
        LocalRole role = roles.lockRole(roleId);
        LocalScene scene = scenes.get(role.sceneId);
        if (scene == null || role.sceneId != sceneId
                || !scene.contains(startX, startY) || !scene.contains(goalX, goalY)) {
            return Optional.empty();
        }
        return Optional.of(savePosition(role, (int) startX, (int) startY));
    }

    /**
     * 确认停步并更新同一场景内的位置
     *
     * @param roleId 当前角色
     * @param sceneId 请求中的场景
     * @param x 停步横坐标
     * @param y 停步纵坐标
     * @return 保存后的角色，无效请求时返回空值
     */
    @Transactional
    public Optional<LocalRole> stopMove(String roleId, long sceneId, long x, long y) {
        LocalRole role = roles.lockRole(roleId);
        LocalScene scene = scenes.get(role.sceneId);
        if (scene == null || role.sceneId != sceneId || !scene.contains(x, y)) {
            return Optional.empty();
        }
        return Optional.of(savePosition(role, (int) x, (int) y));
    }

    /**
     * 根据原传送配置与最新持久化场景执行单人传送
     *
     * @param roleId 当前角色
     * @param portalId 客户端传入的原游戏传送编号
     * @return 传送结果及落点
     */
    @Transactional
    public TravelResult travel(String roleId, long portalId) {
        LocalRole role = roles.lockRole(roleId);
        boolean onHangupMap = HangupMap.byDoor(role.sceneId) != null;
        boolean inDnf = role.sceneId >= 12000 && role.sceneId <= 12004;
        if (portalId == 1047 && (inDnf || role.sceneId == CITY || onHangupMap)) {
            if (!DnfService.open(role)) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            int floor = dnf.getIfAvailable().mission(role.id);
            int target = 12000 + Math.min(4, (floor - 1) / 100);
            return new TravelResult(0, roles.updatePosition(role.id, target, 3 * CELL_SIZE, 8 * CELL_SIZE, null, null, null));
        }
        if (inDnf && (portalId == 1102 || portalId == 1103)) {
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE, null, null, null));
        }
        boolean inMine = mineMaps.containsKey(role.sceneId);
        Integer mine = minePortals.get(portalId);
        if (mine != null) {
            JsonNode map = mineMaps.get(mine);
            if (!(role.sceneId == CITY || onHangupMap || inMine) || role.stage < map.get(1).asInt()
                    || role.level < Math.max(25, map.get(2).asInt())) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            return new TravelResult(0, roles.updatePosition(role.id, mine, map.get(3).asInt() * CELL_SIZE,
                    map.get(4).asInt() * CELL_SIZE, null, null, null));
        }
        if (inMine && portalId == 1103) {
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE, null, null, null));
        }
        boolean onIsland = role.sceneId >= 80001 && role.sceneId <= 80003;
        if(com.pokemon.offline.home.OrangeService.scene(role.sceneId)){
            LocalRole dest=orange.getIfAvailable().travel(role,portalId);
            return new TravelResult(dest==null?CLOSED_PORTAL:0,dest==null?role:dest);
        }
        if(portalId==230011 && role.level>=70 && (role.sceneId==CITY || onHangupMap)){
            home.enter(role.id);
            return new TravelResult(0,roles.updatePosition(role.id,80011,10*CELL_SIZE,5*CELL_SIZE,null,null,null));
        }
        if(role.sceneId==80011 && portalId==230012)
            return new TravelResult(0,roles.updatePosition(role.id,80012,15*CELL_SIZE,6*CELL_SIZE,null,null,null));
        if(role.sceneId==80012 && portalId==230013)
            return new TravelResult(0,roles.updatePosition(role.id,80011,22*CELL_SIZE,8*CELL_SIZE,null,null,null));
        if(role.sceneId==80011 && portalId==230015)
            return new TravelResult(0,roles.updatePosition(role.id,80011,25*CELL_SIZE/2,65*CELL_SIZE/2,null,null,null));
        if((role.sceneId==80011||role.sceneId==80012) && portalId==1102)
            return new TravelResult(0,roles.updatePosition(role.id,CITY,26*CELL_SIZE,33*CELL_SIZE,null,null,null));
        if (portalId == 1045 && (role.sceneId == CITY || onHangupMap || TowerService.isScene(role.sceneId))) {
            int scene=tower.getIfAvailable().travel(role,false);
            if(scene==0) return new TravelResult(CLOSED_PORTAL,role);
            return new TravelResult(0,roles.updatePosition(role.id,scene,8*CELL_SIZE,14*CELL_SIZE,null,null,null));
        }
        if(portalId==1103 && TowerService.isScene(role.sceneId)) {
            if(tower.getIfAvailable().travel(role,true)==0) return new TravelResult(CLOSED_PORTAL,role);
            return new TravelResult(0,roles.updatePosition(role.id,CITY,26*CELL_SIZE,33*CELL_SIZE,null,null,null));
        }
        if (portalId >= 10001 && portalId <= 10003) {
            int island = (int) portalId - 10000;
            if (!eden.open() || !eden.unlocked(role,island) || !(role.sceneId == CITY || onHangupMap || onIsland)) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            return new TravelResult(0, roles.updatePosition(role.id, 80000 + island, 13 * CELL_SIZE, 14 * CELL_SIZE,
                    null, null, null));
        }
        if (portalId == 1103 && onIsland) {
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE,
                    null, null, null));
        }
        if (portalId == 1033 && (role.sceneId == CITY || onHangupMap) && guilds.active(roleId) != null) {
            return new TravelResult(0, roles.updatePosition(role.id, GUILD_HALL, 21 * CELL_SIZE, 8 * CELL_SIZE,
                    role.sceneId, role.x, role.y));
        }
        if ((portalId == 1102 && role.sceneId == CITY)
                || (portalId == 230001 && role.sceneId == GUILD_DUNGEON && guilds.active(roleId) != null)
                || (portalId == 230002 && role.sceneId == GUILD_HALL)) {
            return new TravelResult(0, role);
        }
        if (portalId == 1102 && (role.sceneId == GUILD_HALL || role.sceneId == GUILD_DUNGEON)) {
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE,
                    null, null, null));
        }
        if ((portalId == 1034 || portalId == 1103) && role.sceneId == GUILD_HALL) {
            int scene = portalId == 1034 && role.returnSceneId != null ? role.returnSceneId : CITY;
            int x = portalId == 1034 && role.returnX != null ? role.returnX : 26 * CELL_SIZE;
            int y = portalId == 1034 && role.returnY != null ? role.returnY : 33 * CELL_SIZE;
            return new TravelResult(0, roles.updatePosition(role.id, scene, x, y, null, null, null));
        }
        if (portalId == 1103 && onHangupMap) {
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE,
                    null, null, null));
        }
        if (portalId == 230001 && role.sceneId == GUILD_HALL && guilds.active(roleId) != null) {
            return new TravelResult(0, roles.updatePosition(role.id, GUILD_DUNGEON, 16 * CELL_SIZE, 4 * CELL_SIZE,
                    role.returnSceneId, role.returnX, role.returnY));
        }
        if (portalId == 230002 && role.sceneId == GUILD_DUNGEON) {
            return new TravelResult(0, roles.updatePosition(role.id, GUILD_HALL, 9 * CELL_SIZE, 10 * CELL_SIZE,
                    role.returnSceneId, role.returnX, role.returnY));
        }
        if (portalId == 1102 && (role.sceneId == LAB || onHangupMap || dojoConfig.dimensions().has(
                Integer.toString(role.sceneId)))) {
            // 原研究所传送阵位于 (8, 0)，允许进入阵列时与停步包的少量坐标偏差。
            if (role.sceneId == LAB && (Math.abs(role.x - 8 * CELL_SIZE) > 3 * CELL_SIZE || role.y > 3 * CELL_SIZE)) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            return new TravelResult(0, roles.updatePosition(role.id, CITY, 26 * CELL_SIZE, 33 * CELL_SIZE,
                    null, null, null));
        }
        HangupMap target = HangupMap.byDoor(portalId);
        if (target != null && (role.sceneId == CITY || onHangupMap
                || role.sceneId == GUILD_HALL || role.sceneId == GUILD_DUNGEON)) {
            if (adventure.nextMission(role.id) < target.requiredMission || role.stage < target.requiredWorld) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            hangup.activate(role.id, target);
            if (role.sceneId == target.door) {
                return new TravelResult(0, role);
            }
            return new TravelResult(0, roles.updatePosition(role.id, target.door, target.x * CELL_SIZE,
                    target.y * CELL_SIZE, null, null, null));
        }
        if (portalId == 200020 && (role.sceneId == CITY || onHangupMap)) {
            return new TravelResult(0, roles.updatePosition(role.id, FIRST_DUNGEON, 13 * CELL_SIZE, 2 * CELL_SIZE,
                    role.sceneId, role.x, role.y));
        }
        if (portalId == 200000 && role.sceneId == FIRST_DUNGEON) {
            if (role.returnSceneId == null || role.returnX == null || role.returnY == null) {
                return new TravelResult(CLOSED_PORTAL, role);
            }
            return new TravelResult(0, roles.updatePosition(role.id, role.returnSceneId, role.returnX, role.returnY,
                    null, null, null));
        }
        for (JsonNode door : dojoConfig.portals()) {
            // 原道馆页面首次加载会请求所选分馆的出口门，以进入该地区总馆。
            boolean dojoHallEntry = door.get(5).asInt() == 0 && door.get(2).asInt() >= 72001
                    && door.get(2).asInt() <= 72008 && (door.get(0).asInt() == 220000
                    || door.get(1).asInt() >= 72101 && door.get(1).asInt() <= 72148);
            if (door.get(0).asInt() == portalId && (door.get(1).asInt() == role.sceneId
                    || dojoHallEntry && (role.sceneId == CITY || onHangupMap))) {
                int targetDojo = door.get(5).asInt();
                JsonNode stage = dojoConfig.stage(targetDojo);
                if (dojoHallEntry && door.get(1).asInt() != role.sceneId && door.get(1).asInt() >= 72101) {
                    int selectedDojo = door.get(1).asInt() - 71100;
                    JsonNode selectedStage = dojoConfig.stage(selectedDojo);
                    if (selectedStage == null || role.level < selectedStage.get(1).asInt()
                            || dojo.snapshot(role.id).dojo < selectedDojo) {
                        return new TravelResult(CLOSED_PORTAL, role);
                    }
                }
                if ((dojoHallEntry && role.level < 15)
                        || (targetDojo > 0 && (stage == null || role.level < stage.get(1).asInt()
                        || dojo.snapshot(role.id).dojo < targetDojo))) {
                    return new TravelResult(CLOSED_PORTAL, role);
                }
                return new TravelResult(0, roles.updatePosition(role.id, door.get(2).asInt(),
                        door.get(3).asInt() * CELL_SIZE, door.get(4).asInt() * CELL_SIZE, null, null, null));
            }
        }
        return new TravelResult(INVALID_PORTAL, role);
    }

    /**
     * 保存同场景坐标并保留已有副本返回点
     *
     * @param role 持有事务锁的角色
     * @param x 要保存的横坐标
     * @param y 要保存的纵坐标
     * @return 最新角色
     */
    private LocalRole savePosition(LocalRole role, int x, int y) {
        if (role.x == x && role.y == y) {
            return role;
        }
        return roles.updatePosition(role.id, role.sceneId, x, y, role.returnSceneId, role.returnX, role.returnY);
    }

    /**
     * 为首个副本的原始怪物组构造跨会话稳定的实例编号
     *
     * @param sid 原始怪物组编号
     * @param x 格点横坐标
     * @param y 格点纵坐标
     * @return 地图怪物对象
     */
    private SceneActor monster(int sid, int x, int y) {
        return new SceneActor(sid, "dungeon-" + FIRST_DUNGEON + "-" + sid, x, y, 0);
    }
}
