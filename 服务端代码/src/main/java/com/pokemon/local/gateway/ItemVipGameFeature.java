package com.pokemon.local.gateway;

import com.pokemon.local.payment.ItemVipService;
import com.pokemon.local.payment.VoucherService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.api.entity.GameVipState;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原背包 VIP 经验卡、钻石券及永久代金券入口与资产同步。
 *
 * @author tongruiyuan
 * @since 2026-11-20 21:38
 */
@Service
@Order(-90)
final class ItemVipGameFeature implements GameFeature {
    private final ItemVipService items;
    private final GamePaymentService payments;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final VoucherService vouchers;
    private final Map<GameReply, String> connections = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定道具结算与角色、VIP、背包同步。
     * @param items 道具结算
     * @param payments VIP 汇总
     * @param roles 角色钱包
     * @param bags 背包同步
     * @param vouchers 代金券钱包同步
     */
    ItemVipGameFeature(ItemVipService items, GamePaymentService payments, RoleService roles, BagSnapshot bags,
                       VoucherService vouchers) {
        this.items = items;
        this.payments = payments;
        this.roles = roles;
        this.bags = bags;
        this.vouchers = vouchers;
    }

    /**
     * 从原 C2401 消耗道具，成功应答前同步库存及 VIP 经验。
     * @param role 当前角色
     * @param request 原请求
     * @param output 当前连接
     * @return 是否处理了请求
     * @throws IOException 协议发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long item = number(request.body, "id", 0);
        if (request.type != 2401 || !ItemVipService.supports(item)) return false;
        if (request.session <= 0) return true;
        long quantity = number(request.body, "num", 1);
        String receipt = connections.computeIfAbsent(output, key -> UUID.randomUUID().toString())
                + ":" + request.session;
        int error = items.use(role.id, item, quantity, number(request.body, "giftid", 0), receipt);
        if (error == 0) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            GameVipState vip = payments.vip(role.id);
            bags.push(role.id, output);
            output.push(441, data("level", vip.level, "exp", vip.experience));
            Map<String, Object> wallet = vouchers.wallet(role.id);
            wallet.put("diamonds", saved.diamonds);
            wallet.put("vip", vip.level);
            output.push(3, wallet);
            if (item == 31007 || item == 31008) output.push(14, data("flags", "item.vip_coupon", "adds",
                    data("diamonds", Math.multiplyExact(quantity, 1000))));
        }
        output.send(request, data("errno", error));
        return true;
    }
}
