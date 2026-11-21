package com.pokemon.local.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 家园建筑存档与底包地块、模板 Mapper。
 * @author tongruiyuan
 * @since 2026-11-19 21:49
 */
@Repository
public class HomeBuildingMapper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode definitions;

    /** 载入建筑与地块配置。
     * @param jdbc 本地数据库
     * @param json 建筑配置解析器
     * @throws IOException 随包配置读取失败
     */
    public HomeBuildingMapper(JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream in = new ClassPathResource("home/architecture.json").getInputStream()) {
            definitions = json.readTree(in);
        }
    }

    /** 获取地块开放等级及可建模板。
     * @param idx 原地块编号
     * @return 地块规则
     */
    JsonNode plot(int idx) {
        return definitions.path("plots").path(Integer.toString(idx));
    }

    /** 获取指定等级的建筑规则。
     * @param sid 原建筑模板
     * @return 材料、产量或属性
     */
    JsonNode template(int sid) {
        return definitions.path("architecture").path(Integer.toString(sid));
    }

    /** 恢复原 S817 所需的全地块快照。
     * @param id 角色编号
     * @return 原 erection 列表
     */
    public List<ObjectNode> buildings(String id) {
        return jdbc.query("SELECT slot,sid,last_at FROM local_home_building WHERE role_id=? ORDER BY slot",
                (rs,n) -> json.createObjectNode().put("index", rs.getInt(1)).put("sid", rs.getInt(2))
                        .put("lasttime", rs.getLong(3)), id);
    }

    /** 按实际在建雕像合并角色战斗属性。
     * @param id 角色编号
     * @param attributes 战斗属性汇总
     */
    public void addStatues(String id, Map<Integer,Integer> attributes) {
        for (int sid : jdbc.queryForList("SELECT sid FROM local_home_building WHERE role_id=? AND sid>=5001",
                Integer.class, id)) {
            JsonNode nature = template(sid).path("nature");
            attributes.merge(nature.get(0).asInt(), nature.get(1).asInt(), Math::addExact);
        }
    }

    /** 保存一块已建或升级的地块。
     * @param id 角色编号
     * @param idx 地块
     * @param sid 建筑模板
     * @param at 上次采收的秒时间戳
     */
    void save(String id, int idx, int sid, long at) {
        jdbc.update("MERGE INTO local_home_building(role_id,slot,sid,last_at) KEY(role_id,slot) VALUES(?,?,?,?)",
                id, idx, sid, at);
    }

    /** 删除已拆除建筑。
     * @param id 角色编号
     * @param idx 地块编号
     */
    void dismantle(String id, int idx) {
        jdbc.update("DELETE FROM local_home_building WHERE role_id=? AND slot=?", id, idx);
    }
}
