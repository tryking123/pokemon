package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.PikaParkService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 皮卡丘乐园原界面、闯关动画、购券和累计奖领取入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 18:38
 */
@Service
final class PikaParkGameFeature implements GameFeature {
    private final PikaParkService park;
    private final ActivityService calendar;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final InventoryGameFeature mail;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 绑定乐园业务与原角色、精灵、背包和邮件同步。
     *
     * @param park 乐园结算
     * @param calendar 活动日期
     * @param roles 真实余额
     * @param bags 道具快照
     * @param battle 精灵快照
     * @param mail 溢出邮件通知
     */
    PikaParkGameFeature(PikaParkService park, ActivityService calendar, RoleService roles,
                        BagSnapshot bags, BattleGameFeature battle, InventoryGameFeature mail) {
        this.park = park;
        this.calendar = calendar;
        this.roles = roles;
        this.bags = bags;
        this.battle = battle;
        this.mail = mail;
    }

    /**
     * 读取乐园当前层，或在客户端已展示的周期中购券、抽取和领奖。
     *
     * @param role 当前角色
     * @param request 原乐园请求
     * @param output 回复与推送通道
     * @return 是否是乐园协议
     * @throws IOException 网关写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 215 || request.type > 218 || request.session <= 0) {
            return false;
        }
        Session state;
        synchronized (sessions) {
            state = sessions.computeIfAbsent(output, ignored -> new Session());
        }
        if (request.type == 215) {
            ActivityPeriod cycle = calendar.cycle(PikaParkService.ACTIVITY);
            state.viewed = cycle.start;
            PikaParkService.Result status = park.status(role.id, cycle);
            state.layer = ((Number) status.response.getOrDefault("layer", 0)).intValue();
            output.send(request, status.response);
            return true;
        }
        String field = request.type == 218 ? "num" : request.type == 217 ? "idx" : "option";
        PikaParkService.Result done = park.operate(role.id, state.viewed, state.layer, request.type,
                number(request.body, field, 0),
                Boolean.TRUE.equals(request.body.get("auto")), state.id + ":" + request.session);
        if (Integer.valueOf(0).equals(done.response.get("errno"))) {
            if (request.type == 216) {
                state.layer = ((Number) done.response.get("layer")).intValue();
            }
            if (done.spentDiamonds > 0) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            bags.push(role.id, output);
            if (!done.elves.isEmpty()) {
                battle.pushElfs(role.id, output);
            }
            if (done.mailed) {
                mail.pushMailCount(role, output);
            }
            List<Map<String, Object>> items = new ArrayList<>();
            done.items.forEach((id, quantity) -> items.add(data("id", id, "n", quantity)));
            Map<String, Object> losses = data("diamonds", done.spentDiamonds);
            if (done.spentTickets > 0) {
                losses.put("items", Collections.singletonList(data("id", PikaParkService.TICKET, "n", done.spentTickets)));
            }
            output.push(14, data("flags", request.type == 216 ? "pika.pika_do" : "activity.pika",
                    "adds", data("items", items, "elf", done.elves.stream().map(BattleGameFeature::elfInfo)
                            .collect(Collectors.toList())), "loses", losses));
        }
        output.send(request, done.response);
        return true;
    }

    /**
     * 乐园面板轮次和当前连接防重键。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:38
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private long viewed = Long.MIN_VALUE;
        private int layer;
    }
}
