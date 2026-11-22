package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activitylogin.LoginActivityService;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Propagation;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 地下城赛季奖励及命运之轮结算。
 *
 * @author tongruiyuan
 * @since 2026-11-20 14:49
 */
@Service
public class DnfRewards {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final InventoryService inventory;
    private final RoleService roles;
    private final LoginActivityService dresses;
    private final HangupService hangup;

    /**
     * 绑定角色、背包、时装及精灵经验账本。
     * @param inventory 背包服务
     * @param roles 角色服务
     * @param dresses 时装服务
     * @param hangup 精灵经验池
     */
    public DnfRewards(InventoryService inventory, RoleService roles, LoginActivityService dresses, HangupService hangup) {
        this.inventory = inventory;
        this.roles = roles;
        this.dresses = dresses;
        this.hangup = hangup;
    }

    /**
     * 在调用方已锁角色、检查业务回执的赛季事务中结算实际奖励。
     * @param role 角色编号
     * @param state 本轮持久化状态
     * @param triples 类型、模板及数量组成的扁平数组
     * @param key 关卡、派遣或抽奖业务的唯一发放回执
     * @return 已直接到账的原协议附件
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode grant(String role, ObjectNode state, JsonNode triples, String key) {
        if (!triples.isArray() || triples.size() % 3 != 0) {
            throw new IllegalArgumentException("地下城奖励配置不完整");
        }
        ObjectNode adds = JSON.objectNode();
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < triples.size(); i += 3) {
            int kind = triples.get(i).asInt();
            int id = triples.get(i + 1).asInt();
            long n = triples.get(i + 2).asLong();
            if (n <= 0) {
                throw new IllegalArgumentException("地下城奖励数量无效");
            }
            switch (kind) {
                case 1:
                    items.put(id, items.getOrDefault(id, 0L) + n);
                    break;
                case 3:
                    inventory.grantChip(role, id, n);
                    adds.withArray("elschips").addObject().put("id", id).put("n", n);
                    break;
                case 6:
                    dresses.grantDress(role, id);
                    adds.withArray("fashes").add(id);
                    break;
                case 102:
                    roles.addCurrency(role, 0, 0, n);
                    adds.put("exp", adds.path("exp").asLong() + n);
                    break;
                case 103:
                    roles.addCurrency(role, n, 0, 0);
                    adds.put("coins", adds.path("coins").asLong() + n);
                    break;
                case 104:
                    roles.addCurrency(role, 0, n, 0);
                    adds.put("diamonds", adds.path("diamonds").asLong() + n);
                    break;
                case 108:
                    hangup.addElfPool(role, n);
                    adds.put("exp1", adds.path("exp1").asLong() + n);
                    break;
                case 116:
                    state.put("score", state.path("score").asLong() + n);
                    adds.put("dnfpoint", adds.path("dnfpoint").asLong() + n);
                    break;
                default:
                    throw new IllegalArgumentException("未知的地下城奖励类型 " + kind);
            }
        }
        for (Map.Entry<Integer, Long> item : inventory.grantItemsOrMail(role, key, items).entrySet()) {
            adds.withArray("items").addObject().put("id", item.getKey()).put("n", item.getValue());
        }
        return adds;
    }

    /**
     * 读取本季奖盘，转动时扣除机会并按原权重发放落点对应奖品。
     * @param role 角色编号
     * @param state 本轮持久化状态
     * @param base 当前赛季底包配置行
     * @param spin 是否实际转动
     * @param key 本次转动的唯一业务回执
     * @return 原协议奖盘、从一开始的中奖位置及已到账附件
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode lottery(String role, ObjectNode state, JsonNode base, boolean spin, String key) {
        ObjectNode result = JSON.objectNode().put("errno", 0);
        ArrayNode pool = result.putArray("pool");
        JsonNode rows = base.path("runreward");
        int total = 0;
        for (int i = 0; i < rows.size(); i += 4) {
            pool.addObject().put("itype", rows.get(i).intValue()).put("id", rows.get(i + 1).intValue())
                    .put("num", rows.get(i + 2).intValue());
            total += rows.get(i + 3).intValue();
        }
        if (!spin) {
            return result;
        }
        int cost = base.path("runcost").intValue();
        int left = state.path("lotto").asInt();
        if (cost <= 0 || left < cost) {
            return result.put("errno", 100051);
        }
        int draw = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < rows.size(); i += 4) {
            draw -= rows.get(i + 3).intValue();
            if (draw < 0) {
                ArrayNode award = JSON.arrayNode().add(rows.get(i)).add(rows.get(i + 1)).add(rows.get(i + 2));
                result.set("adds", grant(role, state, award, key));
                state.put("lotto", left - cost);
                return result.put("goal", i / 4 + 1);
            }
        }
        throw new IllegalStateException("地下城奖池无有效落点");
    }
}
