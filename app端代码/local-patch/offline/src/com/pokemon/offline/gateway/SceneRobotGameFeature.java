package com.pokemon.offline.gateway;

import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;

/**
 * 响应原版城市的机器人查询并返回单人场景中的实际空列表。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:06
 */
@Service
final class SceneRobotGameFeature implements GameFeature {
    /**
     * 返回当前单人场景中的机器人数量和列表。
     *
     * @param role 当前已认证角色
     * @param request 原客户端的机器人查询请求
     * @param output 当前游戏连接
     * @return 是否处理了该协议
     * @throws IOException 写入连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 2517) {
            return false;
        }
        if (request.session > 0) {
            output.send(request, data("errno", 0, "num", 0, "list", Collections.emptyList()));
        }
        return true;
    }
}
