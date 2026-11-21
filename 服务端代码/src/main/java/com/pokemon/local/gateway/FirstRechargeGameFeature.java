package com.pokemon.local.gateway;

import com.pokemon.local.payment.FirstRechargeService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.api.entity.FirstRechargeState;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 原 Unity 新手充值返利页面的应答与周期状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:25
 */
@Service
@Order(1)
final class FirstRechargeGameFeature implements GameFeature {
    private static final int INFO = 277;
    private static final int CLAIM = 278;
    private static final int FLAG = 811;
    private final FirstRechargeService rebates;
    private final GamePaymentService payments;
    private final InventoryGameFeature bags;
    private final Map<GameReply, FirstRechargeState> states = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定活动业务与资产同步
     *
     * @param rebates 角色返利资格及发放服务
     * @param payments 角色钻石余额
     * @param bags 角色背包推送
     */
    FirstRechargeGameFeature(FirstRechargeService rebates, GamePaymentService payments, InventoryGameFeature bags) {
        this.rebates = rebates;
        this.payments = payments;
        this.bags = bags;
    }

    /**
     * 应答返利档位详情或领取
     *
     * @param role 当前认证角色
     * @param request 原 Unity 请求
     * @param output 网关应答通道
     * @return 是否已处理
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != INFO && request.type != CLAIM) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == INFO) {
            FirstRechargeState state = rebates.snapshot(role.id);
            FirstRechargeState prior = states.put(output, state);
            if (prior == null || prior.periodStart != state.periodStart || prior.flag != state.flag) {
                output.push(FLAG, data("flag", state.flag));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int sid = FirstRechargeService.FIRST_SLOT; sid <= FirstRechargeService.LAST_SLOT; sid++) {
                rows.add(data("index", sid, "rew1", rebates.diamondRebate(sid), "rew2", rebates.items(),
                        "flag", sid == state.paysid ? state.flag : FirstRechargeService.LOCKED));
            }
            output.send(request, data("errno", 0, "info", rows, "show", FirstRechargeService.SHOW_ELF));
            return true;
        }

        FirstRechargeState prior = states.get(output);
        boolean granted = prior != null && rebates.claim(role.id, prior.periodStart);
        output.send(request, data("errno", granted ? 0 : 1));
        synchronize(role, output);
        if (granted) {
            bags.pushBag(role, output);
            output.push(3, data("diamonds", payments.diamonds(role.id)));
        }
        return true;
    }

    /**
     * 在活动列表之后、充值商品快照之前同步本轮状态
     *
     * @param role 当前认证角色
     * @param output 网关推送通道
     * @throws IOException 连接写入失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        FirstRechargeState state = rebates.snapshot(role.id);
        output.push(FLAG, data("flag", state.flag));
        states.put(output, state);
    }

    /**
     * 当当前活动轮次到期时清理客户端上一期状态
     *
     * @param role 当前认证角色
     * @param output 网关推送通道
     * @throws IOException 连接写入失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        FirstRechargeState prior = states.get(output);
        if (prior == null || Instant.now().getEpochSecond() >= prior.nextRefresh) {
            synchronize(role, output);
        }
    }
}
