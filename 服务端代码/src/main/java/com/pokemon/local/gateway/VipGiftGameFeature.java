package com.pokemon.local.gateway;

import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.VipGiftService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原 Unity VIP 福利与充值页 VIP 礼包协议
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:19
 */
@Service
final class VipGiftGameFeature implements GameFeature {
    private static final int SHOW_GIFTS = 259;
    private static final int BUY_BY_ID = 260;
    private static final int BUY_BY_LEVEL = 161;
    private final VipGiftService gifts;
    private final GamePaymentService payments;
    private final InventoryGameFeature inventory;
    private final BattleGameFeature battle;
    private final CosmeticGameFeature cosmetics;
    private final RoleService roles;

    /**
     * 绑定原版礼包及各种角色资产推送
     *
     * @param gifts 本地 VIP 礼包
     * @param payments 当前角色钱包和 VIP
     * @param inventory 普通物品背包
     * @param battle 新取得的精灵
     * @param cosmetics 时装持有和穿戴状态推送
     * @param roles 已更新的角色钱包
     */
    VipGiftGameFeature(VipGiftService gifts, GamePaymentService payments, InventoryGameFeature inventory,
                       BattleGameFeature battle, CosmeticGameFeature cosmetics, RoleService roles) {
        this.gifts = gifts;
        this.payments = payments;
        this.inventory = inventory;
        this.battle = battle;
        this.cosmetics = cosmetics;
        this.roles = roles;
    }

    /**
     * 响应展示、按礼包编号购买或按 VIP 等级购买
     *
     * @param role 当前认证角色
     * @param request 原游戏请求
     * @param output 原协议写入通道
     * @return 是否处理当前请求
     * @throws IOException 无法发送游戏应答
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != SHOW_GIFTS && request.type != BUY_BY_ID && request.type != BUY_BY_LEVEL) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == SHOW_GIFTS) {
            output.send(request, data("errno", 0, "info", gifts.offers(role.id)));
            return true;
        }
        boolean byId = request.type == BUY_BY_ID;
        String field = byId ? "idx" : "level";
        long selected = number(request.body, field, -1);
        long level = byId && selected >= 1 ? selected - 1 : selected;
        List<Integer> reward = byId && selected < 1 ? Collections.emptyList() : gifts.buy(role.id, level);
        output.send(request, data("errno", reward.isEmpty() ? 1 : 0, field, selected));
        if (!reward.isEmpty()) {
            pushAssets(role, reward, output);
        }
        return true;
    }

    /**
     * 在成功购买后更新游戏角色钱包并同步实际变动的资产
     *
     * @param role 当前认证角色
     * @param reward 已提交的三元组奖品
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    private void pushAssets(LocalRole role, List<Integer> reward, GameReply output) throws IOException {
        output.push(440, data("exp", payments.vip(role.id).experience, "record", gifts.purchased(role.id)));
        LocalRole updated = roles.findById(role.id).orElse(role);
        output.push(3, data("diamonds", updated.diamonds, "coins", updated.coins));
        inventory.pushBag(role, output);
        boolean pushElfs = false;
        for (int offset = 0; offset < reward.size(); offset += 3) {
            int type = reward.get(offset);
            if (type == VipGiftService.ELF || type == VipGiftService.CARRY) {
                pushElfs = true;
            } else if (type == VipGiftService.DRESS) {
                cosmetics.pushDress(role.id, output);
            }
        }
        if (pushElfs) {
            battle.pushElfs(role.id, output);
        }
    }
}
