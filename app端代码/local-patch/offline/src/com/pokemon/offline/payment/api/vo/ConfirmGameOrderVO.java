package com.pokemon.offline.payment.api.vo;

/**
 * Android 本地确认订单时携带的游戏角色及商品校验信息
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
public final class ConfirmGameOrderVO {
    /** 订单所属角色编号 */
    public String roleId;
    /** 订单所属区服编号 */
    public int realmId;
    /** 原游戏充值栏位 */
    public int paysid;
}
