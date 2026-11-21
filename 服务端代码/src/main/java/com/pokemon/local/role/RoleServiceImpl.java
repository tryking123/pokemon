package com.pokemon.local.role;

import com.pokemon.local.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地角色 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@Service
public class RoleServiceImpl implements RoleService {

    private static final Pattern ROLE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final int RENAME_LIMIT = 999;
    private static final int RENAME_COST_STEP = 10000;

    private final RoleMapper roles;
    private final RoleLevels levels;
    private final int initialScene;
    private final int initialX;
    private final int initialY;
    private final int adminLevel;
    private final int adminStage;

    /**
     * 初始化本地角色服务
     *
     * @param roles 本地角色 Mapper
     * @param levels 原客户端角色经验配置
     * @param initialScene 新角色出生场景
     * @param initialX 出生点横坐标
     * @param initialY 出生点纵坐标
     * @param adminLevel 本地 admin 新角色的初始等级
     * @param adminStage 本地 admin 新角色的初始晋升等阶
     */
    public RoleServiceImpl(RoleMapper roles, RoleLevels levels,
                           @Value("${POKEMON_INITIAL_SCENE:71002}") int initialScene,
                           @Value("${POKEMON_INITIAL_X:832}") int initialX,
                           @Value("${POKEMON_INITIAL_Y:1056}") int initialY,
                           @Value("${POKEMON_ADMIN_START_LEVEL:1}") int adminLevel,
                           @Value("${POKEMON_ADMIN_START_STAGE:2}") int adminStage) {
        if (!levels.supports(adminLevel) || adminStage < 0 || adminStage > 2) {
            throw new IllegalArgumentException("admin 初始等级或训练师等阶不在原版配置范围内");
        }
        this.roles = roles;
        this.levels = levels;
        this.initialScene = initialScene;
        this.initialX = initialX;
        this.initialY = initialY;
        this.adminLevel = adminLevel;
        this.adminStage = adminStage;
    }

    /**
     * 将旧逻辑留在同一级的经验兑现为正确等级和级内余额
     */
    @PostConstruct
    public void normalizeExistingExperience() {
        for (LocalRole role : roles.findExperiencedRoles()) {
            RoleLevels.Progression progression = levels.advance(role.stage, role.level, role.exp, 0);
            if (progression.level != role.level || progression.exp != role.exp) {
                roles.updateProgression(role.id, progression.level, progression.exp);
            }
        }
    }

    /**
     * 查询已经创建的角色
     *
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @return 已创建的角色
     */
    @Override
    public Optional<LocalRole> findRole(String accountId, int realmId) {
        return roles.findRole(accountId, realmId);
    }

    /**
     * 按角色主键查询最新存档
     *
     * @param roleId 角色主键
     * @return 最新角色状态
     */
    @Override
    public Optional<LocalRole> findById(String roleId) {
        return roles.findById(roleId, false);
    }

    /**
     * 按 variable_proto[2051] 校验次数和扣除钻石，原名重试直接返回成功。
     *
     * @param roleId 角色主键
     * @param name 新角色名
     * @return 原协议错误码，零表示成功
     */
    @Override
    @Transactional
    public int rename(String roleId, String name) {
        if (name == null || !ROLE_NAME.matcher(name).matches()) {
            return 251503;
        }
        LocalRole role = lockRole(roleId);
        if (role.name.equals(name)) {
            return 0;
        }
        if (role.renameCount >= RENAME_LIMIT) {
            return 130;
        }
        int price = RENAME_COST_STEP * Math.min(10, role.renameCount + 1);
        try {
            return roles.rename(roleId, name, price) == 1 ? 0 : 100006;
        } catch (DuplicateKeyException duplicate) {
            return 4;
        }
    }

    /**
     * 在业务事务中锁定角色记录
     *
     * @param roleId 角色主键
     * @return 锁定的角色状态
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalRole lockRole(String roleId) {
        return roles.findById(roleId, true).orElseThrow(() -> new IllegalArgumentException("本地角色不存在"));
    }

    /**
     * 保存角色当前位置与返回点
     *
     * @param roleId 角色主键
     * @param sceneId 当前场景编号
     * @param x 当前横坐标
     * @param y 当前纵坐标
     * @param returnSceneId 返回场景编号
     * @param returnX 返回横坐标
     * @param returnY 返回纵坐标
     * @return 保存后的角色状态
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalRole updatePosition(String roleId, int sceneId, int x, int y,
                                    Integer returnSceneId, Integer returnX, Integer returnY) {
        if (roles.updatePosition(roleId, sceneId, x, y, returnSceneId, returnX, returnY) != 1) {
            throw new IllegalArgumentException("本地角色不存在");
        }
        return roles.findById(roleId, false).get();
    }

    /**
     * 增加角色货币与经验，用于组装原子领奖和结算
     *
     * @param roleId 角色主键
     * @param coins 金币增量
     * @param diamonds 钻石增量
     * @param exp 经验增量
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addCurrency(String roleId, long coins, long diamonds, long exp) {
        if (coins < 0 || diamonds < 0 || exp < 0) {
            throw new IllegalArgumentException("角色奖励不能为负数");
        }
        LocalRole existing = lockRole(roleId);
        RoleLevels.Progression progression = levels.advance(existing.stage, existing.level, existing.exp, exp);
        if (roles.addCurrency(roleId, coins, diamonds, progression.level, progression.exp) != 1) {
            throw new IllegalArgumentException("本地角色不存在");
        }
    }

    /**
     * 创建角色并读取提交前的初始快照
     *
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @param name 角色名
     * @param sex 角色性别
     * @return 已创建的角色，名称冲突时为空
     */
    @Override
    @Transactional
    public Optional<LocalRole> register(String accountId, int realmId, String name, int sex) {
        Optional<LocalRole> existing = roles.findRole(accountId, realmId);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            roles.insert(UUID.randomUUID().toString(), accountId, realmId, name, sex,
                    initialScene, initialX, initialY, Instant.now().getEpochSecond(), adminLevel, adminStage);
        } catch (DuplicateKeyException duplicate) {
            // 并发请求先完成时，优先按所属账号读取角色。
            return roles.findRole(accountId, realmId);
        }
        return roles.findRole(accountId, realmId);
    }
}
