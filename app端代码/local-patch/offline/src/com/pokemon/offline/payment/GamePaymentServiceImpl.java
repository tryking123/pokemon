package com.pokemon.offline.payment;

import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.common.SettledSignals;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.payment.api.entity.GamePaymentOrder;
import com.pokemon.offline.payment.api.entity.GameVipState;
import com.pokemon.offline.payment.api.vo.ConfirmGameOrderVO;
import com.pokemon.offline.payment.api.vo.GetOrderIdVO;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 原游戏角色模拟充值 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:40
 */
@Service
public class GamePaymentServiceImpl implements GamePaymentService {
    private static final int REALM_ID = 1;
    private static final long ORDER_DURATION = Duration.ofMinutes(15).toMillis();
    private static final String GAME_APP_ID = "cn.yytx.fkbbds1";
    private static final String GAME_CHANNEL = "ds0051";
    private static final long[] VIP_THRESHOLDS = {
            0, 8750, 26250, 43750, 131250, 218750, 350000, 656250, 1312500,
            2625000, 4375000, 8750000, 13125000, 17500000, 21875000, 26250000, 30625000, 35000000
    };
    private final AccountService accounts;
    private final GamePaymentMapper payments;
    private final RoleService roles;
    private final InventoryService inventory;
    private final CardFundService benefits;
    private final WeeklyGiftService gifts;
    private final TimedPaymentShopService shop;
    private final LuxuryGiftService luxury;
    private final VoucherService vouchers;
    private final BlindBoxService boxes;

    /**
     * 初始化原游戏模拟订单业务
     *
     * @param accounts 本地账号服务
     * @param payments 角色充值 Mapper
     * @param roles 本地角色资产服务
     * @param inventory 本地礼包道具发放
     * @param benefits 福利卡和基金的激活校验
     * @param gifts 活动礼包限购和发货
     * @param shop 限时商城购买资格与订单期限
     * @param luxury 宝可梦节日手领礼包
     * @param vouchers 每日代金券与抵扣账本
     * @param boxes 盲盒已付库存
     */
    public GamePaymentServiceImpl(AccountService accounts, GamePaymentMapper payments, RoleService roles,
                                  InventoryService inventory, CardFundService benefits, WeeklyGiftService gifts,
                                  TimedPaymentShopService shop, LuxuryGiftService luxury, VoucherService vouchers, BlindBoxService boxes) {
        this.boxes=boxes;
        this.vouchers = vouchers;
        this.accounts = accounts;
        this.payments = payments;
        this.roles = roles;
        this.inventory = inventory;
        this.benefits = benefits;
        this.gifts = gifts;
        this.shop = shop;
        this.luxury = luxury;
    }

    /**
     * 绑定原 Unity 表单请求与同账户同区角色，只创建待付订单
     *
     * @param vo 原 Unity 区服、账号、角色及档位
     * @param remoteAddress 发起下单的 HTTP 地址
     * @return 原 SDKAdapter 的文本状态及订单编号
     */
    @Override
    @Transactional
    public String legacyOrder(GetOrderIdVO vo, String remoteAddress) {
        if (!"127.0.0.1".equals(remoteAddress) && !"0:0:0:0:0:0:0:1".equals(remoteAddress)
                && !"::1".equals(remoteAddress)) {
            return "1,本地模拟充值只接受回环连接";
        }
        if (!Integer.toString(REALM_ID).equals(vo.serverId) || !GAME_APP_ID.equals(vo.appId)
                || !GAME_CHANNEL.equals(vo.channel) || !isIdentifier(vo.accountId)
                || !isIdentifier(vo.roleId) || vo.productId == null || !vo.productId.matches("[0-9]{1,4}")) {
            return "1,本地账号或商品信息无效";
        }
        try {
            GamePaymentOrder order = enqueue(vo.accountId, vo.roleId, REALM_ID,
                    Integer.parseInt(vo.productId));
            return "0," + order.id;
        } catch (ApiException rejected) {
            return "1," + rejected.getMessage();
        }
    }

    /**
     * 从已经登录的游戏连接创建待付订单
     *
     * @param role 当前网关角色
     * @param paysid 原游戏充值栏位
     * @return 待付订单或当前可重试的同档订单
     */
    @Override
    @Transactional
    public GamePaymentOrder createOrder(LocalRole role, int paysid) {
        return enqueue(role.accountId, role.id, role.realmId, paysid);
    }

    /**
     * 校验本地 Android 会话并返回待确认的服务端订单快照
     *
     * @param authorization 本地账号 HTTP 授权头
     * @param orderId 服务端订单编号
     * @param vo Android 桥传入的游戏角色及档位
     * @return 角色订单与余额
     */
    @Override
    @Transactional
    public Object quote(String authorization, String orderId, ConfirmGameOrderVO vo) {
        String accountId = accounts.requireAccount(authorization).id;
        long balance = ownedBalance(accountId, vo);
        GamePaymentOrder order = usableOrder(orderId, vo);
        return view(order, order.confirmed, balance);
    }

    /**
     * 对持有本地账号会话的角色原子结算订单
     *
     * @param authorization 本地账号 HTTP 授权头
     * @param orderId 服务端订单编号
     * @param vo Android 桥传入的游戏角色及档位
     * @return 当前结算状态与角色钻石余额
     */
    @Override
    @Transactional
    public Object confirm(String authorization, String orderId, ConfirmGameOrderVO vo) {
        String accountId = accounts.requireAccount(authorization).id;
        long balance = ownedBalance(accountId, vo);
        GamePaymentOrder order = usableOrder(orderId, vo);
        if (order.confirmed) {
            return view(order, true, balance);
        }
        long now = System.currentTimeMillis();
        return settle(order, balance, now);
    }

    /** 在角色事务内确认支付并发放原商品。
     * @param order 尚未结算的订单
     * @param balance 扣费前的角色钻石
     * @param now 确认毫秒时间
     * @return 已确认订单及资产
     */
    private Object settle(GamePaymentOrder order, long balance, long now) {
        if (now >= order.expiresAt) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_ORDER_EXPIRED", "模拟充值订单已过期，请重新下单");
        }
        gifts.purchaseDeadline(order.roleId, order.paysid, now);
        shop.purchaseDeadline(order.roleId, order.paysid, now);
        boolean deferredLuxury = LuxuryGiftService.supports(order.paysid) && luxury.reserved(order.id);
        if (deferredLuxury) {
            luxury.deadline(order.roleId, order.paysid, now);
        }
        benefits.requireAvailable(order.roleId, order.paysid, now);
        vouchers.deadline(order.roleId, order.paysid, now);
        if (balance > Long.MAX_VALUE - order.reward) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_BALANCE_LIMIT", "角色钻石达到上限");
        }
        if (payments.confirm(order.id, now) != 1) {
            throw new IllegalStateException("未能确认角色充值订单");
        }
        if (deferredLuxury) {
            return view(order, true, balance);
        }
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(order.paysid);
        vouchers.deliver(order.roleId, order.paysid, order.id, now,
                payments.countConfirmed(order.roleId, order.paysid) == 1);
        if (order.reward > 0) {
            roles.addCurrency(order.roleId, 0, order.reward, 0);
        }
        for (Map.Entry<Integer, Integer> item : product.items.entrySet()) {
            inventory.grant(order.roleId, item.getKey(), item.getValue());
        }
        for (Map.Entry<Integer, Integer> chip : product.chips.entrySet()) {
            inventory.grantChip(order.roleId, chip.getKey(), chip.getValue());
        }
        if (product.homeCoins > 0) {
            inventory.grantHomeCoins(order.roleId, product.homeCoins);
        }
        boxes.deliver(order.roleId,order.paysid);
        if (WeeklyGiftService.supports(order.paysid)) {
            gifts.deliver(order.roleId, order.paysid, order.id, now);
        }
        // 提交后由网关在连接空档立即推送本次到账，盲盒商城不必等下一次心跳。
        SettledSignals.mark(order.roleId);
        return view(order, true, balance + order.reward);
    }

    /**
     * 查询角色已结算且未通知游戏连接的订单
     *
     * @param roleId 当前角色编号
     * @return 待推送到账订单
     */
    @Override
    public List<GamePaymentOrder> unnotified(String roleId) {
        return payments.unnotified(roleId);
    }

    /**
     * 查询游戏内累计充值次数
     *
     * @param roleId 当前角色编号
     * @return 商品档位和结算次数
     */
    @Override
    public List<Map<String, Object>> counts(String roleId) {
        return payments.counts(roleId);
    }

    /**
     * 查询角色持有的钻石余额
     *
     * @param roleId 当前角色编号
     * @return 角色钻石余额
     */
    @Override
    public long diamonds(String roleId) {
        return payments.diamonds(roleId);
    }

    /**
     * 以底包普通充值每元一千基础钻石的比例计算经验，再对照 vip_proto 门槛
     *
     * @param roleId 当前角色编号
     * @return 当前角色 VIP 等级和累计未加赠的充值经验
     */
    @Override
    public GameVipState vip(String roleId) {
        long experience = Math.addExact(Math.multiplyExact(payments.confirmedAmount(roleId), 10),
                payments.itemVipExperience(roleId));
        int level = 0;
        while (level + 1 < VIP_THRESHOLDS.length && experience >= VIP_THRESHOLDS[level + 1]) {
            level++;
        }
        return new GameVipState(level, experience);
    }

    /**
     * 判断本地礼包是否含有普通道具
     *
     * @param paysid 原商品档位
     * @return 是否需要刷新角色背包
     */
    @Override
    public boolean hasItemReward(int paysid) {
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(paysid);
        return WeeklyGiftService.supports(paysid) || !product.items.isEmpty() || !product.chips.isEmpty();
    }

    /**
     * 记录已发送到游戏的结算通知
     *
     * @param roleId 当前角色编号
     * @param orderId 已通知的订单号
     * @return 是否成功记录
     */
    @Override
    public boolean markNotified(String roleId, String orderId) {
        return payments.notified(roleId, orderId, System.currentTimeMillis()) == 1;
    }

    /**
     * 在角色锁中查找同档有效订单或计算并保存结算快照
     *
     * @param accountId 角色所属账户
     * @param roleId 角色编号
     * @param realmId 角色所属区服
     * @param paysid 原游戏充值栏位
     * @return 当前可确认的订单
     */
    private GamePaymentOrder enqueue(String accountId, String roleId, int realmId, int paysid) {
        // 已下架活动不接受新订单，已保存的订单仍沿原确认路径保留权益。
        if (isRemovedProduct(paysid)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_REMOVED", "该活动已下架");
        }
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(paysid);
        if (!payments.lockRole(accountId, roleId, realmId).isPresent()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "没有找到相应的本地角色");
        }
        long now = System.currentTimeMillis();
        return enqueueLocked(roleId, paysid, product, now, orderDeadline(roleId, paysid, now));
    }

    /** 核对当前角色能否购买该商品，并取所有有效期中的最早截止时刻。
     * @param roleId 角色编号
     * @param paysid 原充值商品
     * @param now 下单毫秒时间
     * @return 本次订单的截止时刻
     */
    private long orderDeadline(String roleId, int paysid, long now) {
        benefits.requireAvailable(roleId, paysid, now);
        long expiry = Math.min(now + ORDER_DURATION, vouchers.deadline(roleId, paysid, now));
        expiry = Math.min(expiry, luxury.deadline(roleId, paysid, now));
        return Math.min(expiry, Math.min(gifts.purchaseDeadline(roleId, paysid, now), shop.purchaseDeadline(roleId, paysid, now)));
    }

    /** 在角色行锁内创建原商品订单，两种付款方式共用同一未付单及首充资格。
     * @param roleId 角色编号
     * @param paysid 商品编号
     * @param product 原商品
     * @param now 下单时间
     * @param deadline 本次购买可确认的最晚时刻
     * @return 可确认的订单
     */
    private GamePaymentOrder enqueueLocked(String roleId, int paysid, GamePaymentCatalog.Product product, long now, long deadline) {
        boolean deferredLuxury = LuxuryGiftService.supports(paysid);
        return payments.findPending(roleId, paysid, now).orElseGet(() -> {
            boolean first = product.firstBonus > 0 && payments.countConfirmed(roleId, paysid) == 0;
            long reward = deferredLuxury ? 0 : product.diamonds + (first ? product.firstBonus : 0);
            GamePaymentOrder order = new GamePaymentOrder(UUID.randomUUID().toString(), roleId,
                    paysid, product.amount, reward, deadline, false);
            if (payments.insert(order, now) != 1) {
                throw new IllegalStateException("未能写入角色充值订单");
            }
            if (deferredLuxury) {
                luxury.reserve(order.id);
            }
            return order;
        });
    }

    /** 用有效代金券支付并沿用同一套原商品确认与资产发放事务。
     * @param roleId 角色编号
     * @param paysid 原商品编号
     * @param receipt 原会话请求凭据
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean redeemVoucher(String roleId, int paysid, String receipt) {
        // 以下为已有完整商品与原 chongzhi.ifvoucher 的交集，面值均等于该行 chargeValue。
        if (!(paysid >= 1 && paysid <= 14 || paysid == 101 || paysid == 103 || paysid == 104 || paysid == 106
                || paysid >= 301 && paysid <= 306 || BlindBoxService.index(paysid)>0 || paysid == 501 || paysid == 503 || paysid == 504)) {
            return false;
        }
        LocalRole role = roles.lockRole(roleId);
        int previous = vouchers.purchased(roleId, receipt);
        if (previous != 0) {
            return previous == paysid;
        }
        if (isRemovedProduct(paysid)) return false;
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(paysid);
        long now = System.currentTimeMillis();
        long deadline;
        try {
            deadline = orderDeadline(roleId, paysid, now);
        } catch (ApiException unavailable) {
            return false;
        }
        if (role.diamonds > Long.MAX_VALUE - product.diamonds - product.firstBonus) {
            return false;
        }
        if (!vouchers.spend(roleId, receipt, product.amount / 100, now)) {
            return false;
        }
        GamePaymentOrder order = enqueueLocked(roleId, paysid, product, now, deadline);
        settle(order, role.diamonds, now);
        vouchers.record(roleId, receipt, paysid, order.id);
        return true;
    }

    /** 判断商品是否属于本轮已下架活动，现金与代金券共用同一规则。
     * @param paysid 原商品编号
     * @return 是否禁止新建购买
     */
    private boolean isRemovedProduct(int paysid) {
        return paysid == 301 || paysid >= 303 && paysid <= 306
                || paysid == 501 || paysid == 503 || paysid == 504;
    }

    /**
     * 校验本地账号的角色归属并持有角色余额锁
     *
     * @param accountId 通过 HTTP 会话校验的本地账户
     * @param vo 当前 Unity 角色与档位
     * @return 已锁定角色的钻石余额
     */
    private long ownedBalance(String accountId, ConfirmGameOrderVO vo) {
        if (vo == null || vo.realmId != REALM_ID || !isIdentifier(vo.roleId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "角色信息无效");
        }
        GamePaymentCatalog.require(vo.paysid);
        return payments.lockRole(accountId, vo.roleId, vo.realmId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "没有找到相应的本地角色"));
    }

    /**
     * 读取属于已授权角色且档位一致的可查询订单
     *
     * @param orderId 服务端订单编号
     * @param vo 当前 Unity 角色与档位
     * @return 状态未过期或者已确认的订单
     */
    private GamePaymentOrder usableOrder(String orderId, ConfirmGameOrderVO vo) {
        GamePaymentOrder order = payments.find(vo.roleId, orderId).filter(value -> value.paysid == vo.paysid)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "GAME_ORDER_NOT_FOUND", "没有找到当前角色的对应充值订单"));
        if (!order.confirmed && order.expiresAt <= System.currentTimeMillis()) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_ORDER_EXPIRED", "模拟充值订单已过期，请重新下单");
        }
        return order;
    }

    /**
     * 校验区服表单中使用的本地标识格式
     *
     * @param value 账号或角色编号
     * @return 是否为可接收的标识
     */
    private boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,36}");
    }

    /**
     * 返回游戏订单状态、入账快照和最新角色钻石余额
     *
     * @param order 服务器保存的游戏订单
     * @param confirmed 当前是否已确认
     * @param balance 角色钻石余额
     * @return Android 本地确认页面使用的字段
     */
    private Map<String, Object> view(GamePaymentOrder order, boolean confirmed, long balance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.id);
        result.put("status", confirmed ? "CONFIRMED" : "PENDING");
        result.put("roleId", order.roleId);
        result.put("paysid", order.paysid);
        result.put("amount", order.amount);
        result.put("reward", order.reward);
        result.put("balance", balance);
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(order.paysid);
        result.put("productName", product.name);
        result.put("voucherReward", GamePaymentCatalog.voucherAmount(order.paysid)
                + (payments.countConfirmed(order.roleId, order.paysid) == 0
                ? GamePaymentCatalog.voucherFirstBonus(order.paysid) : 0));
        result.put("activatesFund", order.paysid == CardFundService.FUND);
        if (LuxuryGiftService.supports(order.paysid) && luxury.reserved(order.id)) {
            result.put("claimInFestival", true);
            result.put("festivalCollected", confirmed && luxury.collected(order.id));
            result.put("items", Collections.emptyMap());
            return result;
        }
        if (shop.supports(order.paysid)) {
            result.put("claimInTimedShop", true);
            result.put("shopCollected", confirmed && shop.collected(order.id));
        }
        if(BlindBoxService.index(order.paysid)>0)result.put("blindBoxIndex",BlindBoxService.index(order.paysid));
        result.put("items", WeeklyGiftService.supports(order.paysid)
                ? gifts.itemsFor(order.paysid, order.expiresAt - 1) : product.items);
        return result;
    }
}
