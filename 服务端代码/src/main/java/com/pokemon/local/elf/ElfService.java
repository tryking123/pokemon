package com.pokemon.local.elf;

import com.pokemon.local.elf.api.entity.LocalElf;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 初始精灵与本地队伍 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
public interface ElfService {

    /**
     * 领取初始精灵并加入第一队；重试返回已保存的选择。
     *
     * @param roleId 角色标识
     * @param configId 客户端选择的精灵配置
     * @return 生效的选择，无效时为空
     */
    Optional<LocalElf> choose(String roleId, int configId);

    /**
     * 查询角色保存的初始精灵。
     *
     * @param roleId 角色标识
     * @return 角色已领取的精灵
     */
    Optional<LocalElf> findStarter(String roleId);

    /**
     * 在奖励事务中加入一只未上阵精灵
     *
     * @param roleId 角色标识
     * @param configId 已通过配置验证的精灵编号
     * @return 新建的精灵实例
     */
    LocalElf grant(String roleId, int configId);

    /**
     * 读取角色已取得的全部精灵
     *
     * @param roleId 角色标识
     * @return 精灵及当前上阵位置
     */
    List<LocalElf> list(String roleId);

    /**
     * 读取当前角色的图鉴，并补录旧档已持有和曾进化的精灵模板。
     *
     * @param roleId 当前角色编号
     * @return 已取得的模板及未读标记
     */
    Map<Integer, Integer> handbook(String roleId);

    /**
     * 记录已经拥有的图鉴条目被查看。
     *
     * @param roleId 当前角色编号
     * @param configId 当前查看的模板编号
     * @return 是否为已取得的条目
     */
    boolean readHandbook(String roleId, int configId);

    /**
     * 在第一队内移动精灵，目标已占用时交换两只精灵的战斗阵位。
     *
     * @param roleId 角色编号
     * @param elfId 上阵精灵实例编号
     * @param team 队伍编号
     * @param position 战斗阵位（一至六）
     * @return 是否已保存指定阵位
     */
    boolean move(String roleId, String elfId, int team, int position);

    /**
     * 保存已校验的精灵技能栏。
     *
     * @param roleId 当前角色
     * @param elfId 所属精灵编号
     * @param equip 是否上阵技能
     * @param skill 本次操作的技能编号
     * @param replaced 将替换的技能编号，零为追加
     * @return 原协议错误码及生效的技能列表
     */
    Map<String, Object> skills(String roleId, String elfId, boolean equip, int skill, int replaced);

    /**
     * 设置第一队的上阵位置。
     *
     * @param roleId 角色标识
     * @param elfId 精灵实例编号
     * @param team 队伍编号
     * @param slot 阵容内位置
     * @return 设置是否有效且成功
     */
    boolean setSlot(String roleId, String elfId, int team, int slot);
}
