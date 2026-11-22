package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.MasterTurntableService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 跨服大转盘的原入口、抽取动画、榜奖邮件及资产推送。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:40
 */
@Service
final class MasterTurntableGameFeature implements GameFeature {
    private final MasterTurntableService wheel;
    private final ActivityService calendar;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final InventoryGameFeature mailbox;
    private final Map<GameReply, View> views = new WeakHashMap<>();

    /** 绑定转盘、日历与原资产推送。
     * @param wheel 转盘服务
     * @param calendar 活动周
     * @param roles 角色余额
     * @param bags 背包推送
     * @param mailbox 邮件角标
     */
    MasterTurntableGameFeature(MasterTurntableService wheel, ActivityService calendar, RoleService roles,
                               BagSnapshot bags, InventoryGameFeature mailbox) {
        this.wheel = wheel;
        this.calendar = calendar;
        this.roles = roles;
        this.bags = bags;
        this.mailbox = mailbox;
    }

    /** 在同一原连接内维护已展示的奖池、费用和货架周。
     * @param role 当前角色
     * @param request 原协议
     * @param output 网关回包
     * @return 是否处理本协议
     * @throws IOException 连接不可写
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 10021 || request.type > 10025 || request.session <= 0) { return false; }
        View view = view(output);
        MasterTurntableService.Result result = wheel.process(role.id, request.type,
                number(request.body, request.type == 10025 ? "buy_index" : "itype", 0),
                request.type == 10025 ? view.shopPeriod : view.drawPeriod, view.remaining,
                view.connection + ":" + request.session);
        if (Integer.valueOf(0).equals(result.body.get("errno"))) {
            if (request.type == 10022 || request.type == 10024) {
                view.drawPeriod = result.period;
                view.remaining = result.remaining;
            } else if (request.type == 10023) { view.shopPeriod = result.period; }
        }
        if (result.changed) {
            LocalRole updated = roles.findById(role.id).orElse(role);
            output.push(3, data("diamonds", updated.diamonds, "coins", updated.coins));
            bags.push(role.id, output);
            List<Map<String, Object>> items = new ArrayList<>();
            result.granted.forEach((id, n) -> items.add(data("id", id, "n", n)));
            output.push(14, data("flags", request.type == 10024 ? "do_master_turn_table_turn" : "master_turn_rable_shop",
                    "adds", data("items", items), "loses", data("diamonds", result.cost)));
            if (result.mailed) { mailbox.pushMailCount(role, output); }
        }
        output.send(request, result.body);
        return true;
    }

    /** 首次进服时结清离线期间的周榜。
     * @param role 当前角色
     * @param output 网关推送
     * @throws IOException 连接不可写
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException { tick(role, output); }

    /** 跨周时发放实际榜奖，活跃连接无需重新登录。
     * @param role 当前角色
     * @param output 网关推送
     * @throws IOException 连接不可写
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        View view = view(output);
        if (System.currentTimeMillis() / 1000 >= view.nextSettlement) {
            long end = calendar.cycle(MasterTurntableService.ACTIVITY).end;
            if (wheel.settle(role.id) > 0) { mailbox.pushMailCount(role, output); }
            view.nextSettlement = end;
        }
    }

    /** 获取当前连接的页面版本。
     * @param output 连接
     * @return 页面版本和结算时钟
     */
    private synchronized View view(GameReply output) { return views.computeIfAbsent(output, ignored -> new View()); }

    /** 当前连接已经看到的活动数据。
     * @author tongruiyuan
     * @since 2026-11-20 10:40
     */
    private static final class View {
        final String connection = UUID.randomUUID().toString();
        long drawPeriod = -1, shopPeriod = -1, nextSettlement;
        int remaining = -1;
    }
}
