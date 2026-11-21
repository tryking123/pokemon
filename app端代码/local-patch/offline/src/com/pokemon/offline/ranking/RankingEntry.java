package com.pokemon.offline.ranking;

import com.pokemon.offline.role.api.entity.LocalRole;

/**
 * 单玩家普通榜的可核对条目
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:28
 */
public final class RankingEntry {
    /** 排名条目的实际角色。 */
    public final LocalRole role;
    /** 归入此榜的真实统计值。 */
    public final long value;
    /** 精灵战力榜中的精灵编号，其他榜为零。 */
    public final int elfConfigId;

    /**
     * 构造有事实来源的排名条目
     *
     * @param role 实际存在的本地角色
     * @param value 该榜对应的角色或精灵统计
     * @param elfConfigId 精灵编号，其他榜使用零
     */
    RankingEntry(LocalRole role, long value, int elfConfigId) {
        this.role = role;
        this.value = value;
        this.elfConfigId = elfConfigId;
    }
}
