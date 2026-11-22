package com.pokemon.offline.dojo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 道馆驻防归属、争夺战与积分结算 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:19
 */
@Repository
@DependsOn("dojoMapper")
public class DojoStationMapper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** 注入设备存档与战斗快照编码器。
     * @param jdbc 数据模板
     * @param json 快照编码器
     */
    DojoStationMapper(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 在当前事务内串行化跨角色的道馆占领、过期与交接。 */
    void lockStations() {
        jdbc.update("UPDATE local_dojo_station_lock SET id=id WHERE id=1");
    }

    /** 读取角色的当日记录、战斗或请求回执。
     * @param roleId 角色编号
     * @param key 状态键
     * @return 保存的状态
     */
    ObjectNode state(String roleId, String key) {
        return jdbc.query("SELECT payload FROM local_dojo_station_state WHERE role_id=? AND state_key=?",
                (r, i) -> decode(r.getString(1)), roleId, key).stream().findFirst().orElseGet(json::createObjectNode);
    }

    /** 写入角色状态。
     * @param roleId 角色编号
     * @param key 状态键
     * @param state 持久化快照
     */
    void save(String roleId, String key, ObjectNode state) {
        int changed = jdbc.update("UPDATE local_dojo_station_state SET payload=? WHERE role_id=? AND state_key=?",
                state.toString(), roleId, key);
        if (changed == 0) {
            jdbc.update("INSERT INTO local_dojo_station_state(role_id,state_key,payload) VALUES(?,?,?)",
                    roleId, key, state.toString());
        }
    }

    /** 读取同区服当前所有驻防快照。
     * @param realm 区服编号
     * @return 守方阵容、归属和生效时段
     */
    List<ObjectNode> stations(int realm) {
        return jdbc.query("SELECT s.*,r.name,r.sex,r.avatar,r.level FROM local_dojo_station s JOIN local_game_role r "
                        + "ON r.id=s.role_id WHERE s.realm_id=? ORDER BY s.dojo_id", (r, i) -> {
            ObjectNode row = json.createObjectNode();
            row.put("idx", r.getInt("dojo_id")).put("uid", r.getString("role_id")).put("token", r.getString("token"))
                    .put("time", r.getLong("start_at")).put("end", r.getLong("end_at")).put("name", r.getString("name"))
                    .put("sex", r.getInt("sex")).put("icon", r.getInt("avatar")).put("level", r.getInt("level"));
            row.set("team", decode(r.getString("defender")));
            return row;
        }, realm);
    }

    /** 查询角色此刻确实生效的驻防馆。
     * @param roleId 角色编号
     * @param now 当前 UTC 秒
     * @return 道馆配置号，无有效驻防时为零
     */
    public int activeStation(String roleId, long now) {
        return jdbc.query("SELECT dojo_id FROM local_dojo_station WHERE role_id=? AND end_at>?",
                (r, i) -> r.getInt(1), roleId, now).stream().findFirst().orElse(0);
    }

    /** 登记已获胜的守方战斗副本。
     * @param realm 区服
     * @param roleId 角色编号
     * @param dojo 道馆编号
     * @param token 本次驻防编号
     * @param start 驻防开始
     * @param end 驻防结束
     * @param team 完整守备编队
     */
    void occupy(int realm, String roleId, int dojo, String token, long start, long end, ObjectNode team) {
        jdbc.update("INSERT INTO local_dojo_station(realm_id,dojo_id,role_id,token,start_at,end_at,defender) "
                + "VALUES(?,?,?,?,?,?,?)", realm, dojo, roleId, token, start, end, team.toString());
    }

    /** 清除当前归属并把实际时长所得积分写入原系统邮件附件。
     * @param station 驻防记录
     * @param points 本次到期积分
     * @param now 结算时刻
     */
    void release(ObjectNode station, long points, long now) {
        String token = station.path("token").asText();
        if (jdbc.update("DELETE FROM local_dojo_station WHERE token=?", token) != 1 || points <= 0) {
            return;
        }
        jdbc.update("INSERT INTO local_system_mail(id,role_id,subject,body,created_at,expires_at,dojo_points)"
                        + " VALUES(?,?,?,?,?,?,?)", "dojo:" + token, station.path("uid").asText(), "道馆驻扎奖励",
                "本次驻扎所得道馆积分已随信送达。", now, now + 30L * 86400, points);
    }

    /** 解析持久化战报。
     * @param value JSON 原文
     * @return 完整快照
     */
    private ObjectNode decode(String value) {
        try {
            return (ObjectNode) json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("道馆驻防存档无法读取", error);
        }
    }
}
