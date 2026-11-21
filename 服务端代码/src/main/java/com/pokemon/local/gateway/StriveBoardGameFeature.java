package com.pokemon.local.gateway;

import static com.pokemon.local.gateway.SprotoProtocol.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.StriveBoardService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 对接原训练中心、骰子动画与治疗视图。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:03
 */
@Service
final class StriveBoardGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY =
            new TypeReference<Map<String, Object>>() {};
    private final StriveBoardService board;
    private final ObjectMapper json;
    private final RoleService roles;
    private final ActivityGameFeature activities;
    private final Map<GameReply, String> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply, Long> days = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定真实棋盘、角色钱包和概览。
     *
     * @param board 训练中心
     * @param json 原协议转换器
     * @param roles 角色钱包
     * @param activities 活动概览推送
     */
    StriveBoardGameFeature(
            StriveBoardService board,
            ObjectMapper json,
            RoleService roles,
            ActivityGameFeature activities) {
        this.board = board;
        this.json = json;
        this.roles = roles;
        this.activities = activities;
    }

    /**
     * 转成实际协议字段。
     *
     * @param node 状态或应答节点
     * @return 原协议映射
     */
    private Map<String, Object> wire(JsonNode node) {
        return json.convertValue(node, BODY);
    }

    /**
     * 登录或升至开放等级时推送原两张棋盘，恢复精灵伤情。
     *
     * @param role 登录角色
     * @param out 当前连接
     * @throws IOException 连接写入失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply out) throws IOException {
        if (!board.open(role)) return;
        days.put(out, Math.floorDiv(Instant.now().getEpochSecond() + 28800, 86400));
        out.push(2040, wire(board.snapshot(role.id)));
    }

    /**
     * 跨日本地刷新高级训练的免费次数。
     *
     * @param role 角色
     * @param out 当前连接
     * @throws IOException 连接写入失败
     */
    @Override
    public void tick(LocalRole role, GameReply out) throws IOException {
        if (board.open(role)
                && days.getOrDefault(out, -1L)
                        != Math.floorDiv(Instant.now().getEpochSecond() + 28800, 86400))
            synchronize(role, out);
    }

    /**
     * 应答后按原客户端顺序发送骰子及单张新棋盘。
     *
     * @param role 角色
     * @param req 客户端请求
     * @param out 当前连接
     * @return 是否属于训练中心
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request req, GameReply out)
            throws IOException {
        if (req.type != 5096 && req.type != 5098 && req.type != 5099) return false;
        if (req.session <= 0) return true;
        if (!board.open(role)) {
            out.send(req, data("errno", 2002));
            return true;
        }
        if (req.type == 5098) {
            out.send(req, data("errno", 0));
            synchronize(role, out);
            return true;
        }
        String receipt =
                sessions.computeIfAbsent(out, k -> UUID.randomUUID().toString())
                        + ":"
                        + req.type
                        + ":"
                        + req.session;
        ObjectNode result = board.act(role.id, req.type, json.valueToTree(req.body), receipt);
        out.send(req, wire(result.deepCopy().retain("errno", "info", "treatment")));
        if (result.path("errno").asInt() != 0) return true;
        if (result.has("info")) {
            out.push(
                    2041,
                    data(
                            "dice",
                            result.path("info").path("dice").asInt(),
                            "itype",
                            result.path("diceKind").asInt()));
            out.push(2040, wire(result.path("board")));
            activities.pushActivityStatus(role, out);
        }
        if (result.path("wallet").asBoolean())
            out.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
        return true;
    }
}
