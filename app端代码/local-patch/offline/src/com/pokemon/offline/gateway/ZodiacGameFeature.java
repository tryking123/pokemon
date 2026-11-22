package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.ZodiacService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 十二宫原远征、退出及宝箱入口。
 * @author tongruiyuan
 * @since 2026-11-19 17:47
 */
@Service
@Order(-35)
final class ZodiacGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY = new TypeReference<Map<String,Object>>() {};
    private final ZodiacService zodiac;
    private final ArenaGameFeature panel;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final BattleGameFeature battle;
    private final ObjectMapper json;
    private final Map<GameReply,String> connections = new WeakHashMap<>();

    /**
     * 绑定远征及原客户端推送出口。
     * @param zodiac 远征战斗和结算
     * @param panel 十二宫面板
     * @param roles 角色钱包
     * @param inventory 背包同步
     * @param battle 精灵与力石背包同步
     * @param json 消息字段转换
     */
    ZodiacGameFeature(ZodiacService zodiac, ArenaGameFeature panel, RoleService roles,
                      InventoryGameFeature inventory, BattleGameFeature battle, ObjectMapper json) {
        this.zodiac = zodiac;
        this.panel = panel;
        this.roles = roles;
        this.inventory = inventory;
        this.battle = battle;
        this.json = json;
    }

    /** 接收原宫位战斗、原战斗同步和宝箱领取。
     * @param role 当前角色
     * @param request 原请求
     * @param output 当前连接
     * @return 本入口是否处理
     * @throws IOException 消息发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 273 && request.session > 0) {
            ObjectNode result = zodiac.start(role.id);
            ObjectNode wire = result.deepCopy().retain("errno","seed","weather","player","monster","limitround","limitwinner");
            for (String side : Arrays.asList("player", "monster")) {
                java.util.Iterator<JsonNode> actors = wire.path(side).path("pokemon").elements();
                while (actors.hasNext()) {
                    ObjectNode actor = (ObjectNode) actors.next();
                    if (actor.path("hp").asInt() <= 0) {
                        actors.remove();
                    } else {
                        actor.remove(Arrays.asList("skill", "goldCarry", "homeStatues", "baseDamage", "targetLimit", "physical"));
                    }
                }
            }
            output.send(request, json.convertValue(wire, BODY));
            return true;
        }
        if (request.type == 274 && request.session > 0) {
            String key;
            synchronized (connections) {
                key = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            }
            long index = number(request.body,"idx",0);
            ObjectNode reply = zodiac.openBox(role.id,index >= 1 && index <= 12 ? (int) index : 0,
                    "zodiac:box:" + key + ":" + request.session);
            output.send(request,json.convertValue(reply,BODY));
            if (reply.path("errno").asInt() == 0) {
                inventory.pushBag(role,output);
                battle.pushElfs(role.id,output);
                output.push(3,data("diamonds",roles.findById(role.id).orElse(role).diamonds));
                panel.synchronize(role,output);
            }
            return true;
        }
        switch (request.type) {
            case 3001:
            case 3002:
                if (!zodiac.active(role.id)) {
                    return false;
                }
                Object target = request.body.get("objs");
                int outcome = zodiac.advance(role.id,(int) number(request.body,"sid",0),
                        target instanceof List ? (List<?>) target : Collections.emptyList(),
                        (int) number(request.body,"flag",-1),request.type == 3002);
                if (outcome > 0) {
                    panel.synchronize(role,output);
                    output.push(1051,data());
                }
                return true;
            case 66: case 68: case 69: case 102: case 105: case 109: case 268: case 181: case 2203: case 272:
            case 3037: case 5071: case 5072:
                if (zodiac.active(role.id)) {
                    if (request.session > 0) {
                        output.send(request,data("errno",2002));
                    }
                    return true;
                }
                return false;
            default:
                return false;
        }
    }
}
