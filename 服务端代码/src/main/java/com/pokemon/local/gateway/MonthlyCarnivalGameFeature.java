package com.pokemon.local.gateway;

import com.pokemon.local.activity.MonthlyCarnivalService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 月度狂欢原任务与领奖页面。
 *
 * @author tongruiyuan
 * @since 2026-11-20 12:49
 */
@Service
final class MonthlyCarnivalGameFeature implements GameFeature {
    private final MonthlyCarnivalService service;
    private final BagSnapshot bags;
    private final Map<GameReply, Long> previews = Collections.synchronizedMap(new WeakHashMap<>());

    /** 关联活动服务及角色物品快照。
     * @param service 活动查询与发奖
     * @param bags 背包及邮件同步
     */
    MonthlyCarnivalGameFeature(MonthlyCarnivalService service, BagSnapshot bags) {
        this.service = service;
        this.bags = bags;
    }

    /** 响应原页面的轮次查询、任务摘要或签到领奖。
     * @param role 当前登录角色
     * @param request 原版请求
     * @param output 当前游戏连接
     * @return 是否处理此请求
     * @throws IOException 写入协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 320 || request.type > 322) return false;
        if (request.session <= 0) return true;
        if (request.type == 321) {
            Long shown = previews.get(output);
            Map<String, Object> result = shown == null ? null : service.claim(role.id, shown,
                    SprotoProtocol.number(request.body, "idx", -1));
            output.send(request, data("errno", result == null ? 1 : 0));
            if (result != null) {
                output.push(14, data("flags", "monthly.carnival", "adds", result));
                bags.push(role.id, output);
                bags.pushMailCount(role.id, output);
            }
        } else {
            ActivityPeriod period = service.period();
            previews.put(output, period.start);
            output.send(request, service.panel(role.id, period, request.type == 320));
        }
        return true;
    }
}
