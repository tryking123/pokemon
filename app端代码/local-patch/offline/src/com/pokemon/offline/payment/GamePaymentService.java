package com.pokemon.offline.payment;

import com.pokemon.offline.payment.api.entity.GamePaymentOrder;
import com.pokemon.offline.payment.api.entity.GameVipState;
import com.pokemon.offline.payment.api.vo.ConfirmGameOrderVO;
import com.pokemon.offline.payment.api.vo.GetOrderIdVO;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.util.List;
import java.util.Map;

/**
 * 原游戏角色模拟充值 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:40
 */
public interface GamePaymentService {
    /**
     * 为原 Unity WWWForm 下单请求分配待付订单
     *
     * @param vo 原 Unity 区服、账号、角色及档位
     * @param remoteAddress 请求来源地址
     * @return 原 SDKAdapter 读取的文本状态和订单号
     */
    String legacyOrder(GetOrderIdVO vo, String remoteAddress);

    /**
     * 为已经通过游戏网关鉴权的角色分配待付订单
     *
     * @param role 当前游戏角色
     * @param paysid 原游戏充值栏位
     * @return 待确认充值订单
     */
    GamePaymentOrder createOrder(LocalRole role, int paysid);

    /**
     * 查询本地 Android 即将展示的模拟订单
     *
     * @param authorization 本地账号 HTTP 授权头
     * @param orderId 服务端订单编号
     * @param vo Android 游戏桥传入的角色与商品标识
     * @return 订单金额、到账钻石数、状态及当前角色余额
     */
    Object quote(String authorization, String orderId, ConfirmGameOrderVO vo);

    /**
     * 确认模拟订单并幂等写入同一角色的钻石余额
     *
     * @param authorization 本地账号 HTTP 授权头
     * @param orderId 服务端订单编号
     * @param vo Android 游戏桥传入的角色及商品标识
     * @return 已确认订单及当前角色余额
     */
    Object confirm(String authorization, String orderId, ConfirmGameOrderVO vo);

    /**
     * 获取最近确认但尚未发送游戏通知的订单
     *
     * @param roleId 当前角色标识
     * @return 待通知的充值订单
     */
    List<GamePaymentOrder> unnotified(String roleId);

    /**
     * 读取充值界面使用的档位累计次数
     *
     * @param roleId 当前角色标识
     * @return 包含充值栏位 id 和已结算次数 n 的列表
     */
    List<Map<String, Object>> counts(String roleId);

    /**
     * 读取角色当前的持久钻石余额
     *
     * @param roleId 当前角色标识
     * @return 角色钻石余额
     */
    long diamonds(String roleId);

    /** 使用角色有效代金券购买具有原 ifvoucher 价格的游戏商品。
     * @param roleId 角色编号
     * @param paysid 原商品编号
     * @param receipt 当前连接操作凭据
     * @return 是否成功扣券并完成订单
     */
    boolean redeemVoucher(String roleId, int paysid, String receipt);

    /**
     * 读取游戏角色由模拟订单真实累计的 VIP 状态
     *
     * @param roleId 当前角色编号
     * @return 累计经验和当前等级
     */
    GameVipState vip(String roleId);

    /**
     * 判断原游戏商品到账是否会修改普通背包
     *
     * @param paysid 原商品档位
     * @return 是否包含普通物品
     */
    boolean hasItemReward(int paysid);

    /**
     * 跟踪已输出到游戏连接的充值通知
     *
     * @param roleId 当前角色标识
     * @param orderId 已发送通知的订单号
     * @return 是否更新通知记录
     */
    boolean markNotified(String roleId, String orderId);
}
