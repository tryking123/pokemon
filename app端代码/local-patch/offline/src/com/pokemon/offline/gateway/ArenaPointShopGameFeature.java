package com.pokemon.offline.gateway;

import com.pokemon.offline.inventory.ArenaPointShopService;
import com.pokemon.offline.inventory.ArenaPointShopService.Purchase;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原竞技积分商店的回环兑换与钱包推送。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:16
 */
@Service
final class ArenaPointShopGameFeature implements GameFeature {
    private final ArenaPointShopService shop;
    private final BagSnapshot bags;
    private final Map<GameReply, Session> connections = new WeakHashMap<>();

    /**
     * 绑定真实商城结算及背包同步。
     *
     * @param shop 竞技积分商城
     * @param bags 普通和碎片背包推送
     */
    ArenaPointShopGameFeature(ArenaPointShopService shop, BagSnapshot bags) {
        this.shop = shop;
        this.bags = bags;
    }

    /**
     * 进入场景时加载原版货架的限购快照及角色余额。
     *
     * @param role 当前角色
     * @param output 连接消息出口
     * @throws IOException 写入连接失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        Session state = new Session();
        synchronized (connections) {
            connections.put(output, state);
        }
        output.push(705, data("refresh_time", 0, "buy_info", Collections.emptyList()));
        output.push(703, data("refresh_time", 0, "buy_info", Collections.emptyList()));
        pushBalance(state, shop.balance(role.id), output);
    }

    /**
     * 发现竞技结算增加的真实可用积分后同步钱包。
     *
     * @param role 当前角色
     * @param output 连接消息出口
     * @throws IOException 写入失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        pushBalance(session(output), shop.balance(role.id), output);
    }

    /**
     * 按原购买请求结算竞技积分和真实库存。
     *
     * @param role 当前角色
     * @param request 竞技积分兑换请求
     * @param output 应答和推送出口
     * @return 此功能是否已处理请求
     * @throws IOException 写入连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean dojo = request.type == 2306;
        if ((!dojo && request.type != 2304) || request.session <= 0) {
            return false;
        }
        Session state = session(output);
        Purchase result = shop.buy(role.id, number(request.body, "index", 0), number(request.body, "num", 0),
                state.id + ":" + request.session, dojo);
        if (result.errno == 0) {
            output.send(request, data("errno", 0, "itemid", result.item, "itemtype", result.type,
                    "itemnum", result.quantity));
            bags.push(role.id, output);
            if (dojo) {
                output.push(3, data("dojo_point", result.balance));
            } else {
                pushBalance(state, result.balance, output);
            }
        } else {
            output.send(request, data("errno", result.errno));
        }
        return true;
    }

    /**
     * 当前连接余额发生变化时发送 S3 增量。
     *
     * @param state 连接的缓存
     * @param balance 实际可用积分
     * @param output 当前连接
     * @throws IOException 发送失败
     */
    private void pushBalance(Session state, long balance, GameReply output) throws IOException {
        if (state.balance != balance) {
            state.balance = balance;
            output.push(3, data("pkpoint", balance));
        }
    }

    /**
     * 读取当前连接的购买键前缀及余额快照。
     *
     * @param output 当前连接
     * @return 本功能连接状态
     */
    private Session session(GameReply output) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, unused -> new Session());
        }
    }

    /**
     * 保存连接的购买防重前缀和已同步积分。
     *
     * @author tongruiyuan
     * @since 2026-11-19 15:16
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private long balance = Long.MIN_VALUE;
    }
}
