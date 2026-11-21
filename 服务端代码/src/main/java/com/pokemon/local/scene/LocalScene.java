package com.pokemon.local.scene;

import java.util.Collections;
import java.util.List;

/**
 * 从底包配置和瓦片图中取得的可用本地场景
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
public final class LocalScene {
    public final int id;
    public final List<SceneActor> npcs;
    public final List<SceneActor> monsters;
    private final int width;
    private final int height;

    /**
     * 保存场景尺寸及原始对象列表
     *
     * @param id 场景编号
     * @param width 地图宽，单位为格
     * @param height 地图高，单位为格
     * @param npcs NPC 列表
     * @param monsters 怪物列表
     */
    LocalScene(int id, int width, int height, List<SceneActor> npcs, List<SceneActor> monsters) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.npcs = Collections.unmodifiableList(npcs);
        this.monsters = Collections.unmodifiableList(monsters);
    }

    /**
     * 校验服务器坐标是否处于原始地图内
     *
     * @param x 横坐标
     * @param y 纵坐标
     * @return 坐标是否有效
     */
    public boolean contains(long x, long y) {
        return x >= 0 && y >= 0 && x < width * SceneService.CELL_SIZE && y < height * SceneService.CELL_SIZE;
    }
}
