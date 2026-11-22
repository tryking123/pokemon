package com.pokemon.offline.gateway;

import com.pokemon.offline.hunting.HuntingService;
import com.pokemon.offline.hunting.HuntingSessionService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import static com.pokemon.offline.gateway.SprotoProtocol.number;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;

/**
 * 狩猎场原客户端入口。
 *
 * @author tongruiyuan
 * @since 2026-11-19 17:29
 */
@Service
final class HuntingGameFeature implements GameFeature {
    private final HuntingService hunting;
    private final HuntingSessionService sessions;
    private final InventoryGameFeature inventory;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final ObjectMapper json;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /**
     * 绑定狩猎场存档服务。
     * @param hunting 每日候选及场次服务
     * @param sessions 付费场次与捕获事务
     * @param inventory 背包与邮件数量推送
     * @param roles 已结算的钱包
     * @param battle 精灵及图鉴推送
     * @param json 协议字段转换
     */
    HuntingGameFeature(HuntingService hunting, HuntingSessionService sessions, InventoryGameFeature inventory, RoleService roles,
                       BattleGameFeature battle, ObjectMapper json) {
        this.hunting = hunting;
        this.sessions = sessions;
        this.inventory = inventory;
        this.roles = roles;
        this.battle = battle;
        this.json = json;
    }

    /**
     * 按客户端场序返回本日候选。
     * @param role 当前角色
     * @param request 原协议请求
     * @param output 游戏连接
     * @return 当前入口是否处理此请求
     * @throws IOException 应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 3101 || request.type > 3105 || request.session <= 0) {
            return false;
        }
        if (request.type != 3101) {
            String connection;
            synchronized (connections) {
                connection = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            }
            ObjectNode reply = sessions.act(role.id, request.type,
                    number(request.body, request.type == 3104 ? "itemid" : "itype", 0),
                    number(request.body, "guide", 0), connection + ":" + request.type + ":" + request.session);
            output.send(request, json.convertValue(reply, new TypeReference<Map<String,Object>>() {}));
            int errno = reply.path("errno").asInt();
            if (errno == 0 || errno == HuntingSessionService.CATCH_FAILED) {
                if (request.type == 3102 || request.type == 3105) {
                    publish(role.id, output);
                }
                if (request.type != 3103) {
                    inventory.pushBag(role, output);
                }
                if (request.type == 3105) {
                    output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
                }
                if (reply.has("elfid")) {
                    inventory.pushMailCount(role, output);
                    battle.pushElfs(role.id, output);
                }
            }
            return true;
        }
        List<Integer> pool = hunting.candidates(role.id);
        output.send(request, data("errno", 0, "low", pool.subList(0, 6), "mid", pool.subList(6, 12),
                "high", Collections.emptyList()));
        return true;
    }

    /** 恢复已经付费的场次。
     * @param role 登录角色
     * @param output 原游戏连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        publish(role.id, output);
    }

    /** 推送当前目标与实际幸运值。
     * @param id 当前角色
     * @param output 原游戏连接
     * @throws IOException 推送失败
     */
    private void publish(String id, GameReply output) throws IOException {
        ObjectNode state = sessions.session(id);
        // 原模型在捕获应答中清空目标；S751 会触发加载目标模板的动画。
        if (state.path("cur_elf").asInt() == 0) {
            return;
        }
        output.push(751, data("cur_elf", state.path("cur_elf").asInt(), "grid", state.path("grid").asInt(),
                "lucky", state.path("lucky").asInt()));
    }
}
