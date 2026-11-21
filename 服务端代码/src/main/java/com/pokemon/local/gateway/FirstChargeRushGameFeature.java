package com.pokemon.local.gateway;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.FirstChargeRushService;
import com.pokemon.local.activity.FirstChargeRushService.Result;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import static com.pokemon.local.activity.FirstChargeRushService.ACTIVITY;
import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 首冲来袭原页面查询和顺序领奖协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:22
 */
@Service
final class FirstChargeRushGameFeature implements GameFeature {
    private final ActivityService calendar;
    private final FirstChargeRushService rush;
    private final BagSnapshot bags;
    private final InventoryGameFeature mail;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 绑定本期活动、奖励背包及溢出邮件推送。
     *
     * @param calendar 活动日期
     * @param rush 首冲来袭本地服务
     * @param bags 背包同步
     * @param mail 邮件数量同步
     */
    FirstChargeRushGameFeature(ActivityService calendar, FirstChargeRushService rush, BagSnapshot bags,
                               InventoryGameFeature mail) {
        this.calendar = calendar;
        this.rush = rush;
        this.bags = bags;
        this.mail = mail;
    }

    /**
     * 返回原活动页面的数据，或领取已展示的当前档位。
     *
     * @param role 当前角色
     * @param request 原协议请求
     * @param output 当前连接
     * @return 是否处理了请求
     * @throws IOException 无法发送协议
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if ((request.type != 287 && request.type != 288) || request.session <= 0) {
            return false;
        }
        Session session;
        synchronized (sessions) {
            session = sessions.computeIfAbsent(output, unused -> new Session());
        }
        if (request.type == 287) {
            session.period = calendar.cycle(ACTIVITY);
            Map<String, Object> view = rush.status(role.id, session.period);
            session.index = (int) view.get("index");
            output.send(request, view);
            return true;
        }
        Result result = rush.claim(role.id, session.period, session.index, session.id + ":" + request.session);
        output.send(request, data("errno", result.errno, "flag", result.flag));
        if (result.errno != 0) {
            return true;
        }
        session.index++;
        if (!result.delivered.isEmpty()) {
            List<Map<String, Object>> items = result.delivered.entrySet().stream()
                    .map(item -> data("id", item.getKey(), "n", item.getValue())).collect(Collectors.toList());
            bags.push(role.id, output);
            output.push(14, data("flags", "activity.lifeamass", "adds", data("items", items)));
        }
        if (result.mailed) {
            mail.pushMailCount(role, output);
        }
        return true;
    }

    /**
     * 保存客户端当前展示的领取顺序和防重前缀。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:22
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private ActivityPeriod period;
        private int index;
    }
}

