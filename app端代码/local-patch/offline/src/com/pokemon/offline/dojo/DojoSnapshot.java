package com.pokemon.offline.dojo;

import com.pokemon.offline.dojo.api.entity.DojoPlacement;
import java.util.List;

/**
 * 道馆和当前编队的协议快照。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:56
 */
public final class DojoSnapshot {
    public final int dojo;
    public final int history;
    public final int tactics;
    public final int defeated;
    public final int failures;
    public final int practices;
    public final List<DojoPlacement> team;

    /**
     * 创建已经保存的道馆状态快照。
     *
     * @param dojo 当前挑战道馆
     * @param history 历史最高通关道馆
     * @param tactics 选用的阵法类型
     * @param defeated 当前道馆已击败的目标数
     * @param failures 当日道馆失败次数
     * @param practices 当日道馆热身次数
     * @param team 本地保存的阵容
     */
    DojoSnapshot(int dojo, int history, int tactics, int defeated, int failures, int practices, List<DojoPlacement> team) {
        this.dojo = dojo;
        this.history = history;
        this.tactics = tactics;
        this.defeated = defeated;
        this.failures = failures;
        this.practices = practices;
        this.team = team;
    }
}
