package com.pokemon.offline.role;

import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地训练师头像 Service，提供全部原头像并保存当前佩戴。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:17
 */
@Service
public class RoleAvatarService {
    private final RoleService roles;
    private final RoleMapper mapper;
    private final Map<Integer, Integer> genders = new TreeMap<>();

    /**
     * 装载原版头像编号与佩戴性别条件。
     *
     * @param roles 角色存档与事务锁
     * @param mapper 头像存档
     * @throws IOException 配置无法读取
     */
    public RoleAvatarService(RoleService roles, RoleMapper mapper) throws IOException {
        this.roles = roles;
        this.mapper = mapper;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("role/avatars.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.isEmpty()) {
                    String[] fields = line.split("\t");
                    genders.put(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
                }
            }
        }
    }

    /**
     * 返回默认已拥有的原头像编号。
     *
     * @return 已拥有头像编号
     */
    public List<Integer> unlocked() {
        return new ArrayList<>(genders.keySet());
    }

    /**
     * 判断原解锁请求对应的头像是否已经默认拥有。
     *
     * @param avatarId 头像配置编号
     * @return 原版错误码，零表示已拥有
     */
    public int unlock(int avatarId) {
        return genders.containsKey(avatarId) ? 0 : 3;
    }

    /**
     * 按原头像表的性别条件保存当前头像。
     *
     * @param roleId 当前角色编号
     * @param avatarId 目标头像编号
     * @return 原版错误码，零表示已佩戴
     */
    @Transactional
    public int select(String roleId, int avatarId) {
        LocalRole role = roles.lockRole(roleId);
        Integer gender = genders.get(avatarId);
        if (gender == null || (gender != 3 && gender != role.sex + 1)) {
            return 27;
        }
        mapper.selectAvatar(roleId, avatarId);
        return 0;
    }

}
