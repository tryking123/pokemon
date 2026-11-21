package com.pokemon.local.gateway;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.LimitedBeastService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 限时神兽原协议面板、抽取、领奖与本地排名入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 18:23
 */
@Service
final class LimitedBeastGameFeature implements GameFeature {
    private final ActivityService calendar;
    private final LimitedBeastService beasts;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final InventoryGameFeature mail;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 绑定活动状态、真实角色余额、背包和邮件同步。
     *
     * @param calendar 活动时间窗
     * @param beasts 神兽抽取与活动榜
     * @param roles 角色余额
     * @param bags 背包快照
     * @param mail 邮件数量推送
     */
    LimitedBeastGameFeature(ActivityService calendar, LimitedBeastService beasts, RoleService roles,
                            BagSnapshot bags, InventoryGameFeature mail) {
        this.calendar = calendar;
        this.beasts = beasts;
        this.roles = roles;
        this.bags = bags;
        this.mail = mail;
    }

    /**
     * 响应原活动的四条查询及结算消息。
     *
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output Sproto 应答与资产推送
     * @return 是否命中限时神兽入口
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 187 || request.type > 190 || request.session <= 0) {
            return false;
        }
        Session state = session(output);
        ActivityPeriod period = calendar.cycle(LimitedBeastService.ACTIVITY);
        if (request.type == 187) {
            state.viewed = period.start;
            output.send(request, beasts.status(role.id, period));
            return true;
        }
        if (request.type == 190) {
            output.send(request, beasts.rank(role, period));
            return true;
        }
        boolean draw = request.type == 188;
        LimitedBeastService.Result result = draw ? beasts.draw(role.id, state.viewed,
                Boolean.TRUE.equals(request.body.get("isten")), state.id + ":" + request.session)
                : beasts.claim(role.id, state.viewed, number(request.body, "idx", 0));
        output.send(request, result.response);
        if (Integer.valueOf(0).equals(result.response.get("errno"))) {
            if (draw) {
                LocalRole updated = roles.findById(role.id).orElse(role);
                output.push(3, data("diamonds", updated.diamonds));
            }
            bags.push(role.id, output);
            output.push(14, data("flags", "activity.limitelf", "adds",
                    data("items", items(result.items), "elschips", items(result.chips))));
            if (result.mailed) {
                mail.pushMailCount(role, output);
            }
        }
        return true;
    }

    /**
     * 进服后立即补发离线期间到期的榜单邮件。
     *
     * @param role 当前角色
     * @param output 当前登录连接
     * @throws IOException 邮件推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        tick(role, output);
    }

    /**
     * 在参与期到期或展示期到期时核对历史榜单结算。
     *
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 邮件推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Session state = session(output);
        long now = Instant.now().getEpochSecond();
        if (now >= state.nextSettlement) {
            ActivityPeriod period = calendar.cycle(LimitedBeastService.ACTIVITY);
            if (beasts.settle(role.id) > 0) {
                mail.pushMailCount(role, output);
            }
            state.nextSettlement = now < period.end ? period.end : period.showUntil;
        }
    }

    /**
     * 读取当前连接查看过的周期及下一次轮末检查时间。
     *
     * @param output 当前网关连接
     * @return 连接状态
     */
    private synchronized Session session(GameReply output) {
        return sessions.computeIfAbsent(output, ignored -> new Session());
    }

    /**
     * 把实际到账的道具与碎片转为原统一奖励推送的物品格式。
     *
     * @param amounts 资产模板与实际发放数量
     * @return 原物品数组
     */
    private List<Map<String, Object>> items(Map<Integer, Long> amounts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        amounts.forEach((id, amount) -> rows.add(data("id", id, "n", amount)));
        return rows;
    }

    /**
     * 当前客户端查看的活动轮次、请求防重前缀和结算时钟。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:23
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private long viewed = Long.MIN_VALUE;
        private long nextSettlement;
    }
}
