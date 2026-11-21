package com.pokemon.local.gateway;

import com.pokemon.local.activity.FestivalWelfareService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 节日登录福利的原客户端弹窗、领取与资产推送。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:59
 */
@Service
final class FestivalWelfareGameFeature implements GameFeature {
    private final FestivalWelfareService welfare;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final Map<GameReply,Long> previews = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 连接福利业务与原客户端资产视图。
     * @param welfare 节日登录福利
     * @param roles 角色钱包
     * @param bags 道具背包和邮件
     * @param battle 精灵背包及图鉴
     */
    FestivalWelfareGameFeature(FestivalWelfareService welfare, RoleService roles, BagSnapshot bags, BattleGameFeature battle) {
        this.welfare = welfare;
        this.roles = roles;
        this.bags = bags;
        this.battle = battle;
    }

    /**
     * 响应原弹窗的本周领取请求。
     * @param role 当前角色
     * @param request 回环请求
     * @param output 原协议连接
     * @return 是否已处理
     * @throws IOException 推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 281) {
            return false;
        }
        if (request.session > 0) {
            Long week = previews.get(output);
            boolean granted = week != null && welfare.claim(role.id, week);
            if (granted) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
                bags.push(role.id, output);
                bags.pushMailCount(role.id, output);
                battle.pushElfs(role.id, output);
            }
            output.send(request, data("errno", granted ? 0 : 1));
            synchronize(roles.findById(role.id).orElse(role), output);
        }
        return true;
    }

    /**
     * 将当前角色可见的原奖励和领取标记推送至节日弹窗。
     * @param role 当前角色
     * @param output 原协议连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        long week = welfare.week();
        boolean eligible = role.level >= 40;
        output.push(812, data("index", eligible ? welfare.index(week) : 0,
                "flag", eligible && welfare.claimed(role.id, week) ? 1 : 0));
        previews.put(output, eligible ? week : -1L);
    }

    /**
     * 在线跨周或达到四十级时刷新弹窗。
     * @param role 心跳时角色
     * @param output 原协议连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Long shown = previews.get(output);
        long expected = role.level < 40 ? -1L : welfare.week();
        if (shown == null || shown != expected) {
            synchronize(role, output);
        }
    }
}
