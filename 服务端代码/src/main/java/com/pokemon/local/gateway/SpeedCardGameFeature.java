package com.pokemon.local.gateway;

import com.pokemon.local.payment.SpeedCardService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 超级加速特权卡的原界面查询与每日领取。
 * @author tongruiyuan
 * @since 2026-11-19 21:23
 */
@Service
final class SpeedCardGameFeature implements GameFeature {
    private final SpeedCardService cards;
    private final InventoryGameFeature inventory;

    /**
     * 绑定已存档的特权和背包。
     * @param cards 特权卡业务
     * @param inventory 背包和邮件推送
     */
    SpeedCardGameFeature(SpeedCardService cards, InventoryGameFeature inventory) {
        this.cards = cards;
        this.inventory = inventory;
    }

    /**
     * 查询卡的实际剩余时间或结算当日日奖。
     * @param role 当前登录角色
     * @param request 原客户端请求
     * @param output 原协议响应和到账通知
     * @return 是否已处理
     * @throws IOException 连接发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 291 && request.type != 292) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        if (request.type == 291) {
            output.send(request, data("errno", 0, "info", cards.panel(role.id)));
            return true;
        }
        Map<Integer, Long> received = cards.claim(role.id);
        output.send(request, data("errno", received == null ? 1 : 0));
        if (received != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            received.forEach((id, amount) -> items.add(data("id", id, "n", amount)));
            if (!items.isEmpty()) {
                output.push(14, data("flags", "privilege.daily", "adds", data("items", items)));
            }
            inventory.pushBag(role, output);
            inventory.pushMailCount(role, output);
        }
        return true;
    }
}
