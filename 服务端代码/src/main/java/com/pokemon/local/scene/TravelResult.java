package com.pokemon.local.scene;

import com.pokemon.local.role.api.entity.LocalRole;

/**
 * 本地传送校验结果和存档位置
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
public final class TravelResult {
    public final int errno;
    public final LocalRole role;

    /**
     * 建立传送结果
     *
     * @param errno 原协议错误码，零表示已完成传送
     * @param role 当前角色以及存档坐标
     */
    TravelResult(int errno, LocalRole role) {
        this.errno = errno;
        this.role = role;
    }
}
