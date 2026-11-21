package com.pokemon.local.gateway;

import com.pokemon.local.activity.NewYearFundService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 新春基金原协议的状态展示、激活和一键领取。
 * @author tongruiyuan
 * @since 2026-11-20 10:18
 */
@Service
final class NewYearFundGameFeature implements GameFeature {
    private final NewYearFundService fund;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final InventoryService inventory;
    private final Map<GameReply,Long> periods = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> refresh = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 连接基金进度与角色资产视图。
     * @param fund 基金业务
     * @param roles 角色钱包
     * @param bags 背包快照
     * @param inventory 家园币与已发资产
     */
    NewYearFundGameFeature(NewYearFundService fund, RoleService roles, BagSnapshot bags, InventoryService inventory) {
        this.fund = fund;
        this.roles = roles;
        this.bags = bags;
        this.inventory = inventory;
    }

    /**
     * 提交激活或领取请求，成功时刷新所有变动资产及领取记录。
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 原协议推送通道
     * @return 是否已处理
     * @throws IOException 应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 390 && request.type != 391) return false;
        if (request.session > 0) {
            long preview = periods.getOrDefault(output, -1L);
            long index = number(request.body, "idx", 0);
            boolean unlock = request.type == 390;
            boolean granted = fund.claim(role.id, preview, unlock, index);
            if (granted) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds,
                        "homecoin", inventory.homeCoins(role.id)));
                bags.push(role.id, output);
                bags.pushMailCount(role.id, output);
            }
            output.send(request, unlock ? data("errno", granted ? 0 : 1)
                    : data("errno", granted ? 0 : 1, "idx", index, "auto", Boolean.TRUE.equals(request.body.get("auto"))));
            synchronize(roles.findById(role.id).orElse(role), output);
        }
        return true;
    }

    /**
     * 推送当周真实活跃、激活状态和当前可见分支的领取记录。
     * @param role 当前角色
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        long start = role.level >= 30 ? fund.weekStart() : -1L;
        if (start < 0) {
            output.push(461, data("season", 0));
        } else {
            NewYearFundService.State state = fund.state(role.id, start);
            List<Map<String,Object>> record = new ArrayList<>();
            for (int i = 1; i <= 16; i++) {
                if (state.awards.contains(state.flag == 2 ? -i : i)) {
                    record.add(data("sid", i, "num", 1));
                }
            }
            output.push(461, data("season", 1, "flag", state.flag, "total", state.points, "record", record));
        }
        periods.put(output, start);
        refresh.put(output, Instant.now().getEpochSecond() + 30);
    }

    /**
     * 跨周、升级以及新任务或充值到账后更新原客户端基金状态。
     * @param role 最新角色
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        long expected = role.level < 30 ? -1L : fund.weekStart();
        if (periods.getOrDefault(output, Long.MIN_VALUE) != expected
                || (expected >= 0 && Instant.now().getEpochSecond() >= refresh.getOrDefault(output, 0L))) {
            synchronize(role, output);
        }
    }
}
