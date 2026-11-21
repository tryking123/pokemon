package com.pokemon.local.gateway;

import com.pokemon.local.activitylevel.TrainerEquipmentService;
import com.pokemon.local.activitylevel.TrainerEquipmentRatingService;
import com.pokemon.local.activitylevel.TrainerRefinementService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pokemon.local.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.local.battle.AdventureStage;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 训练家装备穿戴、强化和界面初始快照。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:03
 */
@Service
@Order(20)
final class TrainerEquipmentGameFeature implements GameFeature {
    private final TrainerEquipmentService equipment;
    private final TrainerEquipmentRatingService rating;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final WorldGameFeature world;
    private final TrainerRefinementService refinement;
    private final Map<GameReply, String> connections = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Map<GameReply, String> shownForge = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 连接装备存档、钱包和背包推送。
     *
     * @param equipment 训练家装备服务
     * @param roles 角色存档查询
     * @param bags 穿戴与强化后的背包更新
     * @param world 穿戴与强化后的主线任务状态
     * @param refinement 装备属性洗炼和确认
     * @param rating 装备养成评分
     */
    TrainerEquipmentGameFeature(TrainerEquipmentService equipment, RoleService roles, BagSnapshot bags, WorldGameFeature world,
                                TrainerRefinementService refinement, TrainerEquipmentRatingService rating) {
        this.equipment = equipment;
        this.roles = roles;
        this.bags = bags;
        this.world = world;
        this.refinement = refinement;
        this.rating = rating;
    }

    /**
     * 处理装备替换及单次强化请求。
     *
     * @param role 当前登录角色
     * @param request 原装备协议
     * @param output 当前连接
     * @return 是否处理了请求
     * @throws IOException 应答或推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if ((request.type < 2519 || request.type > 2525) && request.type != 2534 && request.type != 2535) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 2535) {
            List<Map<String, Integer>> scores = rating.scores(role.id);
            output.send(request, scores.isEmpty() ? data("errno", 100021) : data("errno", 0, "score", scores));
            return true;
        }
        if (request.type == 2534) {
            long slot=number(request.body,"pos",0);
            String receipt="advance:"+connections.computeIfAbsent(output,ignored -> java.util.UUID.randomUUID().toString())+":"+request.session;
            com.fasterxml.jackson.databind.node.ArrayNode extra=slot>=1 && slot<=6 ? refinement.advance(role.id,(int)slot,receipt) : null;
            output.send(request,extra==null ? data("errno",100021) : data("errno",0,"extra",refinement.properties(extra)));
            if(extra!=null) {
                output.push(3,data("coins",roles.findById(role.id).orElse(role).coins));
                synchronize(role,output);
                bags.push(role.id,output);
            }
            return true;
        }
        if (request.type == 2524 || request.type == 2525) {
            boolean refresh = request.type == 2524;
            String receipt = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                    + ":" + request.type + ":" + request.session;
            TrainerEquipmentService.Forge state = equipment.changeForge(role.id, shownForge.get(output), refresh, receipt);
            if (state == null) {
                output.send(request, data("errno", 100021));
                synchronize(role, output);
                return true;
            }
            output.send(request, data("errno", 0, "forge", forgeItem(state.offer),
                    refresh ? "num" : "point", refresh ? state.refreshes : state.points));
            shownForge.put(output, state.offer.id);
            if (refresh) {
                LocalRole current = roles.findById(role.id).orElse(role);
                output.push(3, data("diamonds", current.diamonds));
            } else {
                bags.push(role.id, output);
            }
            return true;
        }
        if (request.type == 2522 || request.type == 2523) {
            String receipt = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                    + ":" + request.type + ":" + request.session;
            if (request.type == 2523) {
                boolean saved = refinement.save(role.id, receipt);
                output.send(request, data("errno", saved ? 0 : 100021));
                if (saved) {
                    synchronize(role, output);
                }
            } else {
                Object reserve = request.body.getOrDefault("reserve", Collections.emptyList());
                long pos = number(request.body, "pos", 0);
                long mode = number(request.body, "mode", -1);
                ArrayNode extra = pos >= 1 && pos <= 6 && mode >= 0 && mode <= 1 && reserve instanceof List
                        ? refinement.preview(role.id, (int) pos, (String) request.body.get("sid"), (int) mode,
                                (List<?>) reserve, receipt) : null;
                output.send(request, extra == null ? data("errno", 100021)
                        : data("errno", 0, "extra", refinement.properties(extra)));
                if (extra != null) {
                    bags.push(role.id, output);
                    LocalRole current = roles.findById(role.id).orElse(role);
                    output.push(3, data("coins", current.coins));
                }
            }
            return true;
        }
        if (request.type == 2521) {
            Object ids = request.body.get("list");
            TrainerEquipmentService.MeltResult melted = ids instanceof List ? equipment.melt(role.id, (List<?>) ids) : null;
            if (melted == null) {
                output.send(request, data("errno", 100021));
                return true;
            }
            output.send(request, data("errno", 0, "point", melted.points));
            if ((!melted.equipment.isEmpty() || !melted.stones.isEmpty())) {
                List<Map<String, Object>> gains = new ArrayList<>();
                for (AdventureEquipment gear : melted.equipment) {
                    gains.add(data("id", gear.configId, "sid", gear.id, "pos", gear.slot, "n", 1,
                            "level", 0, "type", AdventureStage.EQUIPMENT, "extra", refinement.properties(refinement.attributes(gear))));
                }
                melted.stones.forEach((id,n)->gains.add(data("id",id,"n",n)));
                bags.pushMailCount(role.id,output);
                output.push(14, data("flags", "equipment.smelting", "adds", data("items", gains)));
            }
            bags.push(role.id, output);
            return true;
        }
        boolean changed;
        Map<String, Object> result;
        if (request.type == 2519) {
            changed = equipment.wear(role.id, (String) request.body.get("sid"));
            result = data("errno", changed ? 0 : 1);
        } else {
            long slot = number(request.body, "pos", 0);
            boolean auto = Boolean.TRUE.equals(request.body.get("auto"));
            changed = slot >= 1 && slot <= 6 && equipment.enhance(role.id, (int) slot, auto);
            result = data("errno", changed ? 0 : 1, "pos", slot, "auto", auto);
        }
        // 原强化应答会先在客户端把部位等级加一，再用完整快照校准。
        output.send(request, result);
        if (changed) {
            synchronize(role, output);
            bags.push(role.id, output);
            world.synchronize(role, output);
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
        }
        return true;
    }

    /**
     * 同步所有已经穿戴的部位与强化状态。
     *
     * @param role 当前登录角色
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (AdventureEquipment item : equipment.equipped(role.id)) {
            parts.add(data("id", item.configId, "sid", item.id, "n", 1, "pos", item.slot, "level", item.level,
                    "extra", refinement.properties(refinement.attributes(item)), "type", AdventureStage.EQUIPMENT, "upgrade", item.advanced ? 1 : 0));
        }
        TrainerEquipmentService.Forge forge = equipment.forgeBoard(role.id);
        Map<String, Object> state = data("part", parts, "nexttime", forge == null ? 0 : forge.resetAt,
                "num", forge == null ? 0 : forge.refreshes, "point", forge == null ? equipment.meltPoints(role.id) : forge.points);
        if (forge != null) {
            state.put("forge", forgeItem(forge.offer));
            shownForge.put(output, forge.offer.id);
        }
        output.push(433, state);
    }

    /**
     * 把候选装备投影成客户端领取时直接放入背包的原始装备结构。
     * @param gear 打造台候选实例
     * @return 原协议 item 结构
     */
    private Map<String,Object> forgeItem(AdventureEquipment gear) {
        return data("id", gear.configId, "sid", gear.id, "pos", gear.slot, "n", 1, "level", 0,
                "state", false, "lock", false, "type", AdventureStage.EQUIPMENT,
                "extra", refinement.properties(refinement.attributes(gear)), "upgrade", gear.advanced ? 1 : 0);
    }
}
