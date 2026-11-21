package com.pokemon.local.gateway;

import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.dojo.DojoService;
import com.pokemon.local.dojo.DojoStationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.local.dojo.DojoSnapshot;
import com.pokemon.local.dojo.api.entity.DojoPlacement;
import com.pokemon.local.dojo.api.vo.DojoTeamSlot;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原游戏道馆阵容与助阵编组入口。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:50
 */
@Service
@Order(-10)
final class AssistGameFeature implements GameFeature {
    /**
     * 构造原驻防开战或增购回包，过滤战斗服务内部的计算字段。
     * @param role 已认证角色
     * @param request 原请求
     * @param output 当前连接
     * @throws IOException 原协议发送失败
     */
    private void stationRequest(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session > 0) {
            String receipt = connections.computeIfAbsent(output,k -> UUID.randomUUID().toString()) + ":" + request.session;
            if (request.type == 268 && combat.active(role.id) && !station.active(role.id)) {
                output.send(request,data("errno",3));
                return;
            }
            ObjectNode reply = request.type == 268 ? station.challenge(role.id,number(request.body,"idx",0),receipt)
                    : station.buy(role.id,receipt);
            Map<String,Object> response = data("errno",reply.path("errno").asInt());
            if (reply.path("errno").asInt() == 0) {
                response.put("times",reply.path("times").asInt());
                if (request.type == 269) {
                    response.put("buy",reply.path("buy").asInt());
                } else {
                    response.put("seed",reply.path("seed").asInt());
                    response.put("weather",reply.path("weather").asInt());
                    response.put("isnpc",reply.path("isnpc").asInt());
                    for (String field : new String[]{"player","monster"}) {
                        ObjectNode team = reply.path(field).deepCopy();
                        Iterator<JsonNode> members = team.path("pokemon").elements();
                        while (members.hasNext()) {
                            ObjectNode member = (ObjectNode) members.next();
                            if (member.path("hp").asInt() <= 0) {
                                members.remove();
                            } else {
                                member.remove(Arrays.asList("goldCarry", "homeStatues", "baseDamage","targetLimit","physical",
                                        "enemy","type1","type2","skill","stationDamagePercent"));
                            }
                        }
                        response.put(field,json.convertValue(team,Map.class));
                    }
                }
            }
            output.send(request,response);
            if (reply.path("errno").asInt() == 0) {
                pushStation(role,output,true);
                if (request.type == 269) {
                    output.push(3,data("diamonds",reply.path("diamonds").asLong()));
                }
            }
        }
    }

    /** 将原协议行动应用到抢夺战并在终局推送最新归属。
     * @param role 已认证角色
     * @param request 回合或逃跑
     * @param output 当前连接
     * @param flee 是否逃跑
     * @throws IOException 协议发送失败
     */
    private void stationAction(LocalRole role, SprotoProtocol.Request request, GameReply output, boolean flee) throws IOException {
        Object targets = request.body.get("objs");
        int outcome = station.action(role.id,(int)number(request.body,"sid",0),
                targets instanceof List ? (List<?>)targets : Collections.emptyList(),
                (int)number(request.body,"flag",-1),flee);
        if (outcome > 0) {
            pushStation(role,output,true);
            if (!flee && outcome == 2) {
                output.push(1051,data());
            }
        }
    }

    /** 用心跳同步其他本地角色引起的易主和每日四点结算。
     * @param role 本地角色
     * @param output 当前连接
     * @throws IOException 协议写出失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        if (System.currentTimeMillis() >= refresh.getOrDefault(output,0L)) {
            pushStation(role,output,false);
        }
    }

    /** 推送原驻防列表与剩余挑战次数。
     * @param role 本地角色
     * @param output 当前连接
     * @param force 是否强制刷新
     * @throws IOException 协议发送失败
     */
    private void pushStation(LocalRole role, GameReply output, boolean force) throws IOException {
        ObjectNode snapshot = station.snapshot(role);
        refresh.put(output,System.currentTimeMillis()+15000);
        ObjectNode last = previous.put(output,snapshot);
        if (force || !snapshot.equals(last)) {
            output.push(426,data("info",json.convertValue(snapshot.path("info"),List.class)));
            output.push(427,data("cptimes",snapshot.path("times").asInt(),"cpbuy",snapshot.path("buy").asInt(),
                    "place",snapshot.path("place").asBoolean()));
            mail.pushMailCount(role,output);
        }
    }

    private static final int SET_DOJO = 180;
    private static final int SET_AID = 183;
    private static final int SWAP_AID = 184;

    private final DojoService dojo;
    private final DojoStationService station;
    private final ObjectMapper json;
    private final Map<GameReply,String> connections = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> refresh = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,ObjectNode> previous = Collections.synchronizedMap(new WeakHashMap<>());
    private final DojoCombatService combat;
    private final InventoryService inventory;
    private final BagSnapshot bags;
    private final InventoryGameFeature mail;

    /**
     * 创建道馆与助阵协议入口。
     *
     * @param dojo 道馆阵容和助阵服务
     * @param combat 道馆挑战服务
     * @param inventory 道具显示类型
     * @param bags 实际背包同步
     * @param mail 邮箱提示
     * @param station 道馆争夺服务
     * @param json 原消息转换
     */
    AssistGameFeature(DojoService dojo, DojoCombatService combat, InventoryService inventory, BagSnapshot bags,
                      InventoryGameFeature mail, DojoStationService station, ObjectMapper json) {
        this.station = station;
        this.json = json;
        this.dojo = dojo;
        this.combat = combat;
        this.inventory = inventory;
        this.bags = bags;
        this.mail = mail;
    }

    /**
     * 送出原协议所需的操作应答和存档快照。
     *
     * @param role 连接认证的角色
     * @param request 原游戏请求
     * @param output 原游戏消息出口
     * @return 是否处理该请求
     * @throws IOException 写入原协议应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case SET_DOJO:
                setTeam(role.id, request, output);
                return true;
            case 268:
            case 269:
                stationRequest(role, request, output);
                return true;
            case 181:
                start(role.id, request, output);
                return true;
            case 182:
                sweep(role, request, output);
                return true;
            case SET_AID:
                setAid(role.id, request, output);
                return true;
            case SWAP_AID:
                swapAid(role.id, request, output);
                return true;
            case 3001:
                if (station.active(role.id)) {
                    stationAction(role,request,output,false);
                    return true;
                }
                if (!combat.active(role.id)) {
                    return false;
                }
                fight(role.id, request, output);
                return true;
            case 3002:
                if (station.active(role.id)) {
                    stationAction(role,request,output,true);
                    return true;
                }
                if (!combat.active(role.id)) {
                    return false;
                }
                combat.flee(role.id);
                pushDojo(role.id, output);
                return true;
            default:
                return false;
        }
    }

    /**
     * 结算已通关道馆的扫荡请求并同步实际到账的奖励。
     *
     * @param role 已认证角色
     * @param request 道馆扫荡请求
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void sweep(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return;
        }
        long stage = number(request.body, "dojoId", 0);
        DojoCombatService.SweepResult result = combat.sweep(role.id, stage);
        output.send(request, data("errno", result.errno, "dojoId", stage));
        if (result.errno == 0) {
            pushDojo(role.id, output);
        }
        if (!result.items.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            result.items.forEach((id, count) -> items.add(data("id", id, "n", count)));
            output.push(14, data("flags", "dojo.clean", "adds", data("items", items)));
            bags.push(role.id, output);
        }
        if (result.mailed) {
            mail.pushMailCount(role, output);
        }
    }

    /**
     * 恢复道馆进度和助阵存档。
     *
     * @param role 连接认证的角色
     * @param output 原游戏消息出口
     * @throws IOException 写入原协议推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushDojo(role.id, output);
        pushAid(role.id, output);
        pushStation(role,output,true);
    }

    /**
     * 开启一个正式挑战或热身局。
     *
     * @param roleId 已认证角色
     * @param request 发起战斗请求
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void start(String roleId, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean practice = Boolean.TRUE.equals(request.body.get("istry"));
        Map<String, Object> answer = combat.start(roleId, number(request.body, "dojoId", 0),
                (String) request.body.get("monId"), practice);
        output.send(request, answer);
        if (practice && number(answer, "errno", -1) == 0) {
            pushDojo(roleId, output);
        }
    }

    /**
     * 校验道馆中的战斗动作，清理场景中的已击败怪物。
     *
     * @param roleId 已认证角色
     * @param request 战斗动作
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void fight(String roleId, SprotoProtocol.Request request, GameReply output) throws IOException {
        Object targets = request.body.get("objs");
        List<?> enemies = targets instanceof List ? (List<?>) targets : Collections.emptyList();
        int scene = dojo.snapshot(roleId).dojo + 71100;
        String cleared = combat.synchronize(roleId, number(request.body, "sid", 0), enemies,
                number(request.body, "flag", -1));
        if (cleared != null) {
            output.push(12, data("sid", scene, "uids", Collections.singletonList(cleared)));
            pushDojo(roleId, output);
        }
    }

    /**
     * 保存道馆完整编队。
     *
     * @param roleId 已认证角色
     * @param request 道馆编队请求
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void setTeam(String roleId, SprotoProtocol.Request request, GameReply output) throws IOException {
        Object rawTeam = request.body.get("elflist");
        List<DojoTeamSlot> team = new ArrayList<>();
        if (rawTeam instanceof List<?>) {
            for (Object slot : (List<?>) rawTeam) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) slot;
                team.add(new DojoTeamSlot((String) row.get("id"), number(row, "position", 0),
                        number(row, "battleid", team.size() + 1)));
            }
        }
        int errno = dojo.setTeam(roleId, number(request.body, "tactics", 0), team);
        output.send(request, data("errno", errno));
        if (errno == 0) {
            pushDojo(roleId, output);
        }
    }

    /**
     * 保存助阵成员或按协议缺省字段撤下成员。
     *
     * @param roleId 已认证角色
     * @param request 助阵设置请求
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void setAid(String roleId, SprotoProtocol.Request request, GameReply output) throws IOException {
        long index = number(request.body, "index", 0);
        String elf = (String) request.body.get("elf");
        int errno = dojo.setAid(roleId, index, elf);
        Map<String, Object> result = data("errno", errno, "index", index);
        if (elf != null && !elf.isEmpty() && errno == 0) {
            result.put("elf", elf);
        }
        output.send(request, result);
        if (errno == 0) {
            pushAid(roleId, output);
        }
    }

    /**
     * 完成槽位交换，先让原客户端处理其增量回调，再发权威快照。
     *
     * @param roleId 已认证角色
     * @param request 助阵交换请求
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void swapAid(String roleId, SprotoProtocol.Request request, GameReply output) throws IOException {
        long first = number(request.body, "idx1", 0);
        long second = number(request.body, "idx2", 0);
        int errno = dojo.swapAid(roleId, first, second);
        output.send(request, data("errno", errno, "idx1", first, "idx2", second));
        if (errno == 0) {
            pushAid(roleId, output);
        }
    }

    /**
     * 推送真实道馆存档。
     *
     * @param roleId 已认证角色
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void pushDojo(String roleId, GameReply output) throws IOException {
        DojoSnapshot progress = dojo.snapshot(roleId);
        List<Map<String, Object>> team = new ArrayList<>();
        for (DojoPlacement elf : progress.team) {
            team.add(data("id", elf.elfId, "position", elf.position, "battleid", elf.battleId, "sid", elf.species));
        }
        output.push(424, data("dojoId", progress.dojo, "history", progress.history, "times", progress.failures,
                "manoeuvre", progress.practices, "monNum", progress.defeated, "tactics", progress.tactics, "list", team,
                "pet_uid", ""));
    }

    /**
     * 推送各助阵位已持久化的配置编号和实例编号。
     *
     * @param roleId 已认证角色
     * @param output 原游戏消息出口
     * @throws IOException 写入协议失败
     */
    private void pushAid(String roleId, GameReply output) throws IOException {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (DojoPlacement elf : dojo.aids(roleId)) {
            slots.add(data("id", elf.elfId, "sid", elf.species, "index", elf.position));
        }
        output.push(425, data("list", slots));
    }
}
