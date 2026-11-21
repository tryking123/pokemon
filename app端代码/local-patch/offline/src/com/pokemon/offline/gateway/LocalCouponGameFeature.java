package com.pokemon.offline.gateway;

import com.pokemon.offline.payment.LocalCouponService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import com.pokemon.offline.platform.stereotype.Component;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 本地每日活动券货架及原使用、出售、充值券协议。
 * @author tongruiyuan
 * @since 2026-11-20 15:32
 */
@Component
final class LocalCouponGameFeature implements GameFeature {
    private final LocalCouponService coupons;
    private final RoleService roles;
    private final Map<GameReply,String> selected = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,String> connections = Collections.synchronizedMap(new WeakHashMap<>());

    /** @param coupons 本地券账本
     * @param roles 角色最新钱包
     */
    LocalCouponGameFeature(LocalCouponService coupons, RoleService roles) {
        this.coupons = coupons;
        this.roles = roles;
    }

    /** 接收原券号或充值档位，返回实际结果并刷新本角色钱包。
     * @param role 已认证角色
     * @param request 客户端请求
     * @param output 当前连接应答和推送
     * @return 是否处理本请求
     * @throws IOException 连接发送失败
     */
    @Override public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int type = request.type;
        if (type != 10991 && type != 377 && type != 378 && type != 10410) return false;
        if (request.session <= 0) return true;
        if (type == 10991) {
            long kind = number(request.body,"kind",0);
            Map<String,Object> state = coupons.panel(role.id,kind,number(request.body,"issue",0) == 1,number(request.body,"day",-1));
            selected.remove(output);
            if (kind == 1004 && Integer.valueOf(1).equals(state.get("status"))) {
                selected.put(output,(String) state.get("uid"));
            }
            output.send(request,state);
            return true;
        }
        String uid = type == 10410 ? selected.get(output) : String.valueOf(request.body.get("sid"));
        boolean product = type != 10410 || number(request.body,"paysid",0) == 5 && number(request.body,"n",0) == 1;
        String receipt = type == 10410 ? connections.computeIfAbsent(output,k -> UUID.randomUUID().toString()) + ":" + request.session : null;
        Map<String,Object> award = product ? coupons.spend(role.id,uid,type,receipt) : Collections.emptyMap();
        Map<String,Object> response = data("errno",award.isEmpty() ? 1 : 0);
        if (type != 10410) response.put("sid",uid);
        output.send(request,response);
        if (!award.isEmpty()) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(14,data("flags","local.coupon","adds",award));
            output.push(3,data("coins",saved.coins,"diamonds",saved.diamonds));
        }
        return true;
    }
}
