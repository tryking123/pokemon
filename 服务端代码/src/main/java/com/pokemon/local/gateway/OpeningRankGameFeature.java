package com.pokemon.local.gateway;

import com.pokemon.local.activity.OpeningRankRewards;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 在开服榜切换时结算上一轮并通知角色邮箱
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:44
 */
@Service
@Order(-1)
final class OpeningRankGameFeature implements GameFeature {
    private final OpeningRankRewards ranks;
    private final InventoryGameFeature mail;
    private final Map<GameReply, Long> deadlines = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定排行榜结算与收件箱
     *
     * @param ranks 榜单截止结算
     * @param mail 原客户端未读邮件数推送
     */
    OpeningRankGameFeature(OpeningRankRewards ranks, InventoryGameFeature mail) {
        this.ranks = ranks;
        this.mail = mail;
    }

    /**
     * 先结算到期榜单，再将请求交给对应玩法
     *
     * @param role 当前认证角色
     * @param request 原客户端请求
     * @param output 连接推送通道
     * @return 始终交给具体玩法处理
     * @throws IOException 推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        tick(role, output);
        return false;
    }

    /**
     * 入场恢复截止点并结算断线期间结束的榜单
     *
     * @param role 当前认证角色
     * @param output 连接推送通道
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) {
        deadlines.put(output, ranks.settle(role.id));
    }

    /**
     * 在线时在收到第一条新周期消息后更新邮箱
     *
     * @param role 当前认证角色
     * @param output 连接推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Long next = deadlines.get(output);
        if (next == null || Instant.now().getEpochSecond() >= next) {
            synchronize(role, output);
            mail.pushMailCount(role, output);
        }
    }
}
