package com.pokemon.offline.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 竞技训练的角色专属存档 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:07
 */
@Repository
@DependsOnDatabaseInitialization
class ArenaMapper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 绑定训练存档数据库与编码器。
     *
     * @param jdbc 数据库访问模板
     * @param json JSON 编码器
     */
    ArenaMapper(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 创建训练存档表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-arena.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取一个存档组。
     *
     * @param roleId 角色编号
     * @param key 存档组键
     * @return 该组数据，首次访问时返回空对象
     */
    ObjectNode load(String roleId, String key) {
        List<String> rows = jdbc.queryForList("SELECT state_data FROM local_arena_state WHERE role_id=? AND state_key=?",
                String.class, roleId, key);
        if (rows.isEmpty()) {
            return json.createObjectNode();
        }
        try {
            return (ObjectNode) json.readTree(rows.get(0));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("竞技训练存档解析失败", invalid);
        }
    }

    /**
     * 在角色锁下写入一个存档组。
     *
     * @param roleId 角色编号
     * @param key 存档组键
     * @param state 该组的新状态
     */
    void save(String roleId, String key, JsonNode state) {
        jdbc.update("MERGE INTO local_arena_state(role_id,state_key,state_data) KEY(role_id,state_key) VALUES (?,?,?)",
                roleId, key, state.toString());
    }

    /**
     * 移除战斗尝试或过期回执。
     *
     * @param roleId 角色编号
     * @param key 存档组键
     */
    void remove(String roleId, String key) {
        jdbc.update("DELETE FROM local_arena_state WHERE role_id=? AND state_key=?", roleId, key);
    }
}
