package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.LeagueSeasonService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 将本机训练师联赛接入原报名、分组、冠军和领奖界面。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:29
 */
@Service
@Order(-21)
final class LeagueSeasonGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY = new TypeReference<Map<String,Object>>() {};
    private final LeagueSeasonService league;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final BagSnapshot bags;
    private final InventoryGameFeature mail;

    /**
     * 绑定本机联赛与原资产刷新出口。
     * @param league 联赛结算服务
     * @param json 原协议字段映射
     * @param inventory 物品类型目录
     * @param bags 背包刷新
     * @param mail 邮件通知
     */
    LeagueSeasonGameFeature(LeagueSeasonService league, ObjectMapper json, InventoryService inventory,
                            BagSnapshot bags, InventoryGameFeature mail) {
        this.league = league;
        this.json = json;
        this.inventory = inventory;
        this.bags = bags;
        this.mail = mail;
    }

    /**
     * 应答报名、分组、冠军和奖励请求。
     * @param role 已登录角色
     * @param request 客户端原请求
     * @param output 消息出口
     * @return 本功能是否已处理该请求
     * @throws IOException 发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean replay = request.type == 249 || request.type == 250;
        if (!replay && (request.type < 234 || request.type > 245)) return false;
        if (request.session <= 0) return true;
        int stage = (int)number(request.body,"stage",-1);
        ObjectNode result = replay ? league.replay(role.id,request.type,stage,String.valueOf(request.body.get("uid1")),
                String.valueOf(request.body.get("uid2")),(int)number(request.body,"idx",0),(int)number(request.body,"page",1))
                : league.dispatch(role.id, request.type, stage,
                (int)number(request.body,"group",-1), (int)number(request.body,"rank",-1),
                String.valueOf(request.body.get("idx")),String.valueOf(request.body.get("bet")));
        output.send(request,json.convertValue(result.path("response"),BODY));
        pushChanges(role,result,output);
        return true;
    }

    /**
     * 推送已提交的比赛奖金、补寄邮件和最新报名状态。
     * @param role 已登录角色
     * @param result 事务已提交的资产和界面变更
     * @param output 消息出口
     * @throws IOException 发送失败
     */
    private void pushChanges(LocalRole role, ObjectNode result, GameReply output) throws IOException {
        if (result.has("coins")) output.push(3,data("coins",result.path("coins").asLong()));
        if (result.has("item")) {
            long n = result.path("delivered").asLong();
            if (n > 0) {
                int id = result.path("item").asInt();
                Map<String,Object> row = data("id",id,"type",inventory.itemType(id),"n",n);
                output.push(14,data("flags","league.reward","adds",data("items",Collections.singletonList(row))));
                bags.push(role.id,output);
            }
        }
        if (result.has("item") || result.has("mail")) mail.pushMailCount(role,output);
        output.push(807,json.convertValue(result.path("panel"),BODY));
    }

    /**
     * 登录后推送实际报名阶段与未领赛季奖励。
     * @param role 当前角色
     * @param output 消息出口
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushChanges(role,league.dispatch(role.id,0,-1,-1,-1,"",""),output);
    }
}
