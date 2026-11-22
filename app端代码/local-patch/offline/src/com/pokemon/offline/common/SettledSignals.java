package com.pokemon.offline.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 刚结算完成的角色标记，让游戏网关在读取空档立刻读取已提交资产并推送。
 *
 * @author tongruiyuan
 * @since 2026-11-21 16:30
 */
public final class SettledSignals {
    private static final long WATCH_NANOS = 5_000_000_000L;
    private static final Map<String, Long> ROLES = new ConcurrentHashMap<>();

    private SettledSignals() {
    }

    /**
     * 标记角色刚完成结算，五秒内由在线连接轮询推送。
     *
     * @param roleId 已结算角色编号
     */
    public static void mark(String roleId) {
        if (roleId != null) {
            ROLES.put(roleId, System.nanoTime() + WATCH_NANOS);
        }
    }

    /**
     * 查询角色是否仍在结算后的推送窗口内。
     *
     * @param roleId 角色编号
     * @return 是否需要立即检查推送
     */
    public static boolean active(String roleId) {
        if (roleId == null) {
            return false;
        }
        Long deadline = ROLES.get(roleId);
        if (deadline == null) {
            return false;
        }
        if (System.nanoTime() > deadline) {
            ROLES.remove(roleId, deadline);
            return false;
        }
        return true;
    }
}
