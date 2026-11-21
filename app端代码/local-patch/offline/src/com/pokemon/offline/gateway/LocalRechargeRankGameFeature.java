package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.OpeningRankRewards;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.activity.OpeningRankRewards.LOCAL_RECHARGE_EVENT;
import static com.pokemon.offline.gateway.SprotoProtocol.data;

/**
 * 原跨服充值榜页面的本地单服排行协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:00
 */
@Service
final class LocalRechargeRankGameFeature implements GameFeature {
    private static final int READ_RANK = 10271;
    private static final long DISPLAY_MULTIPLIER = 10;
    private final ActivityService activities;
    private final OpeningRankRewards rewards;
    private final RoleCosmeticService cosmetics;

    /**
     * 加载本地活动周期、已确认模拟订单排行和角色外观。
     *
     * @param activities 运营活动日历与榜单角色查找服务
     * @param rewards 排行分数及奖档服务
     * @param cosmetics 已保存的时装展示服务
     */
    LocalRechargeRankGameFeature(ActivityService activities, OpeningRankRewards rewards, RoleCosmeticService cosmetics) {
        this.activities = activities;
        this.rewards = rewards;
        this.cosmetics = cosmetics;
    }

    /**
     * 读取本地 admin 的真实模拟充值记录并返回单服排名。
     *
     * @param role 已认证角色
     * @param request 原活动榜单请求
     * @param output 原协议出口
     * @return 是否识别该请求
     * @throws IOException 无法写入应答
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != READ_RANK) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        ActivityPeriod period = activities.cycle(LOCAL_RECHARGE_EVENT);
        long minimum = rewards.threshold(LOCAL_RECHARGE_EVENT);
        Optional<LocalRole> ranked = activities.rankedRole(role.id);
        List<Map<String, Object>> entries = Collections.emptyList();
        long amount = 0;
        if (ranked.isPresent()) {
            LocalRole candidate = ranked.get();
            amount = rewards.currentScore(candidate, period);
            List<Integer> award = amount >= minimum ? rewards.rewards(LOCAL_RECHARGE_EVENT) : Collections.emptyList();
            entries = Collections.singletonList(data("name", candidate.name, "sex", candidate.sex,
                    "level", Math.multiplyExact(amount, DISPLAY_MULTIPLIER), "dress", cosmetics.wornDress(candidate.id),
                    "reward", award, "uid", candidate.id, "serverid", candidate.realmId, "icon", candidate.avatar));
        }
        long displayedThreshold = Math.multiplyExact(minimum, DISPLAY_MULTIPLIER);
        output.send(request, data("errno", 0, "info", entries, "my_consumption", amount * DISPLAY_MULTIPLIER,
                "inrank", ranked.isPresent() ? 1 : 0, "three_num", displayedThreshold, "ten_num", displayedThreshold,
                "top_num", displayedThreshold));
        return true;
    }
}
