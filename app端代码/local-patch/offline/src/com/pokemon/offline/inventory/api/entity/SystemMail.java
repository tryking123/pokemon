package com.pokemon.offline.inventory.api.entity;

import java.util.List;

/**
 * 单玩家系统邮件和附件实体
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:37
 */
public final class SystemMail {
    public final String id;
    public final String subject;
    public final String body;
    public final long createdAt;
    public final long expiresAt;
    public final boolean opened;
    public final boolean claimed;
    public final long coins;
    public final long diamonds;
    /** 处于待领取状态的道馆积分。 */
    public final long dojoPoints;
    public final int itemId;
    public final long quantity;
    /** 支持多个物品和精灵的新邮件附件 */
    public final List<SystemMailReward> rewards;

    /**
     * 构造系统邮件及其附件
     *
     * @param id 邮件标识
     * @param subject 邮件主题
     * @param body 邮件正文
     * @param createdAt 创建时间
     * @param expiresAt 到期时间
     * @param opened 是否已读
     * @param claimed 是否已领附件
     * @param coins 附件金币数
     * @param diamonds 附件钻石数
     * @param dojoPoints 驻防结算积分
     * @param itemId 附件物品编号
     * @param quantity 附件物品数量
     * @param rewards 新版附件，可为空列表
     */
    public SystemMail(String id, String subject, String body, long createdAt, long expiresAt, boolean opened,
                      boolean claimed, long coins, long diamonds, long dojoPoints, int itemId, long quantity,
                      List<SystemMailReward> rewards) {
        this.id = id;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.opened = opened;
        this.claimed = claimed;
        this.coins = coins;
        this.diamonds = diamonds;
        this.dojoPoints = dojoPoints;
        this.itemId = itemId;
        this.quantity = quantity;
        this.rewards = rewards;
    }
}
