package com.pokemon.offline.elf;

import com.pokemon.offline.dojo.DojoService;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 初始精灵与本地队伍 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
@Service
public class ElfServiceImpl implements ElfService {

    private static final int STARTER_ITEM = 10020;
    private static final int PIKACHU = 31016;

    private final RoleService roles;
    private final ElfMapper mapper;
    private final InventoryService inventory;
    private final DojoService dojo;

    /**
     * 初始化初始精灵服务。
     *
     * @param roles 本地角色服务
     * @param mapper 初始精灵 Mapper
     * @param inventory 普通背包服务
     * @param dojo 助阵精灵的占用状态
     */
    public ElfServiceImpl(RoleService roles, ElfMapper mapper, InventoryService inventory, DojoService dojo) {
        this.roles = roles;
        this.mapper = mapper;
        this.inventory = inventory;
        this.dojo = dojo;
    }

    /**
     * 为角色原子地领取一只初始精灵并上阵。
     *
     * @param roleId 角色标识
     * @param configId 请求选择的精灵编号
     * @return 生效的选择，非法选项为空
     */
    @Override
    @Transactional
    public Optional<LocalElf> choose(String roleId, int configId) {
        roles.lockRole(roleId);
        Optional<LocalElf> existing = mapper.find(roleId);
        if (existing.isPresent()) {
            return existing;
        }
        if (mapper.consumedStarter(roleId)) {
            return Optional.empty();
        }
        boolean hasItem = inventory.count(roleId, STARTER_ITEM) > 0;
        if (hasItem) {
            if (configId != PIKACHU || !inventory.takeOne(roleId, STARTER_ITEM)) {
                return Optional.empty();
            }
        } else if (configId != 21006 && configId != 21007 && configId != 21008) {
            return Optional.empty();
        }
        List<LocalElf> owned = mapper.list(roleId);
        int slot = 0;
        for (int candidate = 1; candidate <= 6; candidate++) {
            final int selected = candidate;
            if (owned.stream().noneMatch(elf -> elf.slot == selected)) {
                slot = candidate;
                break;
            }
        }
        LocalElf elf = newElf(configId, slot);
        mapper.insert(roleId, elf, true);
        if (slot > 0) {
            savePosition(roleId, elf, freePosition(owned, 1, slot));
        }
        return Optional.of(elf);
    }

    /**
     * 读取初始精灵及所在队伍。
     *
     * @param roleId 角色标识
     * @return 已领取的精灵
     */
    @Override
    public Optional<LocalElf> findStarter(String roleId) {
        return mapper.find(roleId);
    }

    /**
     * 保存通过玩法获得的未上阵精灵
     *
     * @param roleId 角色标识
     * @param configId 精灵配置编号
     * @return 新建精灵实例
     */
    @Override
    @Transactional
    public LocalElf grant(String roleId, int configId) {
        if (configId <= 0) {
            throw new IllegalArgumentException("精灵配置编号无效");
        }
        roles.lockRole(roleId);
        LocalElf elf = newElf(configId, 0);
        mapper.insert(roleId, elf, false);
        return elf;
    }

    /**
     * 读取角色已拥有的初始与奖励精灵
     *
     * @param roleId 角色标识
     * @return 角色精灵与队位
     */
    @Override
    public List<LocalElf> list(String roleId) {
        return mapper.list(roleId);
    }

    /**
     * 读取图鉴，并将旧档可核对的取得历史补录到持久图鉴。
     *
     * @param roleId 当前角色编号
     * @return 已取得的模板及未读标记
     */
    @Override
    @Transactional
    public Map<Integer, Integer> handbook(String roleId) {
        roles.lockRole(roleId);
        mapper.backfillHandbook(roleId);
        Map<Integer, Integer> saved = mapper.handbook(roleId);
        for (LocalElf elf : mapper.list(roleId)) {
            elf.training.path("old_elfsid").forEach(id -> {
                if (id.asInt() > 0 && !saved.containsKey(id.asInt())) {
                    mapper.discover(roleId, id.asInt());
                    saved.put(id.asInt(), 1);
                }
            });
        }
        return saved;
    }

    /**
     * 将已取得的图鉴条目标记为已读，重复点击保持相同状态。
     *
     * @param roleId 当前角色编号
     * @param configId 原图鉴精灵模板编号
     * @return 是否属于当前角色的图鉴
     */
    @Override
    @Transactional
    public boolean readHandbook(String roleId, int configId) {
        roles.lockRole(roleId);
        return configId > 0 && mapper.readHandbook(roleId, configId);
    }

    /**
     * 在第一队内移动精灵，目标已占用时交换两只精灵的战斗阵位。
     *
     * @param roleId 角色编号
     * @param elfId 上阵精灵实例编号
     * @param team 队伍编号
     * @param position 战斗阵位（一至六）
     * @return 是否已保存指定阵位
     */
    @Override
    @Transactional
    public boolean move(String roleId, String elfId, int team, int position) {
        if (team < 1 || team > 3 || position < 1 || position > 6 || elfId == null) {
            return false;
        }
        if (team > roles.lockRole(roleId).stage + 1) { return false; }
        List<LocalElf> owned = mapper.list(roleId);
        LocalElf selected = null;
        LocalElf other = null;
        for (LocalElf elf : owned) {
            if (elf.team == team && elf.slot > 0) {
                if (elf.id.equals(elfId)) {
                    selected = elf;
                }
                if (elf.position() == position) {
                    other = elf;
                }
            }
        }
        if (selected == null) {
            return false;
        }
        if (selected.position() != position) {
            if (other != null) {
                savePosition(roleId, other, selected.position());
            }
            savePosition(roleId, selected, position);
        }
        return true;
    }

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
    @Override
    @Transactional
    public Map<String, Object> skills(String roleId, String elfId, boolean equip, int skill, int replaced) {
        roles.lockRole(roleId);
        LocalElf elf = mapper.findOwned(roleId, elfId).orElse(null);
        if (elf == null) {
            return Collections.singletonMap("errno", 101005);
        }
        if (dojo.isFighting(roleId, elfId)) {
            return Collections.singletonMap("errno", 2011);
        }
        int error = ElfSkillBar.change(elf, equip, skill, replaced);
        if (error != 0) {
            return Collections.singletonMap("errno", error);
        }
        if (mapper.saveTraining(roleId, elfId, elf.configId, elf.training) != 1) {
            throw new IllegalStateException("技能栏未保存");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("onlyid", elfId);
        result.put("skillid", ElfSkillBar.equipped(elf));
        return result;
    }

    /**
     * 修改精灵上阵位置。
     *
     * @param roleId 角色标识
     * @param elfId 精灵实例编号
     * @param team 队伍编号
     * @param slot 队伍位置
     * @return 该位置是否有效且成功修改
     */
    @Override
    @Transactional
    public boolean setSlot(String roleId, String elfId, int team, int slot) {
        if (team < 1 || team > 3 || slot < 0 || slot > 6 || elfId == null) {
            return false;
        }
        if (team > roles.lockRole(roleId).stage + 1) { return false; }
        if (slot != 0 && dojo.isAiding(roleId, elfId)) {
            return false;
        }
        List<LocalElf> owned = mapper.list(roleId);
        Optional<LocalElf> selected = owned.stream().filter(elf -> elf.id.equals(elfId)).findFirst();
        if (!selected.isPresent()) {
            return false;
        }
        LocalElf target = selected.get();
        if ((target.slot > 0 && target.team != team) || (slot == 0 && target.team != team)) {
            return false;
        }
        if (slot == target.slot) {
            return true;
        }
        if (slot == 0 && owned.stream().filter(elf -> elf.team == team && elf.slot > 0).count() <= 1) {
            return false;
        }
        int position = target.position();
        if (slot != 0) {
            LocalElf occupying = owned.stream()
                    .filter(elf -> elf.team == team && elf.slot == slot).findFirst().orElse(null);
            if (occupying != null) {
                int occupiedPosition = occupying.position();
                mapper.setSlot(roleId, occupying.id, team, target.slot);
                savePosition(roleId, occupying, position);
                position = occupiedPosition;
            } else if (position == 0) {
                position = freePosition(owned, team, slot);
            }
        }
        mapper.setSlot(roleId, elfId, team, slot);
        savePosition(roleId, target, slot == 0 ? 0 : position);
        return true;
    }

    /**
     * 优先使用与展示位同号的空阵位。
     *
     * @param owned 角色的精灵列表
     * @param team 所属普通编队
     * @param preferred 首选阵位
     * @return 尚未被队友占用的阵位
     */
    private int freePosition(List<LocalElf> owned, int team, int preferred) {
        boolean[] used = new boolean[7];
        for (LocalElf elf : owned) {
            if (elf.team == team && elf.position() >= 1 && elf.position() <= 6) {
                used[elf.position()] = true;
            }
        }
        if (!used[preferred]) {
            return preferred;
        }
        for (int position = 1; position <= 6; position++) {
            if (!used[position]) {
                return position;
            }
        }
        throw new IllegalStateException("队伍没有空阵位");
    }

    /**
     * 在角色锁内保存阵位并保留其他培养字段。
     *
     * @param roleId 角色编号
     * @param elf 精灵存档
     * @param position 新阵位，零表示下阵
     */
    private void savePosition(String roleId, LocalElf elf, int position) {
        if (position == 0) {
            elf.training.remove("formationPosition");
        } else {
            elf.training.put("formationPosition", position);
        }
        mapper.saveTraining(roleId, elf.id, elf.configId, elf.training);
    }

    /**
     * 创建扣除前置校验后可存档的一级精灵
     *
     * @param configId 精灵配置编号
     * @param slot 初始上阵槽位
     * @return 以服务器唯一编号标识的精灵
     */
    private LocalElf newElf(int configId, int slot) {
        return new LocalElf("EL00" + UUID.randomUUID().toString().replace("-", ""), configId, 1, 1, slot);
    }
}
