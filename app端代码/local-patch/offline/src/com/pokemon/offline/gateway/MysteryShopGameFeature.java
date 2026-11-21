package com.pokemon.offline.gateway;

import com.pokemon.offline.inventory.MysteryShopService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原神秘商店的上架、购买与刷新协议。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:55
 */
@Service
final class MysteryShopGameFeature implements GameFeature {
    private final MysteryShopService shop;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /**
     * 绑定神秘商店结算、钱包与背包推送。
     *
     * @param shop 神秘商店服务
     * @param roles 最新角色钱包
     * @param bags 背包快照
     */
    MysteryShopGameFeature(MysteryShopService shop, RoleService roles, BagSnapshot bags) {
        this.shop = shop;
        this.roles = roles;
        this.bags = bags;
    }

    /**
     * 处理客户端购买或者刷新神秘商店。
     *
     * @param role 已认证角色
     * @param request 原客户端请求
     * @param output 协议输出通道
     * @return 是否处理了请求
     * @throws IOException 无法发送应答
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 2302 && request.type != 2303) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        boolean refresh = request.type == 2303;
        String requestId = connectionId(output) + ":" + request.session;
        MysteryShopService.Result result = refresh
                ? shop.refresh(role.id, requestId)
                : shop.buy(role.id, requestId, number(request.body, "index", 0));
        Map<String, Object> response = refresh && result.errno == 0 ? fields(result.board) : data();
        response.put("errno", result.errno);
        output.send(request, response);
        if (result.changed) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
            bags.push(role.id, output);
            if (!refresh) {
                output.push(701, fields(result.board));
            }
        }
        return true;
    }

    /**
     * 恢复进入场景时的真实神秘商店商品。
     *
     * @param role 已认证角色
     * @param output 协议输出通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(701, fields(shop.snapshot(role.id)));
    }

    /**
     * 装配购买或刷新后使用的原协议字段。
     *
     * @param board 已保存的商店状态
     * @return 可用于 S701 或 C2303 的字段
     */
    private Map<String, Object> fields(MysteryShopService.Board board) {
        return data("refresh_time", board.refreshAt, "item_num", board.itemRefreshes,
                "diamond_num", board.diamondRefreshes, "shop_info", board.offers.stream().map(offer ->
                        data("prop", offer.item, "type", offer.type, "species", offer.currency, "number", offer.price,
                                "killmoney", offer.price, "num", offer.quantity, "buy_flag", offer.bought ? 1 : 0))
                        .collect(Collectors.toList()));
    }

    /**
     * 为当前连接分配请求回执前缀。
     *
     * @param output 当前连接的输出通道
     * @return 该连接稳定的回执前缀
     */
    private String connectionId(GameReply output) {
        synchronized (connections) {
            return connections.computeIfAbsent(output, unused -> UUID.randomUUID().toString());
        }
    }
}
