package com.pokemon.local.gateway;

import com.pokemon.local.activity.DiamondSaleService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 超级钻石抢购的原预览、当前热点、历史积分商城及购买入口。
 * @author tongruiyuan
 * @since 2026-11-19 19:21
 */
@Service
final class DiamondSaleGameFeature implements GameFeature {
    private final DiamondSaleService sale;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final InventoryGameFeature mail;
    private final BagSnapshot bags;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 接入原 S10007 开奖通知与常规资产推送。
     * @param sale 抢购服务
     * @param roles 角色余额
     * @param battle 精灵
     * @param mail 邮件
     * @param bags 背包
     */
    DiamondSaleGameFeature(DiamondSaleService sale, RoleService roles, BattleGameFeature battle,
                           InventoryGameFeature mail, BagSnapshot bags) {
        this.sale = sale;
        this.roles = roles;
        this.battle = battle;
        this.mail = mail;
        this.bags = bags;
    }

    /**
     * 接收当前屏幕上的动作，保存实际看到的轮次与场次。
     * @param role 当前角色
     * @param request 原请求
     * @param output 协议通道
     * @return 本类是否处理该请求
     * @throws IOException 网关写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int protocol = request.type;
        if (protocol < 10050 || protocol > 10054 || request.session <= 0) {
            return false;
        }
        Session session;
        synchronized (sessions) {
            session = sessions.computeIfAbsent(output, unused -> new Session());
        }
        long value = number(request.body, protocol == 10053 ? "index" : "intgeral", 0);
        DiamondSaleService.Result result = sale.process(role.id, protocol, value,
                protocol == 10053 ? session.period : session.lot, session.key + ":" + request.session);
        if (Integer.valueOf(0).equals(result.response.get("errno"))) {
            if (protocol == 10052) {
                session.period = result.period;
            }
            if (protocol == 10051) {
                session.lot = result.version;
            }
            if (result.diamonds > 0) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            if (result.elf != null) {
                battle.pushElfs(role.id, output);
            }
            if (!result.items.isEmpty()) {
                bags.push(role.id, output);
            }
            if (result.mailed) {
                mail.pushMailCount(role, output);
            }
            if (protocol == 10053 || protocol == 10054) {
                List<Map<String, Object>> items = new ArrayList<>();
                result.items.forEach((id, quantity) -> items.add(data("id", id, "n", quantity)));
                Map<String, Object> adds = data("items", items);
                if (result.elf != null) {
                    adds.put("elf", Collections.singletonList(BattleGameFeature.elfInfo(result.elf)));
                }
                output.push(14, data("flags", "activity.snapup", "adds", adds,
                        "loses", data("diamonds", result.diamonds)));
            }
        }
        output.send(request, result.response);
        if (result.won) {
            output.push(10007, data());
        }
        return true;
    }

    /**
     * 当前面板已展示的场次和商城期次。
     * @author tongruiyuan
     * @since 2026-11-19 19:21
     */
    private static final class Session {
        final String key = UUID.randomUUID().toString();
        long lot = Long.MIN_VALUE;
        long period = Long.MIN_VALUE;
    }
}
