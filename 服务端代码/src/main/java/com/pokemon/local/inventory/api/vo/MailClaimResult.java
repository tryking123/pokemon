package com.pokemon.local.inventory.api.vo;

import java.util.List;

/**
 * 系统邮件领取结果，用于响应本次操作及刷新角色资产。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:26
 */
public final class MailClaimResult {
    /** 本请求领尽附件的邮件编号。 */
    public final List<String> completedIds;
    /** 本次操作是否实际发放奖励。 */
    public final boolean delivered;
    /** 本次操作是否发放了精灵实例。 */
    public final boolean elfDelivered;

    /** 本次操作是否发放时装碎片。 */
    public final boolean dressChipDelivered;

    /** 本次操作是否发放了携带品或 P 力石实例。 */
    public final boolean gearDelivered;
    /** 本次操作是否领取了超古代石盘碎片。 */
    public final boolean tabletDelivered;

    /**
     * 保存协议应答和资产种类。
     *
     * @param completedIds 已领尽邮件编号
     * @param delivered 是否发放资产
     * @param elfDelivered 是否发放精灵实例
     * @param dressChipDelivered 是否发放时装碎片
     * @param gearDelivered 是否发放精灵装备实例
     * @param tabletDelivered 是否发放超古代石盘碎片
     */
    public MailClaimResult(List<String> completedIds, boolean delivered, boolean elfDelivered, boolean dressChipDelivered,
                           boolean gearDelivered, boolean tabletDelivered) {
        this.completedIds = completedIds;
        this.delivered = delivered;
        this.elfDelivered = elfDelivered;
        this.dressChipDelivered = dressChipDelivered;
        this.gearDelivered = gearDelivered;
        this.tabletDelivered = tabletDelivered;
    }
}
