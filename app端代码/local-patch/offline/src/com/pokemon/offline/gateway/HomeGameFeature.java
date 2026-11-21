package com.pokemon.offline.gateway;

import com.pokemon.offline.home.HomeService;
import com.pokemon.offline.home.HomeBuildingService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 家园饲育屋状态和心情道具原协议。
 * @author tongruiyuan
 * @since 2026-11-19 19:52
 */
@Service
@Order(-49)
final class HomeGameFeature implements GameFeature {
    private final com.pokemon.offline.home.LinkGameService links;
    private final com.pokemon.offline.home.HomeRaceService races;
    private final HomeService home;
    private final HomeBuildingService buildings;
    private final com.pokemon.offline.home.BreedingService breeding;
    private final com.pokemon.offline.home.RelicPuzzleService puzzle;
    private final com.pokemon.offline.role.RoleService roles;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> BODY = new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
    };
    private final BagSnapshot bags;
    private final Map<GameReply, String> connections = new WeakHashMap<>();
    private final Map<GameReply, Long> relicDays = Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定家园及道具快照。
     * @param home 家园状态
     * @param buildings 家园建筑和采收
     * @param races 自行车比赛
     * @param links 神塔遗迹翻牌
     * @param breeding 寄存、取蛋及孵化状态
     * @param bags 背包同步
     * @param puzzle 古代遗迹拼图
     * @param roles 钻石钱包
     * @param json 消息解码
     */
    HomeGameFeature(com.pokemon.offline.home.HomeRaceService races, com.pokemon.offline.home.LinkGameService links, HomeService home, HomeBuildingService buildings,
            com.pokemon.offline.home.BreedingService breeding, BagSnapshot bags,
            com.pokemon.offline.home.RelicPuzzleService puzzle, com.pokemon.offline.role.RoleService roles,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.races = races;
        this.links = links;
        this.home = home;
        this.buildings = buildings;
        this.breeding = breeding;
        this.bags = bags;
        this.puzzle = puzzle;
        this.roles = roles;
        this.json = json;
    }

    /** 恢复当前家园的饲育屋面板。
     * @param role 角色
     * @param output 连接
     * @throws IOException 写入消息失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        relicDays.put(output, Math.floorDiv(java.time.Instant.now().getEpochSecond() + 28800L, 86400L));
        pushPets(breeding, json, role.id, output);
        if (role.sceneId != 80011 && role.sceneId != 80012)
            return;
        output.push(822,json.convertValue(races.snapshot(role.id),BODY));
        output.push(821, json.convertValue(links.snapshot(role.id), BODY));
        output.push(820, json.convertValue(puzzle.snapshot(role.id), BODY));
        output.push(818, json.convertValue(breeding.feedlot(role.id), BODY));
        pushBuildings(role, output);
    }

    /**
     * 跨过北京时间零点时同步当前家园遗迹的当日次数。
     * @param role 当前连接角色
     * @param output 原游戏连接
     * @throws IOException 协议写入失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        long day = Math.floorDiv(java.time.Instant.now().getEpochSecond() + 28800L, 86400L);
        Long previous = relicDays.get(output);
        if (previous != null && previous == day) return;
        relicDays.put(output, day);
        LocalRole saved = roles.findById(role.id).orElse(role);
        if (saved.sceneId == 80011 || saved.sceneId == 80012) {
            output.push(821, json.convertValue(links.snapshot(role.id), BODY));
            output.push(820, json.convertValue(puzzle.snapshot(role.id), BODY));
        }
    }

    /** 推送当前家园地块与生产时间。
     * @param role 角色
     * @param output 原游戏连接
     * @throws IOException 协议写入失败
     */
    private void pushBuildings(LocalRole role, GameReply output) throws IOException {
        output.push(817, data("mapid", role.sceneId, "building",
                json.convertValue(buildings.snapshot(role.id), java.util.List.class), "flag", role.sceneId != 80011 || home.claimTutorial(role.id)));
    }

    /** 在原背包扣除星星糖饰并同步实际心情增量。
     * @param role 角色
     * @param request 请求
     * @param output 连接
     * @return 是否已处理
     * @throws IOException 写入消息失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type >= 337 && request.type <= 340) {
            if (request.session <= 0) return true;
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
            }
            long idx = number(request.body, "idx", -1);
            long sid = number(request.body, "sid", 0);
            com.fasterxml.jackson.databind.node.ObjectNode result = buildings.handle(role.id, request.type,
                    idx >= 0 && idx <= 10 ? (int)idx : -1, sid >= 1 && sid <= 10001 ? (int)sid : 0,
                    conn + ":" + request.session);
            boolean replayed = result.path("replayed").asBoolean();
            result.remove("replayed");
            boolean mailed = result.path("mailed").asBoolean();
            result.remove("mailed");
            com.fasterxml.jackson.databind.JsonNode loses = result.remove("loses");
            com.fasterxml.jackson.databind.JsonNode adds = result.remove("adds");
            output.send(request, json.convertValue(result, BODY));
            if (result.path("errno").asInt() == 0) {
                if (!replayed) {
                    if (adds != null) output.push(14, data("flags", "matrix.mine", "adds", json.convertValue(adds,BODY)));
                    LocalRole saved = roles.findById(role.id).orElse(role);
                    output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
                    bags.push(role.id, output);
                    bags.pushMailCount(role.id, output);
                }
                pushBuildings(role, output);
            }
            return true;
        }
        if (request.type>=354 && request.type<=360 && request.session>0) {
            String conn;
            synchronized(connections) {
                conn=connections.computeIfAbsent(output,k->UUID.randomUUID().toString());
            }
            com.fasterxml.jackson.databind.node.ObjectNode result=races.handle(role.id,request.type,
                    number(request.body,"idx",0),conn,request.session);
            output.send(request,json.convertValue(result,BODY));
            if(result.path("errno").asInt()==0) {
                if(request.type==357) output.periodic(()->raceFrame(role,conn,output));
                if(request.type==359) output.periodic(null);
                if(request.type==354 || request.type==355) output.push(822,json.convertValue(races.snapshot(role.id),BODY));
                if(request.type==355 || request.type==358) {
                    LocalRole saved=roles.findById(role.id).orElse(role);
                    output.push(3,data("coins",saved.coins,"diamonds",saved.diamonds));
                }
            }
            return true;
        }
        if ((request.type >= 349 && request.type <= 353 || request.type == 412) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
            }
            com.fasterxml.jackson.databind.node.ObjectNode response = links.handle(role.id, request.type,
                    number(request.body, "idx", 0), conn, request.session);
            com.fasterxml.jackson.databind.JsonNode adds = response.remove("adds");
            output.send(request, json.convertValue(response, BODY));
            if (adds != null) {
                output.push(14, data("flags", request.type == 412 ? "linkgame.clean" : "linkgame.win", "adds",
                        json.convertValue(adds, BODY)));
                bags.push(role.id, output);
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            if (request.type == 352 && response.path("errno").asInt() == 0)
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            if (request.type == 349 || request.type == 352 || request.type == 412) {
                output.push(821, json.convertValue(links.snapshot(role.id), BODY));
            }
            return true;
        }
        boolean petGrowth = request.type == 10301 || request.type == 10302 || request.type == 10307
                || request.type == 10308 || request.type == 10304 || request.type == 10309;
        if ((request.type >= 362 && request.type <= 365 || request.type == 369 || petGrowth) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
            }
            Object pair = request.body.get(petGrowth ? "id_tab" : "list");
            long option = number(request.body, "option", 0);
            com.fasterxml.jackson.databind.node.ObjectNode result = breeding.handle(role.id, request.type,
                    pair instanceof java.util.List ? (java.util.List<?>) pair : Collections.emptyList(),
                    String.valueOf(request.body.get(request.type == 10309 ? "pet_uid" : petGrowth ? "id" : "uid")), Boolean.TRUE.equals(request.body.get("isegg")),
                    option >= 1 && option <= 6 ? (int) option : 0, conn + ":" + request.session);
            boolean replayed = result.path("replayed").asBoolean();
            result.remove("replayed");
            boolean mailed = result.path("mailed").asBoolean();
            result.remove("mailed");
            com.fasterxml.jackson.databind.JsonNode loses = result.remove("loses");
            com.fasterxml.jackson.databind.JsonNode adds = result.remove("adds");
            if (request.type == 10308 && loses != null)
                output.push(14, data("flags", "pet_free", "adds", json.convertValue(adds, BODY), "loses",
                        json.convertValue(loses, BODY)));
            output.send(request, json.convertValue(result, BODY));
            if (petGrowth && replayed)
                pushPets(breeding, json, role.id, output);
            if (result.path("errno").asInt() == 0 && !replayed) {
                if (mailed)
                    bags.pushMailCount(role.id, output);
                if (!petGrowth && adds != null)
                    output.push(14, data("flags", request.type == 364 ? "feedlot.collect" : "feedlot.hatchget", "adds",
                            json.convertValue(adds, BODY)));
                if ((request.type == 10302 || request.type == 10304) && loses != null)
                    output.push(14, data("flags", request.type == 10304 ? "pet_washs" : "pet_starup", "loses", json.convertValue(loses, BODY)));
                if (!petGrowth)
                    output.push(818, json.convertValue(breeding.feedlot(role.id), BODY));
                pushPets(breeding, json, role.id, output);
                LocalRole saved = roles.findById(role.id).orElse(role);
                output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds, "mood", home.mood(role.id)));
                bags.push(role.id, output);
            }
            return true;
        }
        if ((request.type >= 344 && request.type <= 348 || request.type == 411 || request.type == 368) && request.session > 0) {
            String conn;
            synchronized (connections) {
                conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
            }
            long idx = number(request.body, "idx", 0);
            com.fasterxml.jackson.databind.node.ObjectNode result = puzzle.handle(role, request.type,
                    idx >= 1 && idx <= 15 ? (int) idx : 0, conn, request.session);
            com.fasterxml.jackson.databind.JsonNode adds = result.remove("adds");
            if (adds != null) {
                output.push(14, data("flags", request.type==411?"puzzle.clean":"puzzle.win", "adds", json.convertValue(adds, BODY)));
                bags.push(role.id, output);
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            output.send(request, json.convertValue(result, BODY));
            if (request.type == 368 && result.path("errno").asInt() == 0)
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            if (request.type == 344 || request.type == 368 || request.type == 411)
                output.push(820, json.convertValue(puzzle.snapshot(role.id), BODY));
            return true;
        }
        if (request.type != 2401 || number(request.body, "id", 0) != 23046)
            return false;
        if (request.session <= 0)
            return true;
        String conn;
        synchronized (connections) {
            conn = connections.computeIfAbsent(output, k -> UUID.randomUUID().toString());
        }
        long count = number(request.body, "num", 1);
        int result = number(request.body, "giftid", 0) == 0
                ? home.useCandy(role.id, count, conn + ":" + request.session)
                : 100021;
        if (result == 0) {
            output.push(14, data("flags", "item.use", "adds", data("mood", count), "loses",
                    data("items", Collections.singletonList(data("id", 23046, "n", count)))));
            bags.push(role.id, output);
        }
        output.send(request, data("errno", Math.max(0, result)));
        if (result <= 0)
            output.push(3, data("mood", home.mood(role.id)));
        return true;
    }
    /** 推进同一连接的赛跑帧及终点发奖。
     * @param role 参赛角色
     * @param conn 连接编号
     * @param output 当前输出
     * @throws IOException 无法编码或发送
     */
    private void raceFrame(LocalRole role,String conn,GameReply output) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode frame=races.tick(role.id,conn);
        if(frame==null) {
            output.periodic(null);
            return;
        }
        com.fasterxml.jackson.databind.JsonNode adds=frame.remove("adds");
        output.push(823,json.convertValue(frame,BODY));
        if(frame.path("isend").asBoolean()) {
            output.periodic(null);
            output.push(14,data("flags","race.win","adds",json.convertValue(adds,BODY)));
            LocalRole saved=roles.findById(role.id).orElse(role);
            output.push(3,data("coins",saved.coins,"diamonds",saved.diamonds));
            bags.push(role.id,output);
        }
    }


    /** 按原宠物 UID 增量装入客户端缓存，避免大型饲育屋超过单帧长度。
     * @param breeding 角色宠物存档服务
     * @param json 协议数据转换器
     * @param roleId 角色编号
     * @param output 当前连接
     * @throws IOException 写入宠物帧失败
     */
    static void pushPets(com.pokemon.offline.home.BreedingService breeding, com.fasterxml.jackson.databind.ObjectMapper json,
            String roleId, GameReply output) throws IOException {
        java.util.List<?> pets = json.convertValue(breeding.pets(roleId), java.util.List.class);
        if (pets.isEmpty()) {
            output.push(2047, data("pet_info", Collections.emptyList()));
        }
        for (int start = 0; start < pets.size(); start += 100) {
            output.push(2047, data("pet_info", pets.subList(start, Math.min(start + 100, pets.size()))));
        }
    }

}
