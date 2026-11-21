package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfReleaseService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;

/**
 * 原精灵放生界面的请求及背包、经验池与阵容同步。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:14
 */
@Service
final class ElfReleaseGameFeature implements GameFeature {
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<List<Map<String, Object>>>() {};
    private final ElfReleaseService release;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final ObjectMapper json;

    /**
     * 连接放生服务和容器推送。
     * @param release 放生服务
     * @param bags 普通背包
     * @param battle 精灵与装配容器
     * @param json 协议映射
     */
    ElfReleaseGameFeature(ElfReleaseService release, BagSnapshot bags, BattleGameFeature battle, ObjectMapper json) {
        this.release = release;
        this.bags = bags;
        this.battle = battle;
        this.json = json;
    }

    /**
     * 将放生结果送回原回调，并用存档更新资产及精灵列表。
     * @param role 当前角色
     * @param request 原客户端请求
     * @param out 原协议连接
     * @return 是否处理本次请求
     * @throws IOException 推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply out) throws IOException {
        if (request.type != 3003) return false;
        if (request.session <= 0) return true;
        Object selection = request.body.get("onlyidlist");
        ObjectNode result = release.release(role.id, selection instanceof List ? (List<?>) selection : Collections.emptyList());
        int errno = result.path("errno").asInt();
        if (errno != 0) {
            out.send(request, data("errno", errno));
            return true;
        }
        // 原放生成功回调立即从当前精灵名册重绘两栏。
        battle.pushElfs(role.id, out);
        out.send(request, data("errno", 0, "Z_Stone_s", json.convertValue(result.get("Z_Stone_s"), ROWS)));
        Map<String,Object> adds = data("exp1", result.path("experience").asLong());
        if (result.has("items")) adds.put("items", json.convertValue(result.get("items"), ROWS));
        out.push(14, data("flags", "elf.release", "adds", adds, "loses", data()));
        out.push(3, data("exp1", result.path("pool").asLong()));
        bags.push(role.id, out);
        bags.pushMailCount(role.id, out);
        return true;
    }
}
