package com.pokemon.offline.guild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 本地强盗及公会首领快照 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:26
 */
@Repository
@DependsOn("roleMapper")
public class GuildEncounterMapper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 注入存档模板和快照解析器。
     *
     * @param jdbc 本地存档模板
     * @param json 快照解析器
     */
    GuildEncounterMapper(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 获取当前角色已提交的石板研究等级。
     * @param roleId 当前角色编号
     * @return 石板研究等级
     */
    public int tabletLevel(String roleId) {
        return read(roleId, "archaeology").path("tabletLevel").asInt();
    }

    /**
     * 读取供整队属性冻结使用的已提交考古状态。
     * @param roleId 当前角色编号
     * @return 石盘、徽章及化石等级快照
     */
    public ObjectNode researchState(String roleId) {
        return read(roleId, "archaeology");
    }

    /**
     * 将一次有效击败地鼠记录到通行证。
     * @param roleId 当前角色编号
     */
    void defeatedMole(String roleId) {
        com.pokemon.offline.activity.PassportProgress.record(jdbc, roleId, 41, 1);
    }

    /** 初始化本地遭遇快照表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("guild/encounters.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取指定角色、档期与玩法的状态。
     *
     * @param roleId 当前角色编号
     * @param key 状态键
     * @return 持久快照，未开始时为空对象
     */
    ObjectNode read(String roleId, String key) {
        String payload = jdbc.queryForList("SELECT payload FROM local_guild_encounter_state WHERE role_id=? AND state_key=?",
                String.class, roleId, key).stream().findFirst().orElse(null);
        try {
            return payload == null ? json.createObjectNode() : (ObjectNode) json.readTree(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("本地遭遇存档损坏", failure);
        }
    }

    /**
     * 在角色锁内持久化本地遭遇结算状态。
     *
     * @param roleId 当前角色编号
     * @param key 状态键
     * @param value 新快照
     * @return 写入记录数
     */
    int write(String roleId, String key, ObjectNode value) {
        String payload = value.toString();
        int changed = jdbc.update("UPDATE local_guild_encounter_state SET payload=? WHERE role_id=? AND state_key=?",
                payload, roleId, key);
        return changed != 0 ? changed : jdbc.update(
                "INSERT INTO local_guild_encounter_state(role_id,state_key,payload) VALUES(?,?,?)", roleId, key, payload);
    }
}
