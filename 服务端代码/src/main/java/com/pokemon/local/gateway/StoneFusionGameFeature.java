package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.StoneFusionService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 原奥义石融合和洗练窗口的回环协议适配。
 * @author tongruiyuan
 * @since 2026-11-20 09:11
 */
@Service
final class StoneFusionGameFeature implements GameFeature {
    private final StoneFusionService fusion;
    private final BagSnapshot bags;
    private final RoleService roles;
    private final ObjectMapper json;
    private final BattleGameFeature battle;
    private final Map<GameReply,String> sessions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final TypeReference<Map<String,Object>> BODY = new TypeReference<Map<String,Object>>() {};

    /**
     * 连接原请求和本地奥义石存档。
     * @param fusion 奥义石业务
     * @param bags 背包快照
     * @param roles 钱包快照
     * @param battle 精灵战斗与图鉴快照
     * @param json 原字段转换器
     */
    StoneFusionGameFeature(StoneFusionService fusion, BagSnapshot bags, RoleService roles, ObjectMapper json, BattleGameFeature battle) {
        this.battle = battle;
        this.fusion = fusion;
        this.bags = bags;
        this.roles = roles;
        this.json = json;
    }

    /**
     * 接收原融合、洗练和保存请求并回传已提交结果。
     * @param role 本地角色
     * @param request 原协议请求
     * @param output 回环连接
     * @return 当前接口是否已处理
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int command = request.type;
        if (command != 5012 && command != 5013 && command != 5014 && command != 5016) {
            return false;
        }
        if (request.session > 0) {
            String key = sessions.computeIfAbsent(output, ignored -> UUID.randomUUID().toString()) + ":" + command + ":" + request.session;
            if (command == 5013) {
                StoneFusionService.Wear change = fusion.wear(role.id, (String) request.body.get("equ_uid"),
                        (String) request.body.get("elf_uid"), SprotoProtocol.number(request.body, "do_type", 0), key);
                if (change == null) {
                    output.send(request, data("errno", 1));
                } else {
                    Map<String, Object> result = data("errno", 0, "elf_info", battle.equippedElfInfo(role.id, change.elf));
                    if (change.removed.has("sid")) {
                        result.put("takeoff", json.convertValue(change.removed, BODY));
                    }
                    bags.push(role.id, output);
                    battle.pushElfs(role.id, output);
                    output.send(request, result);
                }
                return true;
            }
            ObjectNode result = fusion.act(role.id, command, json.valueToTree(request.body), key);
            output.send(request, json.convertValue(result, BODY));
            if (result.path("errno").asInt() == 0) {
                bags.push(role.id, output);
                String target = (String) request.body.get(command == 5012 ? "synthetic_A" : "uid");
                if (command != 5014 && fusion.equipped(role.id, target)) {
                    battle.pushElfs(role.id, output);
                }
                LocalRole saved = roles.findById(role.id).orElse(role);
                output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
            }
        }
        return true;
    }
}
