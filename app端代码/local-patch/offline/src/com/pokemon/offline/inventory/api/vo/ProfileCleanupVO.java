package com.pokemon.offline.inventory.api.vo;

/**
 * 人物资料清除操作视图对象，用于本地界面提交清除类型和重试凭据。
 *
 * @author tongruiyuan
 * @since 2026-11-22 01:21
 */
public final class ProfileCleanupVO {
    /** 清除类型：1为全部精灵，2为背包物品，3为邮箱。 */
    public final int kind;
    /** 同一次确认操作保持不变的请求编号。 */
    public final String requestId;

    /**
     * 保存人物资料页提交的清除参数。
     *
     * @param kind 清除类型
     * @param requestId 本次操作请求编号
     */
    public ProfileCleanupVO(int kind, String requestId) {
        this.kind = kind;
        this.requestId = requestId;
    }
}
