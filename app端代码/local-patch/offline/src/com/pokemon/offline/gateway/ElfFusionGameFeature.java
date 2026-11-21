package com.pokemon.offline.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfFusionService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原精灵合体页及本地合体器补给请求。
 * @author tongruiyuan
 * @since 2026-11-20 13:18
 */
@Service
final class ElfFusionGameFeature implements GameFeature {
    private final ElfFusionService fusion;
    private final BattleGameFeature battle;
    private final BagSnapshot bags;
    private final RoleService roles;
    private final Map<GameReply,String> sessions = Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定合体、资产与原面板推送。
     * @param fusion 合体服务
     * @param battle 精灵快照
     * @param bags 背包和未读邮件
     * @param roles 角色钱包
     */
    ElfFusionGameFeature(ElfFusionService fusion, BattleGameFeature battle, BagSnapshot bags, RoleService roles) {
        this.fusion = fusion;
        this.battle = battle;
        this.bags = bags;
        this.roles = roles;
    }

    /** 绑定原请求并推送已提交的资产变更。
     * @param role 当前角色
     * @param request 原客户端请求
     * @param out 当前协议通道
     * @return 是否处理了该请求
     * @throws IOException 协议发送失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply out) throws IOException {
        if (request.type != 5100 && request.type != 10990) return false;
        if (request.session <= 0) return true;
        String receipt = sessions.computeIfAbsent(out, ignored -> UUID.randomUUID().toString()) + ":" + request.session;
        if (request.type == 5100) {
            Object raw = request.body.get("uid_tab");
            int errno = fusion.fuse(role.id, (int)number(request.body,"elfid",0), raw instanceof List ? (List<?>)raw : null, receipt);
            if (errno == 0) {
                bags.push(role.id,out);
                battle.pushElfs(role.id,out);
            }
            out.send(request,data("errno",errno));
            return true;
        }
        ObjectNode result = fusion.supply(role.id,(int)number(request.body,"action",0),receipt);
        if (result.path("errno").asInt() == 0) {
            bags.push(role.id,out);
            bags.pushMailCount(role.id,out);
            out.push(3,data("diamonds",roles.findById(role.id).orElse(role).diamonds));
        }
        out.send(request,data("errno",result.get("errno").asInt(),"message",result.get("message").asText()));
        return true;
    }
}
