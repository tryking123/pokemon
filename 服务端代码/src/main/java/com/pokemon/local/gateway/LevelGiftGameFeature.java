package com.pokemon.local.gateway;

import com.pokemon.local.activitylevel.LevelGiftService;
import com.pokemon.local.activitylevel.LevelGiftService.State;
import com.pokemon.local.activitylevel.LevelGiftService.Tier;
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
 * 闯关礼包查询、领取与运营窗口刷新协议。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:30
 */
@Service
final class LevelGiftGameFeature implements GameFeature {
    private final LevelGiftService gifts;
    private final InventoryGameFeature bags;
    private final BattleGameFeature battle;
    private final RoleService roles;
    private final Map<GameReply, Long> periodEnds = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 初始化闯关礼包协议及相关资产推送。
     *
     * @param gifts 闯关礼包查询和领取
     * @param bags 背包快照推送
     * @param battle 精灵及阵容快照推送
     * @param roles 角色钱包查询
     */
    LevelGiftGameFeature(LevelGiftService gifts, InventoryGameFeature bags, BattleGameFeature battle, RoleService roles) {
        this.gifts = gifts;
        this.bags = bags;
        this.battle = battle;
        this.roles = roles;
    }

    /**
     * 按原数组顺序编码礼包列表，并处理客户端提交的礼包索引。
     *
     * @param role 当前已认证角色
     * @param request 原协议请求
     * @param output 游戏连接推送通道
     * @return 是否为闯关礼包请求
     * @throws IOException 写入连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 170 && request.type != 171) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 170) {
            State state = gifts.state(role.id);
            List<Map<String, Object>> info = new ArrayList<>(state.tiers.size());
            for (int index = 0; index < state.tiers.size(); index++) {
                Tier tier = state.tiers.get(index);
                info.add(data("grade", tier.grade, "rew", tier.rew, "flag", state.flags.get(index)));
            }
            output.send(request, data("errno", 0, "info", info));
            return true;
        }
        long index = number(request.body, "idx", 0);
        Tier reward = gifts.claim(role.id, index);
        output.send(request, data("errno", reward == null ? 1 : 0, "idx", index));
        if (reward != null) {
            bags.pushBag(role, output);
            if (reward.rew.get(0) == LevelGiftService.ELF) {
                battle.pushElfs(role.id, output);
            } else {
                long diamonds = roles.findById(role.id).map(saved -> saved.diamonds).orElse(role.diamonds);
                output.push(3, data("diamonds", diamonds, "servertime", Instant.now().getEpochSecond()));
            }
            synchronize(role, output);
        }
        return true;
    }

    /**
     * 将当前活动的领完标记同步到活动导航。
     *
     * @param role 当前已认证角色
     * @param output 游戏连接推送通道
     * @throws IOException 写入连接失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        State state = gifts.state(role.id);
        output.push(444, data("all", state.all));
        periodEnds.put(output, state.end);
    }

    /**
     * 在在线连接跨越运营周期时重置领完标记。
     *
     * @param role 当前已认证角色
     * @param output 游戏连接推送通道
     * @throws IOException 写入连接失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Long end = periodEnds.get(output);
        if (end != null && Instant.now().getEpochSecond() >= end) {
            synchronize(role, output);
        }
    }
}
