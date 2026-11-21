package com.pokemon.local.gateway;

import com.pokemon.local.activitydaily.DailyRechargeService;
import com.pokemon.local.activitydaily.RechargePromotionService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 每日充值福利与积分商城原版协议。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:07
 */
@Service
final class DailyRechargeGameFeature implements GameFeature {
    private final DailyRechargeService recharge;
    private final InventoryService inventory;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final RechargePromotionService promotions;
    private final BattleGameFeature battle;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /**
     * 绑定实际领取、兑换和资产同步。
     *
     * @param recharge 活动档位、已领记录和兑换
     * @param inventory 道具显示类型
     * @param roles 角色货币余额
     * @param bags 物品及碎片背包快照
     * @param promotions 充值累计与单笔活动
     * @param battle 精灵背包快照
     */
    DailyRechargeGameFeature(DailyRechargeService recharge, InventoryService inventory, RoleService roles, BagSnapshot bags,
                             RechargePromotionService promotions, BattleGameFeature battle) {
        this.recharge = recharge;
        this.inventory = inventory;
        this.roles = roles;
        this.bags = bags;
        this.promotions = promotions;
        this.battle = battle;
    }

    /**
     * 成功结算后同步相关资产并返回原请求应答。
     *
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 协议回复通道
     * @return 是否处理了请求
     * @throws IOException 回复失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if ((request.type >= 150 && request.type <= 155) || request.type == 192 || request.type == 193) {
            if (request.session > 0) {
                handlePromotion(role, request, output);
            }
            return true;
        }
        if (request.type < 162 || request.type > 165) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 162) {
            output.send(request, recharge.daily(role.id));
        } else if (request.type == 164) {
            output.send(request, recharge.shop());
        } else {
            long index = number(request.body, "idx", 0);
            boolean claim = request.type == 163;
            DailyRechargeService.Result result = claim ? recharge.claim(role.id, index)
                    : recharge.exchange(role.id, requestId(output, request.session), index);
            if (!result.awards.isEmpty()) {
                bags.push(role.id, output);
                pushAwards(role, result.awards, output, claim);
            }
            output.send(request, data("errno", result.errno, "idx", index));
        }
        return true;
    }

    /**
     * 转换充值档位查询与领取，并同步真正变化的本地资产。
     *
     * @param role 当前角色
     * @param request 原充值活动请求
     * @param output 游戏协议通道
     * @throws IOException 写入回复失败
     */
    private void handlePromotion(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int activity;
        switch (request.type) {
            case 150:
            case 151:
                activity = RechargePromotionService.TOTAL;
                break;
            case 152:
            case 153:
                activity = RechargePromotionService.STREAK;
                break;
            case 154:
            case 155:
                activity = RechargePromotionService.SINGLE;
                break;
            default:
                activity = RechargePromotionService.DAILY;
        }
        if (request.type % 2 == 0) {
            output.send(request, promotions.status(role.id, activity));
            return;
        }
        long index = number(request.body, "idx", 0);
        RechargePromotionService.Result result = promotions.claim(role.id, activity, index);
        if (result.errno == 0) {
            bags.push(role.id, output);
            pushPromotionAwards(role, result.rewards, output);
        }
        output.send(request, data("errno", result.errno, "idx", index));
    }

    /**
     * 发送领取后角色货币、物品和精灵的实际余额与提示。
     *
     * @param role 当前角色
     * @param rewards 底包奖励三元组
     * @param output 游戏协议通道
     * @throws IOException 写入推送失败
     */
    private void pushPromotionAwards(LocalRole role, List<Integer> rewards, GameReply output) throws IOException {
        Map<String, Object> adds = data();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> chips = new ArrayList<>();
        boolean gotElf = false;
        for (int index = 0; index < rewards.size(); index += 3) {
            int type = rewards.get(index);
            int id = rewards.get(index + 1);
            int quantity = rewards.get(index + 2);
            if (type == 1) {
                items.add(data("id", id, "n", quantity));
            } else if (type == 2) {
                gotElf = true;
            } else if (type == 3) {
                chips.add(data("id", id, "n", quantity));
            } else {
                adds.merge(type == 103 ? "coins" : "diamonds", quantity,
                        (left, right) -> ((Integer) left) + ((Integer) right));
            }
        }
        if (gotElf) {
            battle.pushElfs(role.id, output);
        }
        if (adds.containsKey("coins") || adds.containsKey("diamonds")) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
        }
        if (!items.isEmpty()) {
            adds.put("items", items);
        }
        if (!chips.isEmpty()) {
            adds.put("elschips", chips);
        }
        output.push(14, data("flags", "recharge.promotion", "adds", adds, "loses", data()));
    }

    /**
     * 将原始领取记录转换为正确的货币、普通道具与精灵碎片附件。
     *
     * @param role 当前角色
     * @param awards 本次新发放的奖励
     * @param output 协议通道
     * @param claim 是否为充值档位领取
     * @throws IOException 推送失败
     */
    private void pushAwards(LocalRole role, List<DailyRechargeService.Award> awards, GameReply output, boolean claim)
            throws IOException {
        Map<String, Object> adds = data();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> chips = new ArrayList<>();
        for (DailyRechargeService.Award award : awards) {
            if (award.type == DailyRechargeService.DIAMONDS) {
                adds.put("diamonds", award.quantity);
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            } else if (award.type == DailyRechargeService.CHIP) {
                chips.add(data("id", award.id, "n", award.quantity));
            } else {
                items.add(data("id", award.id, "n", award.quantity));
            }
        }
        if (!items.isEmpty()) {
            adds.put("items", items);
        }
        if (!chips.isEmpty()) {
            adds.put("elschips", chips);
        }
        output.push(14, data("flags", claim ? "dailycharge.claim" : "dailycharge.exchange", "adds", adds, "loses", data()));
    }

    /**
     * 创建可在同一连接中复用的请求记录键。
     *
     * @param output 当前连接通道
     * @param session 原请求会话号
     * @return 该请求唯一保存编号
     */
    private String requestId(GameReply output, long session) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, unused -> UUID.randomUUID().toString()) + ":" + session;
        }
    }
}
