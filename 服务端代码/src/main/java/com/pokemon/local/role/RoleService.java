package com.pokemon.local.role;

import com.pokemon.local.role.api.entity.LocalRole;
import java.util.Optional;

/**
 * 本地角色 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
public interface RoleService {

    /**
     * 读取指定账号的历史角色
     *
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @return 已创建的角色
     */
    Optional<LocalRole> findRole(String accountId, int realmId);

    /**
     * 按角色主键查询最新存档
     *
     * @param roleId 角色主键
     * @return 最新角色状态
     */
    Optional<LocalRole> findById(String roleId);

    /**
     * 按角色改名次数扣除原版钻石费用并保存新区服名片。
     *
     * @param roleId 角色主键
     * @param name 新角色名
     * @return 原协议错误码，零表示成功
     */
    int rename(String roleId, String name);

    /**
     * 锁定角色并读取最新存档，供同一事务中的资产及进度修改使用
     *
     * @param roleId 角色主键
     * @return 最新角色状态
     */
    LocalRole lockRole(String roleId);

    /**
     * 在调用方事务中保存角色场景与副本返回点
     *
     * @param roleId 角色主键
     * @param sceneId 当前场景编号
     * @param x 当前横坐标
     * @param y 当前纵坐标
     * @param returnSceneId 返回场景编号，无返回点时为空
     * @param returnX 返回横坐标
     * @param returnY 返回纵坐标
     * @return 保存后的角色状态
     */
    LocalRole updatePosition(String roleId, int sceneId, int x, int y,
                             Integer returnSceneId, Integer returnX, Integer returnY);

    /**
     * 在调用方事务中增加角色金币、钻石与经验
     *
     * @param roleId 角色主键
     * @param coins 金币增量
     * @param diamonds 钻石增量
     * @param exp 经验增量
     */
    void addCurrency(String roleId, long coins, long diamonds, long exp);

    /**
     * 为账号在当前区服创建角色，相同账号重复建角返回既有角色
     *
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @param name 请求的角色名
     * @param sex 角色性别
     * @return 成功时返回角色，名称冲突时返回空
     */
    Optional<LocalRole> register(String accountId, int realmId, String name, int sex);
}
