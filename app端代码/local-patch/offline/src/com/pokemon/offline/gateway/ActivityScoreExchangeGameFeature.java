package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityScoreExchangeService;
import com.pokemon.offline.activity.ActivityScoreExchangeService.Result;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.activity.ActivityScoreExchangeService.KYLIN;
import static com.pokemon.offline.activity.ActivityScoreExchangeService.REBATE;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原版神兽降临和超级返利页面的 Sproto 接口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:09
 */
@Service
final class ActivityScoreExchangeGameFeature implements GameFeature {
    private final ActivityService calendar;
    private final ActivityScoreExchangeService exchanges;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /**
     * 接入活动结算和实际奖励的资产推送。
     *
     * @param calendar 持久化活动周期
     * @param exchanges 签到和积分兑换服务
     * @param bags 背包推送
     * @param battle 精灵库存推送
     */
    ActivityScoreExchangeGameFeature(ActivityService calendar, ActivityScoreExchangeService exchanges,
                                      BagSnapshot bags, BattleGameFeature battle) {
        this.calendar = calendar;
        this.exchanges = exchanges;
        this.bags = bags;
        this.battle = battle;
    }

    /**
     * 刷新积分兑换页面、办理签到或兑换。
     *
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 协议响应和推送通道
     * @return 是否为本功能的协议
     * @throws IOException 无法发送结果
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 172 || request.type > 176 || request.session <= 0) {
            return false;
        }
        Session session;
        synchronized (sessions) {
            session = sessions.computeIfAbsent(output, unused -> new Session());
        }
        int activityId = request.type <= 173 ? KYLIN : REBATE;
        if (request.type == 172 || request.type == 174) {
            ActivityPeriod period = calendar.cycle(activityId);
            session.viewed.put(activityId, period);
            output.send(request, exchanges.status(role.id, period));
        } else if (request.type == 175) {
            Result result = exchanges.sign(role.id, session.viewed.get(REBATE));
            output.send(request, data("errno", result.errno, "score", result.score));
        } else {
            long index = number(request.body, "idx", 0);
            Result result = exchanges.redeem(role.id, activityId, session.viewed.get(activityId), index,
                    session.id + ":" + request.session);
            output.send(request, data("errno", result.errno, "idx", index));
            if (result.errno == 0) {
                pushReward(role, activityId, result, output);
            }
        }
        return true;
    }

    /**
     * 发布实际取得的道具或精灵，并刷新相应背包。
     *
     * @param role 当前角色
     * @param activityId 运营活动编号
     * @param result 已结算的奖励
     * @param output 推送通道
     * @throws IOException 无法发送资产变化
     */
    private void pushReward(LocalRole role, int activityId, Result result, GameReply output) throws IOException {
        Map<String, Object> adds;
        if (result.elf != null) {
            LocalElf elf = result.elf;
            adds = data("elf", Collections.singletonList(data("id", elf.id, "sid", elf.configId, "level", elf.level)));
            battle.pushElfs(role.id, output);
        } else {
            adds = data("items", Collections.singletonList(data("id", result.item, "n", result.quantity)));
            bags.push(role.id, output);
        }
        output.push(14, data("flags", activityId == KYLIN ? "activity.kylin" : "activity.rebate", "adds", adds));
    }

    /**
     * 保存连接打开的活动轮次及兑换去重前缀。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:09
     */
    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final Map<Integer, ActivityPeriod> viewed = new HashMap<>();
    }
}
