package com.pokemon.offline.gateway;

import java.io.IOException;
import java.util.Map;

/**
 * 本地游戏网关的响应和推送出口
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:49
 */
interface GameReply {
    /** 设置本连接的定时玩法更新，传空时停止。
     * @param work 当前连接定时操作
     */
    default void periodic(OfflineGameSession.RequestWork work) {
    }

    /**
     * 读取当前连接在原场景协议中的玩家实体编号。
     *
     * @return 与 S3 session 相同的场景实体编号
     */
    int actorSession();

    /**
     * 使用原请求 session 发送响应
     *
     * @param request 原客户端请求
     * @param body 响应字段
     * @throws IOException 连接写入失败
     */
    void send(SprotoProtocol.Request request, Map<String, Object> body) throws IOException;

    /**
     * 向客户端发送服务端主动消息
     *
     * @param type 服务端协议编号
     * @param body 推送字段
     * @throws IOException 连接写入失败
     */
    void push(int type, Map<String, Object> body) throws IOException;
}
