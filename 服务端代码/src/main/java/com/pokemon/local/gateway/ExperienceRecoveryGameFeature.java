package com.pokemon.local.gateway;

import com.pokemon.local.role.ExperienceRecoveryService;
import com.pokemon.local.dailydup.DailyDungeonService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 经验补偿和日常副本奖励找回的原客户端入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:11
 */
@Service
final class ExperienceRecoveryGameFeature implements GameFeature {
    private final ExperienceRecoveryService recovery;
    private final RoleService roles;
    private final DailyDungeonService daily;
    private final ObjectMapper json;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {};

    /** 面板的所属副本日和最近展示的找回资格。 */
    private static final class Session {
        final String id = UUID.randomUUID().toString();
        long day;
        long shown;
    }

    /** 注入追赶业务与角色变更读取。
     * @param daily 每日副本奖励找回
     * @param json 协议映射
     * @param recovery 经验补偿业务
     * @param roles 角色状态
     */
    ExperienceRecoveryGameFeature(ExperienceRecoveryService recovery, RoleService roles, DailyDungeonService daily, ObjectMapper json) {
        this.daily = daily;
        this.json = json;
        this.recovery = recovery;
        this.roles = roles;
    }

    /** 办理经验补偿或找回副本奖励，同步角色资产和面板。
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 当前连接
     * @return 是否处理本次请求
     * @throws IOException 协议写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 5093 && request.session > 0) {
            Session current = session(output);
            long granted = number(request.body, "id", 0) == 1100
                    ? daily.recover(role.id, number(request.body, "choose_type", 0), current.shown,
                            current.id + ":" + request.session) : -1;
            output.send(request, data("errno", granted < 0 ? 1 : 0));
            if (granted > 0) {
                output.push(14, data("flags", "rewardback.claim", "adds", data("coins", granted)));
                LocalRole saved = roles.findById(role.id).orElse(role);
                output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
            }
            updateRewards(role.id, output);
            return true;
        }
        if (request.type != 5091 || request.session <= 0) {
            return false;
        }
        int errno = recovery.claim(role.id, number(request.body, "choose_type", 0));
        output.send(request, data("errno", errno, "next_exp", 0));
        if (errno == 0) {
            LocalRole updated = roles.findById(role.id).orElse(role);
            output.push(3, data("level", updated.level, "exp", updated.exp, "diamonds", updated.diamonds));
            synchronize(updated, output);
        }
        return true;
    }

    /** 推送达到条件的经验找回入口。
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 协议写入失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        updateRewards(role.id, output);
        Map<String, Object> state = recovery.snapshot(role);
        if (!state.isEmpty()) {
            output.push(2033, state);
        }
    }

    /**
     * 在线跨越每日副本 04:00 清算边界后更新未用场次。
     * @param role 角色
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        if (session(output).day != (System.currentTimeMillis()/1000 + 14400)/86400) updateRewards(role.id, output);
    }

    /**
     * 取得连接的最近面板记录。
     * @param output 当前连接
     * @return 本地会话
     */
    private Session session(GameReply output) {
        synchronized (sessions) { return sessions.computeIfAbsent(output, k -> new Session()); }
    }

    /**
     * 刷新原奖励找回页与本连接可提交的最早资格。
     * @param id 角色编号
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    private void updateRewards(String id, GameReply output) throws IOException {
        Session current = session(output);
        current.day = (System.currentTimeMillis()/1000 + 14400)/86400;
        ObjectNode panel = daily.recovery(id);
        current.shown = panel.path("day").asLong(-1);
        panel.remove("day");
        output.push(2034, json.convertValue(panel, BODY));
    }
}
