package com.pokemon.offline.gateway;

import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.elf.PowerStoneShopService;
import com.pokemon.offline.elf.PowerStoneShopService.Board;
import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原 P 力石商城的刷新、购买及货架推送。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:30
 */
@Service
final class PowerStoneShopGameFeature implements GameFeature {
    private final PowerStoneShopService shop;
    private final ElfGearService gears;
    private final RoleService roles;
    private final Map<GameReply, View> views = Collections.synchronizedMap(new WeakHashMap<>());

    /** 保存单个连接已看到的批次及当天已经提交的交易请求号。 */
    private static final class View {
        Board board;
        final Set<Long> completed = new HashSet<>();
        long day;
    }

    /**
     * 创建商城协议入口。
     *
     * @param shop 货架与购买服务
     * @param gears 力石碎屑余额
     * @param roles 角色钻石余额
     */
    PowerStoneShopGameFeature(PowerStoneShopService shop, ElfGearService gears, RoleService roles) {
        this.shop = shop;
        this.gears = gears;
        this.roles = roles;
    }

    /**
     * 进入游戏时发送当前货架与下一次自动刷新时刻。
     *
     * @param role 登录角色
     * @param output 推送连接
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        View view = new View();
        views.put(output, view);
        push(view, shop.board(role.id), output);
    }

    /**
     * 在自动刷新时同步客户端各位置，碎屑只由购买或分解响应变更。
     *
     * @param role 登录角色
     * @param output 推送连接
     * @throws IOException 发送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        View view = views.get(output);
        if (view != null && Instant.now().getEpochSecond() >= view.board.next) {
            push(view, shop.board(role.id), output);
        }
    }

    /**
     * 处理原商城按钮，过期批次先同步，重复交易请求不会二次扣款。
     *
     * @param role 登录角色
     * @param request 客户端请求
     * @param output 协议连接
     * @return 是否属于商城
     * @throws IOException 发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 257 && request.type != 258) {
            return false;
        }
        View view = views.get(output);
        if (view == null || request.session <= 0 || view.completed.contains((long) request.session)) {
            if (request.session > 0) {
                output.send(request, data("errno", 100021));
            }
            return true;
        }
        if (request.type == 257) {
            Board refreshed = shop.refresh(role.id, view.board.version);
            if (refreshed != null) {
                view.completed.add((long) request.session);
                output.send(request, data("errno", 0, "shop", cargo(refreshed), "manaual", refreshed.manual));
                view.board = refreshed;
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
                return true;
            }
        } else {
            long index = number(request.body, "idx", 0);
            List<ElfGear> received = shop.buy(role.id, view.board.version, index);
            if (!received.isEmpty()) {
                view.completed.add((long) request.session);
                output.send(request, data("errno", 0, "idx", index, "debris", gears.debris(role.id)));
                output.push(14, data("flags", "pstone.buy", "adds", data("pstone",
                        received.stream().map(BattleGameFeature::powerStone).collect(Collectors.toList()))));
                BattleGameFeature.pushStoneBag(gears.snapshot(role.id), null, output);
                return true;
            }
        }
        output.send(request, data("errno", 100021));
        push(view, shop.board(role.id), output);
        return true;
    }

    /**
     * 更新客户端已见批次，S808 省略碎屑以保留角色余额。
     *
     * @param view 连接状态
     * @param board 实际货架
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    private void push(View view, Board board, GameReply output) throws IOException {
        long day = (Instant.now().getEpochSecond() + 28800) / 86400;
        if (view.day != day) {
            view.completed.clear();
            view.day = day;
        }
        view.board = board;
        output.push(808, data("shop", cargo(board), "manaual", board.manual, "autotime", board.next));
    }

    /**
     * 按原 cargo 结构显示每格余量。
     *
     * @param board 当前存档
     * @return 有序货架
     */
    private List<Map<String, Object>> cargo(Board board) {
        List<Map<String, Object>> goods = new ArrayList<>();
        for (int i = 0; i < board.ids.size(); i++) {
            goods.add(data("id", board.ids.get(i), "times", (board.sold & (1 << i)) == 0 ? 1 : 0));
        }
        return goods;
    }
}
