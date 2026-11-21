package com.pokemon.local.gateway;

import com.pokemon.local.activitylogin.LoginActivityService;
import com.pokemon.local.activitylogin.api.entity.LoginActivityState;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 七日登录活动的原 Unity 协议适配
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:03
 */
@Service
final class LoginActivityGameFeature implements GameFeature {
    private final LoginActivityService activity;
    private final RoleService roles;
    private final InventoryGameFeature bags;
    private final BattleGameFeature battle;
    private final CosmeticGameFeature cosmetics;
    private final Map<GameReply, LoginActivityState> connections =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定七日活动及相关资产的原协议推送
     *
     * @param activity 七日登录活动
     * @param roles 角色钱包读取
     * @param bags 普通背包协议
     * @param battle 精灵背包协议
     * @param cosmetics 时装持有和穿戴状态推送
     */
    LoginActivityGameFeature(LoginActivityService activity, RoleService roles,
                             InventoryGameFeature bags, BattleGameFeature battle, CosmeticGameFeature cosmetics) {
        this.activity = activity;
        this.roles = roles;
        this.bags = bags;
        this.battle = battle;
        this.cosmetics = cosmetics;
    }

    /**
     * 响应七日奖励详情或领取请求
     *
     * @param role 当前认证角色
     * @param request 原游戏请求
     * @param output 原协议应答通道
     * @return 是否由七日登录活动处理
     * @throws IOException 应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 136 && request.type != 137 && request.type != 2601) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 136) {
            pushState(role.id, output);
            List<Map<String, Object>> info = activity.rewards().stream()
                    .map(row -> data("rew", row)).collect(Collectors.toList());
            output.send(request, data("errno", 0, "info", info));
            return true;
        }

        boolean legacy = request.type == 2601;
        long index = number(request.body, legacy ? "day" : "idx", 0);
        LoginActivityState prior = connections.get(output);
        boolean granted = prior != null && activity.claim(role.id, prior.periodStart, index);
        output.send(request, legacy ? data("errno", granted ? 0 : 1) : data("errno", granted ? 0 : 1, "idx", index));
        pushState(role.id, output);
        if (granted) {
            pushAssets(role, activity.rewards().get((int) index - 1), output);
            bags.pushMailCount(role, output);
        }
        return true;
    }

    /**
     * 在场景加载完成时推送七日活动状态
     *
     * @param role 当前认证角色
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushState(role.id, output);
    }

    /**
     * 在线跨越自然日或活动周期时重新同步
     *
     * @param role 当前认证角色
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        LoginActivityState state = connections.get(output);
        if (state == null || Instant.now().getEpochSecond() >= state.nextRefresh) {
            pushState(role.id, output);
        }
    }

    /**
     * 重置或更新原客户端的登录天数和已领奖序号
     *
     * @param roleId 当前角色编号
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    private void pushState(String roleId, GameReply output) throws IOException {
        LoginActivityState state = activity.visit(roleId);
        output.push(438, data("days", state.days, "record", state.record));
        connections.put(output, state);
    }

    /**
     * 推送此次奖励改变的资产视图
     *
     * @param role 当前认证角色
     * @param reward 本次奖励的两组类型、编号和数量
     * @param output 原协议推送通道
     * @throws IOException 推送失败
     */
    private void pushAssets(LocalRole role, List<Integer> reward, GameReply output) throws IOException {
        int first = reward.get(0);
        int second = reward.get(3);
        if (first == LoginActivityService.ITEM || second == LoginActivityService.ITEM) {
            bags.pushBag(role, output);
        }
        if (first == LoginActivityService.ELF || second == LoginActivityService.ELF) {
            battle.pushElfs(role.id, output);
        }
        if (first == LoginActivityService.DIAMONDS || second == LoginActivityService.DIAMONDS) {
            long diamonds = roles.findById(role.id).map(saved -> saved.diamonds).orElse(role.diamonds);
            output.push(3, data("diamonds", diamonds, "servertime", Instant.now().getEpochSecond()));
        }
        if (first == LoginActivityService.DRESS || second == LoginActivityService.DRESS) {
            cosmetics.pushDress(role.id, output);
        }
    }
}
