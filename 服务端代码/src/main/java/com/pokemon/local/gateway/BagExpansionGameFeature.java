package com.pokemon.local.gateway;

import com.pokemon.local.inventory.BagCapacityService;
import com.pokemon.local.inventory.BagCapacityService.Expansion;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 普通道具背包与整只精灵背包的扩容协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:37
 */
@Service
final class BagExpansionGameFeature implements GameFeature {
    private final BagCapacityService capacity;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 连接一致的原页面容量与钻石扣费。
     *
     * @param capacity 扩容业务
     * @param bags 普通道具背包推送
     * @param battle 精灵背包推送
     */
    BagExpansionGameFeature(BagCapacityService capacity, BagSnapshot bags, BattleGameFeature battle) {
        this.capacity = capacity;
        this.bags = bags;
        this.battle = battle;
    }

    /**
     * 保存进入游戏时原客户端收到的两类容量。
     *
     * @param role 当前角色
     * @param output 当前协议连接
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) {
        synchronized (sessions) {
            sessions.put(output, new Session(capacity.ordinary(role.id), capacity.elves(role.id)));
        }
    }

    /**
     * 校验背包类型及原页面容量，购买后同步真实钱包。
     *
     * @param role 当前角色
     * @param request C310 或 C1701
     * @param output 当前协议连接
     * @return 是否处理了该协议
     * @throws IOException 写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean elf = request.type == 310;
        if ((!elf && request.type != 1701) || request.session <= 0) {
            return false;
        }
        long type = number(request.body, "ntype", 0);
        if (!elf && type != 1) {
            output.send(request, data("errno", 100021, "ntype", type));
            return true;
        }
        Session session;
        synchronized (sessions) {
            session = sessions.computeIfAbsent(output,
                    unused -> new Session(capacity.ordinary(role.id), capacity.elves(role.id)));
        }
        Expansion result = capacity.buy(role.id, elf, elf ? session.elves : session.ordinary,
                session.id + ":" + request.session);
        output.send(request, elf ? data("errno", result.errno)
                : data("errno", result.errno, "ntype", type, "capacity", result.capacity));
        boolean resync = elf ? result.capacity != session.elves : result.capacity != session.ordinary;
        if (elf) {
            session.elves = result.capacity;
        } else {
            session.ordinary = result.capacity;
        }
        if (result.errno == 0) {
            output.push(3, data("diamonds", result.diamonds));
        }
        if (resync) {
            if (elf) {
                battle.pushElfs(role.id, output);
            } else {
                bags.push(role.id, output);
            }
        }
        return true;
    }

    /**
     * 当前连接的容量及请求去重前缀。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:37
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private int ordinary;
        private int elves;

        /**
         * 初始化原页面所看到的容量。
         *
         * @param ordinary 普通背包容量
         * @param elves 精灵背包容量
         */
        private Session(int ordinary, int elves) {
            this.ordinary = ordinary;
            this.elves = elves;
        }
    }
}

