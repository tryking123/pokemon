package com.pokemon.offline.payment.api.vo;

/**
 * 原 Unity SDKAdapter 下单表单
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
public final class GetOrderIdVO {
    public final String accountId;
    public final String serverId;
    public final String roleId;
    public final String appId;
    public final String channel;
    public final String productId;

    /**
     * 绑定原 Unity 下单参数
     *
     * @param accountId 登录账号编号
     * @param serverId 区服编号
     * @param roleId 角色编号
     * @param appId 原 Unity 应用编号
     * @param channel 原 Unity 渠道编号
     * @param productId 充值栏位
     */
    public GetOrderIdVO(String accountId, String serverId, String roleId, String appId,
                        String channel, String productId) {
        this.accountId = accountId;
        this.serverId = serverId;
        this.roleId = roleId;
        this.appId = appId;
        this.channel = channel;
        this.productId = productId;
    }
}
