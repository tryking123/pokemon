package com.pokemon.local.scene;

import java.util.Set;

/**
 * 场景快照读取已经结算的怪物组状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:48
 */
public interface SceneEncounterState {

    /**
     * 返回角色已经击败的首个副本怪物组
     *
     * @param roleId 当前角色主键
     * @return 已结算的原游戏怪物组编号
     */
    Set<Integer> clearedEncounters(String roleId);
}
