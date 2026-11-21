package com.pokemon.local.gateway;

import com.pokemon.local.common.ApiException;
import com.pokemon.local.payment.CardFundService;
import com.pokemon.local.payment.WeeklyGiftService;
import com.pokemon.local.payment.TimedPaymentShopService;
import com.pokemon.local.payment.LuxuryGiftService;
import com.pokemon.local.payment.ClassicFirstChargeService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.payment.VipGiftService;
import com.pokemon.local.payment.VoucherService;
import com.pokemon.local.payment.api.entity.FirstRechargeState;
import com.pokemon.local.payment.api.entity.GamePaymentOrder;
import com.pokemon.local.payment.api.entity.GameVipState;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.stereotype.Component;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原游戏充值下单与到账推送
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:41
 */
@Component
final class PaymentGameFeature implements GameFeature {
    private static final int ASK_CHARGE = 2801;
    private static final int ASK_PAYMENT_GIFTS = 327;
    private static final int ASK_SEASONAL_PAYMENT_GIFTS = 423;
    private static final int ASK_CLASSIC_FIRST_CHARGE = 148;
    private static final int CLAIM_CLASSIC_FIRST_CHARGE = 149;

    private final WeeklyGiftService gifts;
    private final TimedPaymentShopService shop;
    private final LuxuryGiftService luxury;
    private final VoucherService vouchers;
    private final Map<GameReply, Long> voucherDay = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply, Long> freePreviews = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply, String> voucherConnections = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> luxuryPeriodEnds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,String> shopConnections = Collections.synchronizedMap(new WeakHashMap<>());
    private final GamePaymentService payments;
    private final InventoryGameFeature inventory;
    private final ClassicFirstChargeService firstCharge;
    private final VipGiftService vipGifts;
    private final CardFundGameFeature cardFund;
    private final Map<GameReply, FirstRechargeState> classicStates =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 初始化充值网关适配
     *
     * @param payments 本地游戏订单服务
     * @param inventory 角色礼包到账后的背包协议
     * @param firstCharge 经典首充活动
     * @param vipGifts 已购买的原版 VIP 礼包
     * @param cardFund 基金激活状态推送
     * @param gifts 连续充值与直购活动
     * @param shop 两版商城订单领取及货架
     * @param luxury 节日礼包领取与 S451 状态
     * @param vouchers 两类代金券余额及每日特惠
     */
    PaymentGameFeature(GamePaymentService payments, InventoryGameFeature inventory,
                       ClassicFirstChargeService firstCharge, VipGiftService vipGifts, CardFundGameFeature cardFund,
                       WeeklyGiftService gifts, TimedPaymentShopService shop, LuxuryGiftService luxury, VoucherService vouchers) {
        this.vouchers = vouchers;
        this.gifts = gifts;
        this.shop = shop;
        this.luxury = luxury;
        this.payments = payments;
        this.inventory = inventory;
        this.firstCharge = firstCharge;
        this.vipGifts = vipGifts;
        this.cardFund = cardFund;
    }

    /**
     * 处理当前角色从游戏发起的充值订单或礼包列表查询
     *
     * @param role 当前登录角色
     * @param request 原客户端 Sproto 请求
     * @param output 该连接的响应通道
     * @return 该消息是否由充值模块处理
     * @throws IOException 连接写入错误
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 10423 || request.type == 10424) {
            if (request.session <= 0) return true;
            long day = vouchers.today(System.currentTimeMillis());
            if (request.type == 10423) {
                freePreviews.put(output, day);
                output.send(request, vouchers.dailyFree(role.id, day));
            } else {
                boolean granted = vouchers.claimFree(role.id, freePreviews.getOrDefault(output, -1L));
                output.send(request, data("errno", granted ? 0 : 1));
                output.push(10014, data("got", vouchers.claimedFree(role.id, day)));
                if (granted) {
                    output.push(14, data("flags", "voucher.dayfree", "adds", data("limit_voucher", VoucherService.DAILY_FREE_AMOUNT)));
                    output.push(3, vouchers.wallet(role.id));
                }
            }
            return true;
        }
        if (request.type == 10425 || request.type == 10426) {
            if (request.session <= 0) {
                return true;
            }
            if (request.type == 10425) {
                output.send(request, vouchers.dailyShop(role.id));
            } else {
                String receipt = voucherConnections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                        + ":" + request.session;
                long sku = SprotoProtocol.number(request.body, "cfid", -1);
                boolean success = sku > 0 && sku <= Integer.MAX_VALUE && payments.redeemVoucher(role.id, (int) sku, receipt);
                Map<String, Object> result = vouchers.wallet(role.id);
                result.put("errno", success ? 0 : 1);
                output.send(request, result);
                if (success) {
                    notifyPayments(role, output, false);
                }
            }
            return true;
        }
        if (request.type == 289) {
            if (request.session > 0) {
                long sku = number(request.body, "idx", 0);
                String key = shopConnections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                        + ":289:" + request.session;
                Map<String,Object> claim = sku >= 501 && sku <= 504
                        ? luxury.claim(role.id, (int)sku, key) : data("errno", 100021);
                output.send(request, data("errno", claim.get("errno"), "idx", sku));
                if (claim.containsKey("adds")) {
                    output.push(14, data("flags", "festival.daygift", "adds", claim.get("adds")));
                    output.push(3, data("diamonds", payments.diamonds(role.id)));
                    inventory.pushBag(role, output);
                    inventory.pushHomeWallet(role.id, output);
                    inventory.pushMailCount(role, output);
                    output.push(451, data("record", luxury.records(role.id)));
                }
            }
            return true;
        }
        if (request.type == 421 || request.type == 422) {
            if (request.session > 0) {
                output.send(request, gifts.panel(role, request.type == 422));
            }
            return true;
        }
        if (request.type == ASK_CHARGE) {
            if (request.session > 0) {
                askCharge(role, request, output);
            }
            return true;
        }
        if (request.type == ASK_PAYMENT_GIFTS || request.type == ASK_SEASONAL_PAYMENT_GIFTS) {
            if (request.session > 0) {
                output.send(request, data("errno", 0, "list", shop.panel(role.id, request.type == ASK_SEASONAL_PAYMENT_GIFTS)));
            }
            return true;
        }
        if (request.type == 328 || request.type == 424) {
            if (request.session > 0) {
                long sku = number(request.body, "itype", 0);
                String key = shopConnections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                        + ":" + request.type + ":" + request.session;
                Map<String,Object> claim = sku >= 303 && sku <= 306
                        ? shop.claim(role.id, request.type == 424, (int)sku, key) : data("errno", 100021);
                output.send(request, data("errno", claim.get("errno"), "itype", sku));
                if (claim.containsKey("items")) {
                    output.push(14, data("flags", "shop.payme", "adds", data("items", claim.get("items"))));
                    inventory.pushBag(role, output);
                    inventory.pushMailCount(role, output);
                }
            }
            return true;
        }
        if (request.type == ASK_CLASSIC_FIRST_CHARGE || request.type == CLAIM_CLASSIC_FIRST_CHARGE) {
            if (request.session > 0) {
                classicFirstCharge(role, request, output);
            }
            return true;
        }
        return false;
    }

    /**
     * 进入游戏时恢复充值计数及未送达的确认为真到账消息
     *
     * @param role 当前登录角色
     * @param output 该连接的推送出口
     * @throws IOException 连接写入错误
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        notifyPayments(role, output, true);
    }

    /**
     * 当心跳到达时，将 Android HTTP 已结算的订单同步给原 Unity 游戏
     *
     * @param role 当前登录角色
     * @param output 该连接的推送出口
     * @throws IOException 连接写入错误
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        notifyPayments(role, output, false);
    }

    /**
     * 按照原 order 结构回复待付订单，实际到账仍由明确确认触发
     *
     * @param role 当前登录角色
     * @param request 原 ask_charge 请求
     * @param output 当前响应出口
     * @throws IOException 连接写入错误
     */
    private void askCharge(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long sid = number(request.body, "sid", -1);
        if (role == null || sid <= 0 || sid > Integer.MAX_VALUE) {
            output.send(request, data("errno", 1));
            return;
        }
        try {
            GamePaymentOrder order = payments.createOrder(role, (int) sid);
            output.send(request, data("errno", 0, "order",
                    data("orderid", order.id, "fee", order.amount, "cbkurl", "")));
        } catch (ApiException rejected) {
            output.send(request, data("errno", 1));
        }
    }

    /**
     * 查询或领取老版首充页的奖励
     *
     * @param role 已验证的本地角色
     * @param request 经典首充请求
     * @param output 原客户端应答通道
     * @throws IOException 写入失败
     */
    private void classicFirstCharge(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == ASK_CLASSIC_FIRST_CHARGE) {
            FirstRechargeState state = pushClassic(role.id, output, false);
            output.send(request, data("errno", 0, "rew", firstCharge.rewards(), "flag", state.flag, "show", 0));
            return;
        }
        FirstRechargeState prior = classicStates.get(output);
        boolean granted = prior != null && firstCharge.claim(role.id, prior.periodStart);
        output.send(request, data("errno", granted ? 0 : 1));
        pushClassic(role.id, output, false);
        if (granted) {
            inventory.pushBag(role, output);
        }
    }

    /**
     * 将当前经典首充状态同步给原客户端，避免在同一状态重复推送
     *
     * @param roleId 当前角色编号
     * @param output 原客户端推送通道
     * @param initial 是否首次进场
     * @return 当前已同步的活动状态
     * @throws IOException 写入失败
     */
    private FirstRechargeState pushClassic(String roleId, GameReply output, boolean initial) throws IOException {
        FirstRechargeState state = firstCharge.snapshot(roleId);
        FirstRechargeState prior = classicStates.put(output, state);
        if (initial || prior == null || state.periodStart != prior.periodStart || state.flag != prior.flag) {
            output.push(439, data("flag", state.flag, "show", 0));
        }
        return state;
    }

    /**
     * 推送角色实际钻石、档位购买次数和新增到账订单号
     *
     * @param role 当前游戏连接角色
     * @param output 当前游戏连接响应通道
     * @param initial 是否初次加载充值状态
     * @throws IOException 连接写入错误
     */
    private void notifyPayments(LocalRole role, GameReply output, boolean initial) throws IOException {
        List<GamePaymentOrder> orders = payments.unnotified(role.id);
        GameVipState vip = initial || !orders.isEmpty() ? payments.vip(role.id) : null;
        if (initial) {
            output.push(440, data("exp", vip.experience, "record", vipGifts.purchased(role.id)));
        }
        if (!orders.isEmpty()) {
            output.push(441, data("level", vip.level, "exp", vip.experience));
            Map<String, Object> balances = vouchers.wallet(role.id);
            balances.put("diamonds", payments.diamonds(role.id));
            balances.put("vip", vip.level);
            output.push(3, balances);
            inventory.pushHomeWallet(role.id, output);
        }
        long today = vouchers.today(System.currentTimeMillis());
        boolean newDay = today != voucherDay.getOrDefault(output, -1L);
        if (newDay) {
            voucherDay.put(output, today);
            output.push(10014, data("got", vouchers.claimedFree(role.id, today)));
            if (orders.isEmpty()) output.push(3, vouchers.wallet(role.id));
        }
        if (initial || newDay || !orders.isEmpty()) {
            if (initial || newDay || orders.stream().anyMatch(o -> o.paysid == 2001 || o.paysid == 2002)) {
                output.push(10015, data("record", vouchers.dailyShop(role.id).get("record")));
            }
            output.push(1402, data("data", payments.counts(role.id)));
        }
        if (orders.stream().anyMatch(order -> payments.hasItemReward(order.paysid))) {
            inventory.pushBag(role, output);
        }
        if (orders.stream().anyMatch(order -> WeeklyGiftService.supports(order.paysid))) {
            inventory.pushMailCount(role, output);
        }
        FirstRechargeState classic = classicStates.get(output);
        if (initial || !orders.isEmpty() || classic == null || Instant.now().getEpochSecond() >= classic.nextRefresh) {
            pushClassic(role.id, output, initial);
        }
        for (GamePaymentOrder order : orders) {
            if (order.paysid == CardFundService.FUND) {
                cardFund.synchronize(role, output);
            }
            output.push(11, data("orderid", order.id, "paysid", order.paysid));
            payments.markNotified(role.id, order.id);
        }
        long now = Instant.now().getEpochSecond();
        if (initial || now >= luxuryPeriodEnds.getOrDefault(output, 0L)
                || orders.stream().anyMatch(order -> LuxuryGiftService.supports(order.paysid))) {
            output.push(451, data("record", luxury.records(role.id)));
            luxuryPeriodEnds.put(output, luxury.periodEnd(now));
        }
    }
}
