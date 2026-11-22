package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.home.HomeExamService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 家园答题入口、原试卷协议及答对后的到账通知。
 * @author tongruiyuan
 * @since 2026-11-19 21:39
 */
@Service
final class HomeExamGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {};
    private final HomeExamService exam;
    private final InventoryGameFeature inventory;
    private final RoleService roles;
    private final ObjectMapper json;
    private final Map<GameReply, Session> sessions = new WeakHashMap<>();

    /** 连接最近显示的试题，避免另一连接翻页后误答下一题。 */
    private static final class Session {
        final String id = UUID.randomUUID().toString();
        long shown;
        long panelDay;
    }

    /**
     * 绑定答题和资产同步。
     * @param exam 答题业务
     * @param inventory 背包、家园币和邮件
     * @param roles 角色钱包
     * @param json 协议映射
     */
    HomeExamGameFeature(HomeExamService exam, InventoryGameFeature inventory, RoleService roles, ObjectMapper json) {
        this.exam = exam;
        this.inventory = inventory;
        this.roles = roles;
        this.json = json;
    }

    /**
     * 恢复原入口的本日成绩。
     * @param role 角色
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        Session current = session(output);
        current.panelDay = (System.currentTimeMillis() / 1000 + 28800) / 86400;
        output.push(829, json.convertValue(exam.panel(role.id), BODY));
    }

    /**
     * 在线跨日后清理前一天的试题并刷新答题入口。
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        Session current = session(output);
        if (current.panelDay != (System.currentTimeMillis() / 1000 + 28800) / 86400) {
            current.shown = 0;
            synchronize(role, output);
        }
    }

    /**
     * 取得当前连接的出题记录。
     * @param output 当前连接
     * @return 会话状态
     */
    private Session session(GameReply output) {
        synchronized (sessions) {
            return sessions.computeIfAbsent(output, unused -> new Session());
        }
    }

    /**
     * 处理出题、排除选项、交卷和改分，按原回调顺序推送奖励。
     * @param role 角色
     * @param request 客户端请求
     * @param output 当前连接
     * @return 是否属于答题协议
     * @throws IOException 推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 373 || request.type > 376) return false;
        if (request.session <= 0) return true;
        Session session = session(output);
        ObjectNode result = exam.handle(role.id, request.type, number(request.body, "option", -1), session.shown,
                "exam:" + session.id + ":" + request.type + ":" + request.session);
        JsonNode token = result.remove("token");
        if (token != null) session.shown = token.asLong();
        JsonNode adds = result.remove("adds"), spent = result.remove("spent");
        if (adds != null) output.push(14, data("flags", "riddle.elect", "adds", json.convertValue(adds, BODY)));
        if (spent != null) output.push(14, data("flags", "riddle.cost", "loses", data("diamonds", spent.asInt())));
        if (adds != null || spent != null) {
            output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            if (adds != null && adds.has("homecoin")) inventory.pushHomeWallet(role.id, output);
            if (adds != null && adds.has("items")) {
                inventory.pushBag(role, output);
                inventory.pushMailCount(role, output);
            }
        }
        output.send(request, json.convertValue(result, BODY));
        return true;
    }
}
