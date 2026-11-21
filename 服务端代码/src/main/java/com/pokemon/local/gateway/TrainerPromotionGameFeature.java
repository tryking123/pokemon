package com.pokemon.local.gateway;

import com.pokemon.local.role.TrainerPromotionService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 原训练师升阶协议及确认后的角色与背包同步。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:32
 */
@Service
final class TrainerPromotionGameFeature implements GameFeature {
    private final TrainerPromotionService promotions;
    private final BagSnapshot bags;
    private final Map<GameReply, String> sessions = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 连接升阶业务及背包推送。
     *
     * @param promotions 训练师升阶
     * @param bags 背包快照
     */
    TrainerPromotionGameFeature(TrainerPromotionService promotions, BagSnapshot bags) {
        this.promotions = promotions;
        this.bags = bags;
    }

    /**
     * 将原 C5090 请求交给角色业务处理。
     *
     * @param role 当前角色
     * @param request 原客户端请求
     * @param output 回环应答通道
     * @return 是否为训练师升阶请求
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 5090) {
            return false;
        }
        if (request.session > 0) {
            String receipt = sessions.computeIfAbsent(output, ignored -> UUID.randomUUID().toString()) + ":" + request.session;
            LocalRole promoted = promotions.promote(role.id, receipt);
            if (promoted != null) {
                bags.push(role.id, output);
                output.push(3, data("stage", promoted.stage, "coins", promoted.coins));
            }
            output.send(request, data("errno", promoted == null ? 1 : 0));
        }
        return true;
    }
}
