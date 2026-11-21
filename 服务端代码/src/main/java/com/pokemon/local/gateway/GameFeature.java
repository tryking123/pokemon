package com.pokemon.local.gateway;

import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;

/**
 * 已登录本地角色的游戏功能接口
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:33
 */
interface GameFeature {

    /**
     * 处理本功能负责的客户端请求
     *
     * @param role 当前已认证的本地角色
     * @param request 客户端请求
     * @param output 应答及推送通道
     * @return 是否由本功能处理
     * @throws IOException 发送消息失败
     */
    boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException;

    /**
     * 进入场景后同步本功能的角色快照
     *
     * @param role 当前已认证的本地角色
     * @param output 应答及推送通道
     * @throws IOException 发送消息失败
     */
    default void synchronize(LocalRole role, GameReply output) throws IOException {
    }

    /**
     * 在游戏心跳后发送已提交的游戏状态变化
     *
     * @param role 当前已认证的本地角色
     * @param output 推送通道
     * @throws IOException 发送消息失败
     */
    default void tick(LocalRole role, GameReply output) throws IOException {
    }
}
