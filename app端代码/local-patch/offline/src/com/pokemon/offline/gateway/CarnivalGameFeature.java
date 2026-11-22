package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.CarnivalService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 七日目标原活动页面和领奖协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 19:40
 */
@Service
final class CarnivalGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() { };
    private final Map<GameReply, Long> opened = Collections.synchronizedMap(new WeakHashMap<>());
    private final CarnivalService carnival;
    private final ActivityService calendar;
    private final InventoryGameFeature inventory;
    private final RoleService roles;
    private final ObjectMapper json;

    /**
     * 绑定七日活动、角色资产和原协议数据。
     * @param carnival 七日目标结算
     * @param calendar 本地活动周期
     * @param inventory 背包和邮件通知
     * @param roles 当前角色余额
     * @param json 协议字段转换
     */
    CarnivalGameFeature(CarnivalService carnival, ActivityService calendar, InventoryGameFeature inventory,
                        RoleService roles, ObjectMapper json) {
        this.carnival = carnival;
        this.calendar = calendar;
        this.inventory = inventory;
        this.roles = roles;
        this.json = json;
    }

    /**
     * 响应当前轮次的查询、逐日领取和真实到账同步。
     * @param role 已认证角色
     * @param request 原协议请求
     * @param output 当前连接
     * @return 是否处理该请求
     * @throws IOException 消息发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 177 && request.type != 178) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 177) {
            ActivityPeriod period = calendar.cycle(CarnivalService.ACTIVITY);
            opened.put(output, period.start);
            output.send(request, json.convertValue(carnival.panel(role.id, period), BODY));
            return true;
        }
        long day = number(request.body, "idx1", 0);
        long row = number(request.body, "idx2", 0);
        ObjectNode result = carnival.claim(role.id, opened.getOrDefault(output, Long.MIN_VALUE), day, row);
        int errno = result.path("errno").asInt();
        output.send(request, data("errno", errno, "idx1", day, "idx2", row));
        if (errno == 0) {
            output.push(14, data("flags", "activity.carnival", "adds", json.convertValue(result.get("adds"), BODY)));
            if (result.get("adds").has("diamonds")) {
                output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            inventory.pushBag(role, output);
            inventory.pushMailCount(role, output);
        }
        return true;
    }
}
