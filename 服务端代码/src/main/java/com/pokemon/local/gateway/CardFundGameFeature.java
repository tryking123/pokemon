package com.pokemon.local.gateway;

import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.CardFundService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 福利卡和成长基金在原客户端中的查询、领取和状态协议。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:49
 */
@Service
final class CardFundGameFeature implements GameFeature {
    private final CardFundService benefits;
    private final RoleService roles;
    private final InventoryService inventory;
    private final BagSnapshot bags;

    /**
     * 绑定福利资格、角色钱包和背包同步。
     *
     * @param benefits 福利卡和基金结算服务
     * @param roles 角色钱包服务
     * @param inventory 道具展示类型
     * @param bags 背包同步
     */
    CardFundGameFeature(CardFundService benefits, RoleService roles, InventoryService inventory, BagSnapshot bags) {
        this.benefits = benefits;
        this.roles = roles;
        this.inventory = inventory;
        this.bags = bags;
    }

    /**
     * 响应原客户端的卡与基金查询和领取操作。
     *
     * @param role 已认证角色
     * @param request 原客户端请求
     * @param output 协议回复与推送通道
     * @return 是否处理了请求
     * @throws IOException 读取或发送协议消息失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type < 157 || request.type > 160) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 157) {
            output.send(request, data("errno", 0, "status", benefits.fundActive(role.id) ? 1 : 0,
                    "info", benefits.fundInfo(role.id)));
        } else if (request.type == 159) {
            output.send(request, data("errno", 0, "info", benefits.cardInfo(role.id)));
        } else {
            boolean fund = request.type == 158;
            long index = number(request.body, "idx", 0);
            CardFundService.Claim claim = fund ? benefits.claimFund(role.id, index) : benefits.claimCard(role.id, index);
            output.send(request, data("errno", claim.errno, "idx", index));
            if (claim.errno == 0) {
                pushClaim(role, output, claim, fund);
            }
        }
        return true;
    }

    /**
     * 同步原活动菜单所需的基金状态。
     *
     * @param role 已认证角色
     * @param output 协议推送通道
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(443, data("all", benefits.fundFinished(role.id), "flag", benefits.fundActive(role.id) ? 1 : 0));
    }

    /**
     * 按实际到账推送钱包、物品和状态。
     *
     * @param role 已认证角色
     * @param output 协议推送通道
     * @param claim 已提交的领取结果
     * @param fund 本次是否是成长基金
     * @throws IOException 发送失败
     */
    private void pushClaim(LocalRole role, GameReply output, CardFundService.Claim claim, boolean fund) throws IOException {
        Map<String, Object> adds = data("diamonds", claim.diamonds);
        if (claim.item != 0) {
            adds.put("items", Collections.singletonList(data("id", claim.item, "n", 1)));
            bags.push(role.id, output);
        }
        output.push(14, data("flags", fund ? "fund.claim" : "monthcard.claim", "adds", adds, "loses", data()));
        output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
        if (fund) {
            synchronize(role, output);
        }
    }
}
