package com.pokemon.offline.gateway;

import com.pokemon.offline.inventory.ProfileCleanupService;
import com.pokemon.offline.inventory.api.vo.ProfileCleanupVO;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 人物资料页清除操作的进程内协议入口，始终使用当前已登录角色。
 *
 * @author tongruiyuan
 * @since 2026-11-22 01:25
 */
@Service
final class ProfileCleanupGameFeature implements GameFeature {
    private final ProfileCleanupService cleanup;

    /**
     * 绑定本地角色资产清除业务。
     * @param cleanup 事务清除业务
     */
    ProfileCleanupGameFeature(ProfileCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    /**
     * 分派本人资产清除，提交后由客户端重新进入角色恢复完整快照。
     * @param role 当前登录角色
     * @param request 清除类型和幂等请求编号
     * @param output 原协议响应通道
     * @return 是否处理本请求
     * @throws IOException 响应编码失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != 10994 || request.session <= 0) return false;
        long kind = number(request.body, "kind", 0);
        Object key = request.body.get("requestId");
        boolean valid = kind >= 1 && kind <= 3 && key instanceof String
                && cleanup.clear(role.id, new ProfileCleanupVO((int) kind, (String) key));
        output.send(request, data("errno", valid ? 0 : 100021, "kind", kind));
        return true;
    }
}
