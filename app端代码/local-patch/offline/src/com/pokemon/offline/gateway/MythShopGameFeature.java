package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.MythShopService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 神兽商店的货架、刷新与整只精灵兑换入口。
 * @author tongruiyuan
 * @since 2026-11-19 19:08
 */
@Service
final class MythShopGameFeature implements GameFeature {
    private final MythShopService shop;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final InventoryGameFeature mail;
    private final BagSnapshot bags;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 绑定商店与角色、精灵、邮件和背包推送。
     * @param shop 货架结算
     * @param roles 角色余额
     * @param battle 精灵推送
     * @param mail 邮件提示
     * @param bags 背包推送
     */
    MythShopGameFeature(MythShopService shop, RoleService roles, BattleGameFeature battle,
                        InventoryGameFeature mail, BagSnapshot bags) {
        this.shop = shop;
        this.roles = roles;
        this.battle = battle;
        this.mail = mail;
        this.bags = bags;
    }

    /**
     * 从当前连接最近看的货架操作并同步实际扣费或到账。
     * @param role 当前角色
     * @param request 原请求
     * @param output 应答通道
     * @return 是否处理该消息
     * @throws IOException 写入协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 246 || request.type > 248 || request.session <= 0) {
            return false;
        }
        Session session;
        synchronized (sessions) {
            session = sessions.computeIfAbsent(output, unused -> new Session());
        }
        long type = number(request.body, "shoptype", 0);
        MythShopService.Result done = shop.process(role.id, request.type, type, number(request.body, "index", 0),
                session.versions.get((int) type), session.key + ":" + request.session);
        session.versions.putAll(done.versions);
        if (done.diamonds > 0) {
            output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
        }
        if (done.ticket > 0) {
            bags.push(role.id, output);
            Map<String, Object> adds = data();
            if (done.elf != null) {
                battle.pushElfs(role.id, output);
                adds.put("elf", Collections.singletonList(BattleGameFeature.elfInfo(done.elf)));
            }
            if (done.mailed) {
                mail.pushMailCount(role, output);
            }
            output.push(14, data("flags", "activity.mythshop", "adds", adds, "loses",
                    data("items", Collections.singletonList(data("id", done.ticket, "n", 1)))));
        }
        output.send(request, done.response);
        return true;
    }

    /**
     * 每个游戏连接独立防重并记录各栏展示版本。
     * @author tongruiyuan
     * @since 2026-11-19 19:08
     */
    private static final class Session {
        final String key = UUID.randomUUID().toString();
        final Map<Integer, String> versions = new HashMap<>();
    }
}
