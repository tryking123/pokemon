package com.pokemon.offline.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.inventory.api.vo.ProfileCleanupVO;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 人物资料页精灵、背包物品及邮箱清除 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-22 01:21
 */
@Service
public class ProfileCleanupService {
    private final JdbcTemplate jdbc;
    private final InventoryMapper inventory;
    private final ObjectMapper json;

    /**
     * 绑定同一角色事务中的存档、角色锁和结构化数据工具。
     *
     * @param jdbc 应用私有数据库
     * @param inventory 角色资产锁
     * @param json 结构化存档读写工具
     */
    public ProfileCleanupService(JdbcTemplate jdbc, InventoryMapper inventory, ObjectMapper json) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.json = json;
    }

    /** 初始化清除操作的持久化重试凭据。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("inventory/profile-cleanup.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 原子清除当前角色选择的资产，并阻止同一请求删除后来获得的资产。
     *
     * @param roleId 已验证会话中的角色编号
     * @param vo 清除类型与操作请求编号
     * @return 参数有效且本次操作已完成或同类型请求已处理时为真
     */
    @Transactional
    public boolean clear(String roleId, ProfileCleanupVO vo) {
        if (vo == null || vo.kind < 1 || vo.kind > 3 || vo.requestId == null
                || vo.requestId.trim().isEmpty() || vo.requestId.length() > 128) {
            return false;
        }
        inventory.lockWallet(roleId);
        List<Integer> previous = jdbc.queryForList("SELECT kind FROM local_profile_cleanup_receipt "
                + "WHERE role_id=? AND request_id=?", Integer.class, roleId, vo.requestId);
        if (!previous.isEmpty()) {
            return previous.get(0) == vo.kind;
        }
        if (vo.kind == 1) {
            clearElves(roleId);
        } else if (vo.kind == 2) {
            clearBag(roleId);
        } else {
            clearMail(roleId);
        }
        jdbc.update("INSERT INTO local_profile_cleanup_receipt(role_id,request_id,kind) VALUES(?,?,?)",
                roleId, vo.requestId, vo.kind);
        return true;
    }

    /**
     * 删除全部精灵、已装随身资产及实例占用，保留图鉴和已经结算的历史。
     *
     * @param roleId 当前角色编号
     */
    private void clearElves(String roleId) {
        for (String table : Arrays.asList("local_dojo_team", "local_elf_aid", "local_elf_follow",
                "local_travel_dispatch", "local_rocket_slot", "local_dungeon_fighter_life", "local_dojo_attempt")) {
            jdbc.update("DELETE FROM " + table + " WHERE role_id=?", roleId);
        }
        jdbc.update("DELETE FROM local_pstone_refine_pending WHERE role_id=? AND gear_id IN "
                + "(SELECT id FROM local_elf_gear WHERE role_id=? AND elf_id IS NOT NULL AND elf_id<>'')", roleId, roleId);
        jdbc.update("DELETE FROM local_elf_gear WHERE role_id=? AND elf_id IS NOT NULL AND elf_id<>''", roleId);
        jdbc.update("DELETE FROM local_secret_stone WHERE role_id=? AND elf_id IS NOT NULL AND elf_id<>''", roleId);
        jdbc.update("DELETE FROM local_arena_state WHERE role_id=? AND (state_key='attempt' OR state_key LIKE 'lineup:%')", roleId);
        jdbc.update("UPDATE local_battle_attempt SET status='FLED' WHERE role_id=? AND status='ACTIVE'", roleId);
        clearBreedingPair(roleId);
        clearTravelAssignments(roleId);
        cancelFrozenFights(roleId);
        jdbc.update("DELETE FROM local_elf WHERE role_id=?", roleId);
    }

    /**
     * 删除背包内物品、碎片与未穿戴装备，保留角色钱包及已装备资产。
     *
     * @param roleId 当前角色编号
     */
    private void clearBag(String roleId) {
        for (String table : Arrays.asList("local_inventory_item", "local_elf_chip", "local_trainer_sticker")) {
            jdbc.update("DELETE FROM " + table + " WHERE role_id=?", roleId);
        }
        String unequipped = "SELECT i.id FROM local_adventure_equipment_instance i WHERE i.role_id=? "
                + "AND NOT EXISTS (SELECT 1 FROM local_trainer_equipped e WHERE e.equipment_id=i.id)";
        jdbc.update("DELETE FROM local_trainer_refine_pending WHERE role_id=? AND equipment_id IN (" + unequipped + ")", roleId, roleId);
        jdbc.update("DELETE FROM local_trainer_attributes WHERE equipment_id IN (" + unequipped + ")", roleId);
        // 旧表仍会在启动迁移中补回实例，因此同时删除对应的旧背包装备。
        jdbc.update("DELETE FROM local_adventure_equipment WHERE role_id=? AND id IN (" + unequipped + ")", roleId, roleId);
        jdbc.update("DELETE FROM local_adventure_equipment_instance WHERE id IN (" + unequipped + ")", roleId);
        jdbc.update("DELETE FROM local_pstone_refine_pending WHERE role_id=? AND gear_id IN "
                + "(SELECT id FROM local_elf_gear WHERE role_id=? AND (elf_id IS NULL OR elf_id=''))", roleId, roleId);
        jdbc.update("DELETE FROM local_elf_gear WHERE role_id=? AND (elf_id IS NULL OR elf_id='')", roleId);
        jdbc.update("DELETE FROM local_secret_stone WHERE role_id=? AND (elf_id IS NULL OR elf_id='')", roleId);
    }

    /**
     * 丢弃所有邮件附件并使邮件立即不可见，保留邮件编号以阻止相同来源补寄。
     *
     * @param roleId 当前角色编号
     */
    private void clearMail(String roleId) {
        jdbc.update("DELETE FROM local_system_mail_reward WHERE mail_id IN "
                + "(SELECT id FROM local_system_mail WHERE role_id=?)", roleId);
        jdbc.update("UPDATE local_system_mail SET expires_at=0,opened=TRUE,claimed=TRUE,wallet_claimed=TRUE,"
                + "claimed_quantity=quantity,coins=0,diamonds=0,dojo_points=0 WHERE role_id=?", roleId);
    }

    /**
     * 解除饲育屋精灵寄存，保留已孵化宠物与已有蛋。
     *
     * @param roleId 当前角色编号
     */
    private void clearBreedingPair(String roleId) {
        for (String saved : jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?", String.class, roleId)) {
            ObjectNode state = readState(saved);
            state.remove(Arrays.asList("pair", "origin", "start", "group"));
            jdbc.update("UPDATE local_home_breeding SET state_json=? WHERE role_id=?", state.toString(), roleId);
        }
    }

    /**
     * 取消被清除精灵的在途旅行与待续战斗，保留旅行次数和已结算进度。
     *
     * @param roleId 当前角色编号
     */
    private void clearTravelAssignments(String roleId) {
        for (String saved : jdbc.queryForList("SELECT payload FROM local_travel_state WHERE role_id=?", String.class, roleId)) {
            ObjectNode state = readState(saved);
            for (com.fasterxml.jackson.databind.JsonNode row : state.path("travel")) {
                if (row instanceof ObjectNode && row.has("uid")) {
                    ((ObjectNode) row).remove(Arrays.asList("uid", "opemtime", "speed_time"));
                }
            }
            state.remove(Arrays.asList("fight", "eventBattle", "eventPicture", "num_tab"));
            jdbc.update("UPDATE local_travel_state SET payload=? WHERE role_id=?", state.toString(), roleId);
        }
    }

    /**
     * 丢弃各本地玩法尚未结算的战斗快照，避免重新进入后继续使用已删除精灵。
     *
     * @param roleId 当前角色编号
     */
    private void cancelFrozenFights(String roleId) {
        for (String table : Arrays.asList("local_nest_state", "local_secret_state", "local_tower_state",
                "local_space_progress", "local_eden_state", "local_dailydup_state", "local_orange_state")) {
            for (String saved : jdbc.queryForList("SELECT state_json FROM " + table + " WHERE role_id=?", String.class, roleId)) {
                ObjectNode state = readState(saved);
                removeFights(state);
                jdbc.update("UPDATE " + table + " SET state_json=? WHERE role_id=?", state.toString(), roleId);
            }
        }
        for (String key : Arrays.asList("dnf:season", "rocket:match")) {
            for (String saved : jdbc.queryForList("SELECT state_data FROM local_arena_state WHERE role_id=? AND state_key=?",
                    String.class, roleId, key)) {
                ObjectNode state = readState(saved);
                removeFights(state);
                jdbc.update("UPDATE local_arena_state SET state_data=? WHERE role_id=? AND state_key=?", state.toString(), roleId, key);
            }
        }
    }

    /**
     * 移除对象及其子对象中的当前战斗，保留已结算进度和历史回执。
     *
     * @param state 待处理的玩法状态
     */
    private void removeFights(com.fasterxml.jackson.databind.JsonNode state) {
        if (state instanceof ObjectNode) {
            ((ObjectNode) state).remove("fight");
        }
        state.elements().forEachRemaining(this::removeFights);
    }

    /**
     * 读取已保存对象；损坏时抛出异常，使整个清除事务回滚。
     *
     * @param saved 原始存档文本
     * @return 结构化存档对象
     */
    private ObjectNode readState(String saved) {
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = json.readTree(saved);
            if (!(parsed instanceof ObjectNode)) {
                throw new IllegalStateException("清除操作所需关联存档不是对象");
            }
            return (ObjectNode) parsed;
        } catch (IOException ex) {
            throw new IllegalStateException("清除操作所需关联存档不能解析", ex);
        }
    }
}
