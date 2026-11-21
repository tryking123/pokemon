package com.pokemon.local.battle.api.entity;

/**
 * 角色最新一场单人战斗的服务器存档
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
public final class BattleAttempt {

    /** 战斗尝试编号。 */
    public final String id;
    /** 服务器确认的遭遇编号。 */
    public final int encounter;
    /** 客户端重放本次战斗使用的随机种子。 */
    public final int seed;
    /** 尝试创建的 UTC 秒数。 */
    public final long startedAt;
    /** 已击败敌方槽位的位图。 */
    public int defeatedMask;
    /** 当前尝试状态。 */
    public String status;

    /**
     * 还原已保存的副本尝试。
     *
     * @param id 尝试标识
     * @param encounter 遭遇编号
     * @param seed 客户端战斗随机种子
     * @param defeatedMask 已击败敌方槽位位图
     * @param startedAt 开战时间戳
     * @param status 当前状态
     */
    public BattleAttempt(String id, int encounter, int seed, int defeatedMask, long startedAt, String status) {
        this.id = id;
        this.encounter = encounter;
        this.seed = seed;
        this.defeatedMask = defeatedMask;
        this.startedAt = startedAt;
        this.status = status;
    }
}
