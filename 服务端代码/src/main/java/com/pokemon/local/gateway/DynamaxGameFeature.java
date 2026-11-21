package com.pokemon.local.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.DynamaxService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 极巨化养成及原精灵、背包与资产面板同步。
 * @author tongruiyuan
 * @since 2026-11-20 11:03
 */
@Service
final class DynamaxGameFeature implements GameFeature {
    private final DynamaxService service;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final RoleService roles;
    private final ObjectMapper json;
    private final Map<GameReply,String> sessions=new WeakHashMap<>();

    /** 绑定极巨化、精灵和背包推送。
     * @param service 养成服务
     * @param bags 背包
     * @param battle 精灵同步
     * @param roles 角色钱包
     * @param json 协议转换
     */
    DynamaxGameFeature(DynamaxService service,BagSnapshot bags,BattleGameFeature battle,RoleService roles,ObjectMapper json) {
        this.service=service; this.bags=bags; this.battle=battle; this.roles=roles; this.json=json;
    }

    /** 办理原客户端五种极巨化动作。
     * @param role 当前角色
     * @param req 请求
     * @param out 返回连接
     * @return 是否处理
     * @throws IOException 写入失败
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean handle(LocalRole role,SprotoProtocol.Request req,GameReply out) throws IOException {
        if (req.type<312 || req.type>316 || req.session<=0) { return false; }
        String key;
        synchronized(sessions) { key=sessions.computeIfAbsent(out,ignored->UUID.randomUUID().toString())+":"+req.session; }
        ObjectNode result=service.perform(role.id,req.type,req.body,key);
        if (result.path("errno").asInt()==0) {
            bags.push(role.id,out);
            LocalRole current=roles.findById(role.id).orElse(role);
            out.push(3,data("diamonds",current.diamonds,"coins",current.coins));
        }
        out.send(req,json.convertValue(result,Map.class));
        if (result.path("errno").asInt()==0) { battle.pushElfs(role.id,out); }
        return true;
    }
}
