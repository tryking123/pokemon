package com.pokemon.offline.gateway;

import com.pokemon.offline.role.RoleAvatarService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原版头像列表、解锁和切换的单机协议适配。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:17
 */
@Service
final class AvatarGameFeature implements GameFeature {
    private static final int UNLOCK = 231;
    private static final int SELECT = 232;
    private final RoleAvatarService avatars;
    private final RoleService roles;

    /**
     * 连接头像业务与角色本地存档。
     *
     * @param avatars 头像服务
     * @param roles 角色存档服务
     */
    AvatarGameFeature(RoleAvatarService avatars, RoleService roles) {
        this.avatars = avatars;
        this.roles = roles;
    }

    /**
     * 处理角色的头像解锁或佩戴请求。
     *
     * @param role 当前已认证角色
     * @param request 原头像协议
     * @param output 当前连接
     * @return 是否处理该请求
     * @throws IOException 应答或推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type != UNLOCK && request.type != SELECT) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        long index = number(request.body, "idx", 0);
        boolean unlock = request.type == UNLOCK;
        int errno = 3;
        if (index > 0 && index <= Integer.MAX_VALUE) {
            errno = unlock ? avatars.unlock((int) index) : avatars.select(role.id, (int) index);
        }
        output.send(request, data("errno", errno, "idx", index));
        if (errno == 0 && !unlock) {
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, data("icon", saved.avatar));
        }
        return true;
    }

    /**
     * 在入场时下发已解锁头像列表。
     *
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        output.push(806, data("list", avatars.unlocked()));
    }
}
