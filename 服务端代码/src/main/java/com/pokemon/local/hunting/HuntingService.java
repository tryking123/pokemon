package com.pokemon.local.hunting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 普通场与高级狩猎场的每日候选精灵 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 17:29
 */
@Service
public class HuntingService {
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int[][] pools;

    /**
     * 读取固定底包两场各六格的精灵、权重对。
     *
     * @param roles 角色存档锁
     * @param jdbc 数据库访问模板
     * @param json 配置及存档编码器
     * @throws IOException 配置无法读取
     */
    HuntingService(RoleService roles, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.roles = roles;
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream stream = new ClassPathResource("hunting/pools.json").getInputStream()) {
            pools = json.readValue(stream, int[][].class);
        }
    }

    /** 初始化按角色保存的每日狩猎场候选池。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("hunting/schema.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 同日返回已保存的十二格模板，北京时间四点后首次访问时轮换。
     *
     * @param roleId 当前角色编号
     * @return 前六格普通场、后六格高级场的模板编号
     */
    @Transactional
    public List<Integer> candidates(String roleId) {
        roles.lockRole(roleId);
        return candidatesForDay(roleId, day());
    }

    /** 计算北京时间四点切换的业务日。
     * @return 业务日编号
     */
    static long day() {
        return LocalDateTime.now(ZoneOffset.ofHours(8)).minusHours(4).toLocalDate().toEpochDay();
    }

    /** 读取动作开始时的当日候选。
     * @param roleId 已持锁的角色编号
     * @param day 业务日
     * @return 两场共十二格的模板
     */
    List<Integer> candidatesForDay(String roleId, long day) {
        List<String> stored = jdbc.queryForList("SELECT pool_json FROM local_hunting_day WHERE role_id=? AND day_number=?",
                String.class, roleId, day);
        try {
            List<Integer> result = new ArrayList<>();
            if (!stored.isEmpty()) {
                for (int sid : json.readValue(stored.get(0), int[].class)) {
                    result.add(sid);
                }
                return result;
            }
            for (int[] pool : pools) {
                result.add(draw(pool));
            }
            String encoded = json.writeValueAsString(result);
            if (jdbc.update("UPDATE local_hunting_day SET day_number=?,pool_json=? WHERE role_id=?", day, encoded, roleId) == 0) {
                jdbc.update("INSERT INTO local_hunting_day(role_id,day_number,pool_json) VALUES(?,?,?)", roleId, day, encoded);
            }
            return result;
        } catch (IOException invalid) {
            throw new IllegalStateException("狩猎场候选池存档解析失败", invalid);
        }
    }

    /**
     * 按本格原表实际总权重独立抽取一只精灵。
     *
     * @param pool 依次交替排列的模板编号、权重
     * @return 抽中的模板编号
     */
    int draw(int[] pool) {
        int total = 0;
        for (int i = 1; i < pool.length; i += 2) {
            total += pool[i];
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < pool.length; i += 2) {
            pick -= pool[i + 1];
            if (pick < 0) {
                return pool[i];
            }
        }
        throw new IllegalStateException("狩猎场权重不完整");
    }
}
