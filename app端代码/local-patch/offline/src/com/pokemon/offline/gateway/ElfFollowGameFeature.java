package com.pokemon.offline.gateway;

import com.pokemon.offline.elf.ElfFollowService;
import com.pokemon.offline.elf.ElfFollowService.Result;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 接入原精灵跟随的设置、换位及自然时间亲密度推送。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:04
 */
@Service
final class ElfFollowGameFeature implements GameFeature {
    private final ElfFollowService follow;
    private final BattleGameFeature battle;
    private final Map<GameReply, Session> connections = new WeakHashMap<>();

    /**
     * 连接跟随和亲密度的本地存档。
     *
     * @param follow 跟随槽位及结算
     * @param battle 当前精灵详细属性推送
     */
    ElfFollowGameFeature(ElfFollowService follow, BattleGameFeature battle) {
        this.follow = follow;
        this.battle = battle;
    }

    /**
     * 登录时结清离线收益并发送槽位。
     *
     * @param role 当前角色
     * @param output 连接的消息出口
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        Session session = new Session();
        synchronized (connections) {
            connections.put(output, session);
        }
        Result result = follow.synchronize(role.id);
        session.checkedMinute = Instant.now().getEpochSecond() / 60;
        if (!result.gains.isEmpty()) {
            battle.pushElfs(role.id, output);
        }
        publish(role.id, result, session, output, true, false);
    }

    /**
     * 正常心跳中按至少一分钟一次读取跟随状态和收益。
     *
     * @param role 当前角色
     * @param output 连接消息出口
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Session session = session(output);
        long minute = Instant.now().getEpochSecond() / 60;
        if (session.checkedMinute == minute) {
            return;
        }
        Result result = follow.synchronize(role.id);
        session.checkedMinute = minute;
        publish(role.id, result, session, output, false, true);
    }

    /**
     * 设置、清除或交换两个精灵跟随槽位。
     *
     * @param role 当前角色
     * @param request 原界面的跟随请求
     * @param output 当前连接
     * @return 是否受理该协议
     * @throws IOException 发送协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if ((request.type != 3044 && request.type != 3045) || request.session <= 0) {
            return false;
        }
        Session session = session(output);
        String key = session.id + ":" + request.session;
        Result result;
        if (request.type == 3044) {
            long index = number(request.body, "index", 0);
            String id = (String) request.body.get("uid");
            result = follow.set(role.id, index, id, key);
            output.send(request, data("errno", result.errno, "index", index,
                    "uid", id == null || id.isEmpty() ? null : id));
        } else {
            long first = number(request.body, "idx1", 0);
            long second = number(request.body, "idx2", 0);
            result = follow.swap(role.id, first, second, key);
            output.send(request, data("errno", result.errno, "idx1", first, "idx2", second));
        }
        if (result.errno == 0) {
            publish(role.id, result, session, output, true, true);
        }
        return true;
    }

    /**
     * 发送亲密度变化、当前精灵属性和跟随列表。
     *
     * @param roleId 角色编号
     * @param result 业务提交结果
     * @param session 当前连接的推送记录
     * @param output 当前连接
     * @param force 是否主动刷新全部槽位
     * @param notify 是否发送原版亲密度增加事件
     * @throws IOException 推送失败
     */
    private void publish(String roleId, Result result, Session session, GameReply output,
                         boolean force, boolean notify) throws IOException {
        if (notify && !result.gains.isEmpty()) {
            for (Map.Entry<Integer, List<String>> entry : result.gains.entrySet()) {
                output.push(448, data("list", entry.getValue(), "addNum", entry.getKey()));
            }
            battle.pushElfs(roleId, output);
        }
        if (force || !result.gains.isEmpty() || !result.slots.equals(session.slots)) {
            output.push(447, data("list", result.slots));
            session.slots = result.slots;
        }
    }

    /**
     * 取得当前连接的推送快照与请求前缀。
     *
     * @param output 当前连接
     * @return 此连接持有的跟随状态
     */
    private Session session(GameReply output) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, unused -> new Session());
        }
    }

    /**
     * 当前连接所观察到的跟随状态。
     *
     * @author tongruiyuan
     * @since 2026-11-19 15:04
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private long checkedMinute = Long.MIN_VALUE;
        private List<String> slots = Collections.emptyList();
    }
}
