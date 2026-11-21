package com.pokemon.local.scene;

/**
 * 地图 NPC 或怪物对象的配置标识和位置
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
public final class SceneActor {
    public final int sid;
    public final String uid;
    public final int x;
    public final int y;
    public final int face;

    /**
     * 从原始格点坐标构造网关单位的位置
     *
     * @param sid 原对象配置编号
     * @param uid 本地实例编号
     * @param x 原格点横坐标
     * @param y 原格点纵坐标
     * @param face 朝向
     */
    SceneActor(int sid, String uid, int x, int y, int face) {
        this.sid = sid;
        this.uid = uid;
        this.x = x * SceneService.CELL_SIZE;
        this.y = y * SceneService.CELL_SIZE;
        this.face = face;
    }
}
