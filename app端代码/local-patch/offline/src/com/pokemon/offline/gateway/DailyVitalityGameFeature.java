package com.pokemon.offline.gateway;

import com.pokemon.offline.activitydaily.DailyVitalityService;
import com.pokemon.offline.activitydaily.DailyVitalityService.Status;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原客户端每日活跃显示及原档位领奖入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:09
 */
@Service
final class DailyVitalityGameFeature implements GameFeature {
    private final DailyVitalityService vitality;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final Map<GameReply, Status> displayed = new WeakHashMap<>();

    /**
     * 加载活跃结算和资产刷新所需的服务。
     *
     * @param vitality 每日活跃服务
     * @param roles 角色货币查询服务
     * @param inventory 背包及邮件快照出口
     */
    DailyVitalityGameFeature(DailyVitalityService vitality, RoleService roles, InventoryGameFeature inventory) {
        this.vitality = vitality;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 校验并领取原任务页面选定的活跃档位。
     *
     * @param role 当前已认证角色
     * @param request 已解码的客户端请求
     * @param output 当前连接应答及推送通道
     * @return 是否处理该请求
     * @throws IOException 写入连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 133) {
            return false;
        }
        if (request.session > 0) {
            long index = number(request.body, "idx", 0);
            boolean granted = vitality.claim(role.id, index);
            output.send(request, data("errno", granted ? 0 : 1, "idx", index));
            if (granted) {
                LocalRole updated = roles.findById(role.id).orElse(role);
                output.push(3, data("coins", updated.coins, "diamonds", updated.diamonds));
                inventory.pushBag(updated, output);
                inventory.pushMailCount(updated, output);
            }
            pushStatus(role, output, false);
        }
        return true;
    }

    /**
     * 进入场景后推送当天积分及领奖记录。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @throws IOException 写入连接失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushStatus(role, output, true);
    }

    /**
     * 重新读取已提交的玩法流水并刷新发生变化的积分。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @throws IOException 写入连接失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        pushStatus(role, output, false);
    }

    /**
     * 使用本地快照替换原任务页进度。
     *
     * @param role 当前已认证角色
     * @param output 当前连接推送通道
     * @param force 是否为入场后的初始推送
     * @throws IOException 写入连接失败
     */
    private void pushStatus(LocalRole role, GameReply output, boolean force) throws IOException {
        Status previous;
        synchronized (displayed) {
            previous = displayed.get(output);
        }
        if (!force && previous == null) {
            return;
        }
        Status current = vitality.snapshot(role.id);
        if (force || previous.points != current.points || !previous.claimed.equals(current.claimed)) {
            output.push(413, data("vitality", current.points, "record", current.claimed));
            synchronized (displayed) {
                displayed.put(output, current);
            }
        }
    }
}
