package com.pokemon.offline.gateway;

import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.hangup.HangupMap;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.hangup.api.entity.HangupState;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 冒险挂机领取及原客户端收益快照。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:15
 */
@Service
final class HangupGameFeature implements GameFeature {
    private final HangupService hangup;
    private final com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.secret.SecretService> secret;
    private final AdventureProgressService adventure;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final InventoryService inventory;
    private final BagSnapshot bags;
    private final Map<GameReply, String> quickenConnections = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定原冒险协议的存档服务。
     *
     * @param hangup 挂机收益服务
     * @param secret 秘境挑战点余额服务
     * @param adventure 冒险关卡存档
     * @param roles 读取入账后的角色
     * @param battle 精灵经验池同步
     * @param inventory 奖励物品类型
     * @param bags 地图、普通挂机与快速战斗奖励结算后的背包同步
     */
    HangupGameFeature(HangupService hangup, com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.secret.SecretService> secret, AdventureProgressService adventure, RoleService roles,
                      BattleGameFeature battle, InventoryService inventory, BagSnapshot bags) {
        this.hangup = hangup;
        this.secret=secret;
        this.adventure = adventure;
        this.roles = roles;
        this.battle = battle;
        this.inventory = inventory;
        this.bags = bags;
    }

    /**
     * 处理冒险普通挂机领取，数额由服务端记录决定。
     *
     * @param role 当前认证角色
     * @param request 原协议领取请求
     * @param output 当前连接通知通道
     * @return 是否由该功能处理
     * @throws IOException 发送消息失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 3041) {
            claimMap(role, request, output);
            return true;
        }
        if (request.type == 3039) {
            quicken(role, request, output);
            return true;
        }
        if (request.type == 2208) {
            if (request.session <= 0) return true;
            long requested=number(request.body,"sid",Long.MIN_VALUE);
            boolean ok=requested==-1 || hangup.selectDungeon(role.id,requested,receipt(output,request));
            HangupState current=hangup.snapshot(role.id,adventure.nextMission(role.id));
            output.send(request,data("errno",ok?0:1,"sid",current.dungeonGroup));
            push(role.id,output);
            return true;
        }
        if (request.type != 3038 && request.type != 2209) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        boolean dungeon=request.type==2209;
        HangupState claimed = dungeon && number(request.body,"ntype",-1)!=0 ? null :
                hangup.claim(role.id,dungeon,receipt(output,request));
        output.send(request, dungeon ? data("errno",claimed==null?1:0,"dayitemn",0,"dayelfn",0)
                : data("errno", claimed == null ? 1 : 0));
        if (claimed != null) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", saved.coins, "level", saved.level, "exp", saved.exp,
                    "challenge", secret.getIfAvailable().balance(role.id), "exp1", Math.addExact(claimed.elfPool, claimed.elfExp), "servertime", Instant.now().getEpochSecond()));
            battle.pushElfs(role.id, output);
            pushReward(role.id, output, dungeon ? "dungeon.onhook_award" : "hangup.receive_normal", claimed);
        }
        push(role.id, output);
        return true;
    }

    /**
     * 为同一连接内同类动作建立稳定回执。
     * @param output 当前连接
     * @param request 原请求
     * @return 含动作、连接与请求编号的键
     */
    private String receipt(GameReply output,SprotoProtocol.Request request) {
        String connection=quickenConnections.computeIfAbsent(output,k->UUID.randomUUID().toString());
        return request.type+":"+connection+":"+request.session;
    }

    /**
     * 按请求号幂等结算一次快速战斗并推送奖励与本日次数。
     *
     * @param role 当前认证角色
     * @param request 原快速战斗请求
     * @param output 当前连接
     * @throws IOException 发送失败
     */
    private void quicken(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return;
        }
        String connection = quickenConnections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
        HangupState result = hangup.quicken(role.id, connection + ":" + request.session);
        output.send(request, data("errno", result == null ? 1 : 0));
        if (result == null) {
            return;
        }
        if (result.pendingSeconds > 0) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds, "level", saved.level,
                    "exp", saved.exp, "exp1", result.elfPool));
            battle.pushElfs(role.id, output);
            pushReward(role.id, output, "hangup.quicken", result);
        }
        push(role.id, output);
    }

    /**
     * 同步本次入账的数值和普通道具，并在有掉落时更新背包。
     *
     * @param roleId 已结算的角色编号
     * @param output 当前连接
     * @param flag 原客户端奖励来源标识
     * @param result 本次结算明细
     * @throws IOException 发送失败
     */
    private void pushReward(String roleId, GameReply output, String flag, HangupState result) throws IOException {
        Map<String, Object> adds = data("coins", result.coins, "exp", result.roleExp, "exp1", result.elfExp);
        if (!result.items.isEmpty()) {
            adds.put("items", itemRows(result.items));
        }
        output.push(14, data("flags", flag, "adds", adds));
        if (!result.items.isEmpty()) {
            bags.push(roleId, output);
        }
        bags.pushMailCount(roleId, output);
    }

    /**
     * 将本次直接入包的物品转换为原奖励通知明细。
     * @param delivered 实际入包数量
     * @return 原协议物品列表
     */
    private List<Map<String, Object>> itemRows(Map<Integer, Long> delivered) {
        List<Map<String, Object>> items = new ArrayList<>();
        delivered.forEach((id, count) -> items.add(data("id", id, "n", count)));
        return items;
    }

    /**
     * 发放达到原版门槛后的首通地图奖励并同步实际资产。
     *
     * @param role 当前认证角色
     * @param request 原地图领奖请求
     * @param output 当前连接
     * @throws IOException 发送失败
     */
    private void claimMap(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return;
        }
        long id = number(request.body, "sid", 0);
        HangupMap map = HangupMap.byId(id);
        Map<Integer, Long> delivered = hangup.claimMap(role.id, map);
        output.send(request, data("errno", delivered == null ? 1 : 0, "sid", id));
        if (delivered == null) {
            return;
        }
        LocalRole saved = roles.findById(role.id).orElse(role);
        output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
        output.push(14, data("flags", "hangup.map_complete", "adds",
                data("coins", map.coins, "diamonds", map.diamonds, "items", itemRows(delivered))));
        bags.push(role.id, output);
        bags.pushMailCount(role.id, output);
        push(role.id, output);
    }

    /**
     * 入服、进图或领取后更新客户端当前挂机状态。
     *
     * @param roleId 角色编号
     * @param output 当前连接
     * @throws IOException 发送失败
     */
    void push(String roleId, GameReply output) throws IOException {
        int mission = adventure.nextMission(roleId);
        pushSnapshot(output, mission, hangup, roleId, Collections.emptyList());
    }

    /**
     * 发送原 S1007 结构，并在真实通关时附带已经入库的关卡奖励。
     *
     * @param output 当前连接
     * @param mission 下一关编号
     * @param hangup 挂机收益服务
     * @param roleId 角色编号
     * @param direct 已发放的关卡物品
     * @throws IOException 发送失败
     */
    static void pushSnapshot(GameReply output, int mission, HangupService hangup,
                             String roleId, List<Map<String, Object>> direct)
            throws IOException {
        HangupState state = hangup.snapshot(roleId, mission);
        List<Map<String, Object>> items = new ArrayList<>();
        state.items.forEach((id, count) -> items.add(data("id", id, "n", count)));
        long itemCount = state.items.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> payload = data("exp", state.roleExp, "exp1", state.elfExp, "coins", state.coins,
                "pokemon_pool", Collections.emptyList(), "item_pool", items, "scene", state.mapId,
                "mission", mission, "lasttime", state.pendingSeconds,
                "pokemon_count", 0, "item_count", itemCount, "quicken_count", hangup.quickenCount(roleId),
                "bonus", 0, "howlong", 0, "passed", 0,
                "map_flag", hangup.claimedMaps(roleId), "fasttimes", hangup.fastTimes(roleId));
        if (!direct.isEmpty()) {
            payload.put("direct", data("items", direct));
        }
        output.push(1007, payload);
        Map<String,Object> reward=data("coins",state.coins,"exp",state.roleExp,"exp1",state.elfExp);
        if(!items.isEmpty()) reward.put("items",items);
        output.push(372,data("sid",state.dungeonGroup,"sdids",hangup.dungeonGroups(roleId),
                "last",state.pendingSeconds,"reward",reward,"dayitemn",0,"dayelfn",0));
    }
}
