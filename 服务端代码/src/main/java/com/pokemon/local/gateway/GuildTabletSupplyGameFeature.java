package com.pokemon.local.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.guild.PersonalGuildService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 公会研究员一次性石盘材料邮件供给协议。
 *
 * @author tongruiyuan
 * @since 2026-11-20 23:32
 */
@Service
final class GuildTabletSupplyGameFeature implements GameFeature {
    private final PersonalGuildService guilds;
    private final BagSnapshot bags;

    /**
     * 连接认证角色业务与真实邮箱角标。
     * @param guilds 公会研究业务
     * @param bags 邮箱状态推送
     */
    GuildTabletSupplyGameFeature(PersonalGuildService guilds, BagSnapshot bags) {
        this.guilds = guilds;
        this.bags = bags;
    }

    /**
     * 处理当前连接角色的材料申请，不接受外部收件角色编号。
     * @param role 当前已认证角色
     * @param request 本地研究员申请
     * @param output 原游戏连接
     * @return 是否已处理该协议
     * @throws IOException 推送邮箱或应答失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 10993) return false;
        if (request.session <= 0) return true;
        ObjectNode result = guilds.supplyTabletMail(role.id, (int) number(request.body, "action", 0));
        if (result.path("errno").asInt() == 0) bags.pushMailCount(role.id, output);
        output.send(request, data("errno", result.path("errno").asInt(), "message", result.path("message").asText()));
        return true;
    }
}
