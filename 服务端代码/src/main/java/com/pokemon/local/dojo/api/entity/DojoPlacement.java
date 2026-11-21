package com.pokemon.local.dojo.api.entity;

/**
 * 道馆阵容与助阵存档的精灵槽位。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:55
 */
public final class DojoPlacement {
    /** 精灵实例标识。 */
    public final String elfId;
    /** 精灵配置编号。 */
    public final int species;
    /** 阵法位置或助阵槽编号。 */
    public final int position;
    /** 道馆阵容中的上阵序号，助阵时为零。 */
    public final int battleId;

    /**
     * 创建已确认的精灵位置。
     *
     * @param elfId 精灵实例标识
     * @param species 精灵配置编号
     * @param position 阵法位置或助阵槽
     * @param battleId 道馆阵容序号，助阵时为零
     */
    public DojoPlacement(String elfId, int species, int position, int battleId) {
        this.elfId = elfId;
        this.species = species;
        this.position = position;
        this.battleId = battleId;
    }
}
