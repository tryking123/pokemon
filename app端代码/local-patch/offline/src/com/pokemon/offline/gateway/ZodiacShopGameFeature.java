package com.pokemon.offline.gateway;

import com.pokemon.offline.arena.ZodiacShopService;
import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 十二宫积分商店的原客户端货架、购买及付费刷新入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 17:44
 */
@Service
final class ZodiacShopGameFeature implements GameFeature {
    private final ZodiacShopService shop;
    private final ElfGearService gears;
    private final BagSnapshot bags;
    private final RoleService roles;
    private final Map<GameReply, View> views = Collections.synchronizedMap(new WeakHashMap<>());

    /** 单个连接最后收到的货架及该业务日已成功的请求。 */
    private static final class View {
        private long version;
        private long next;
        private long day;
        private final Set<Long> completed = new HashSet<>();
    }

    /**
     * 绑定货架事务与资产推送。
     *
     * @param shop 积分商城服务
     * @param gears 力石实例背包
     * @param bags 道具背包快照
     * @param roles 角色钻石钱包
     */
    ZodiacShopGameFeature(ZodiacShopService shop, ElfGearService gears, BagSnapshot bags, RoleService roles) {
        this.shop = shop;
        this.gears = gears;
        this.bags = bags;
        this.roles = roles;
    }

    /**
     * 建立本连接的首个货架视图。
     *
     * @param role 当前角色
     * @param output 原协议通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        View view = new View();
        views.put(output, view);
        observe(view, shop.board(role.id));
    }

    /**
     * 跨过四小时边界后推送新货架。
     *
     * @param role 当前角色
     * @param output 原协议通道
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        View view = views.get(output);
        if (view != null && Instant.now().getEpochSecond() >= view.next) {
            push(role.id, view, output);
        }
    }

    /**
     * 更新客户端与当前连接已知的商品位置。
     *
     * @param roleId 当前角色编号
     * @param view 客户端视图
     * @param output 原协议通道
     * @throws IOException 推送失败
     */
    private void push(String roleId, View view, GameReply output) throws IOException {
        Map<String, Object> board = shop.board(roleId);
        observe(view, board);
        board.remove("version");
        output.push(810, board);
    }

    /**
     * 记录角色连接对应的货架版本及刷新时刻。
     *
     * @param view 原连接货架游标
     * @param board 当前存档货架
     */
    private void observe(View view, Map<String, Object> board) {
        view.version = ((Number) board.get("version")).longValue();
        view.next = ((Number) board.get("autotime")).longValue();
        long today = (Instant.now().getEpochSecond() + 28800) / 86400;
        if (view.day != today) {
            view.completed.clear();
            view.day = today;
        }
    }

    /**
     * 处理当前批次的手动刷新或购买，重复请求仅可成交一次。
     *
     * @param role 当前角色
     * @param request 原商城请求
     * @param output 应答与资产推送通道
     * @return 是否属于本商城的请求
     * @throws IOException 协议写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 275 && request.type != 276) {
            return false;
        }
        View view = views.get(output);
        if (request.session <= 0 || view == null || view.completed.contains(request.session)) {
            if (request.session > 0) {
                output.send(request, data("errno", 100021));
            }
            return true;
        }
        if (request.type == 275) {
            Map<String, Object> changed = shop.refresh(role.id, view.version);
            if (!changed.isEmpty()) {
                view.completed.add(request.session);
                output.send(request, data("errno", 0, "shop", changed.get("shop"), "manaual", changed.get("manaual")));
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
                push(role.id, view, output);
                return true;
            }
        } else {
            long index = number(request.body, "idx", 0);
            int kind = shop.buy(role.id, view.version, index);
            if (kind > 0) {
                view.completed.add(request.session);
                output.send(request, data("errno", 0, "idx", index));
                if (kind == ElfGearService.POWERSTONE) {
                    BattleGameFeature.pushStoneBag(gears.snapshot(role.id), null, output);
                } else {
                    bags.push(role.id, output);
                }
                push(role.id, view, output);
                return true;
            }
        }
        output.send(request, data("errno", 100021));
        push(role.id, view, output);
        return true;
    }
}
