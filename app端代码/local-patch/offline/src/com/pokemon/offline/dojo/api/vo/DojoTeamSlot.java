package com.pokemon.offline.dojo.api.vo;

/**
 * 原客户端提交的道馆阵法位置。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:55
 */
public final class DojoTeamSlot {
    /** 角色精灵实例编号。 */
    public final String elfId;
    /** 阵法格。 */
    public final long position;
    /** 上阵顺序。 */
    public final long battleId;

    /**
     * 绑定原游戏发来的精灵阵法位置。
     *
     * @param elfId 精灵实例编号
     * @param position 阵法格
     * @param battleId 上阵顺序
     */
    public DojoTeamSlot(String elfId, long position, long battleId) {
        this.elfId = elfId;
        this.position = position;
        this.battleId = battleId;
    }
}
