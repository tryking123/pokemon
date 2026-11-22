package com.pokemon.offline.activitylevel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 从训练家当前穿戴的六个部位重算养成评分。
 *
 * @author tongruiyuan
 * @since 2026-11-20 11:24
 */
@Service
public class TrainerEquipmentRatingService {
    private final TrainerEquipmentService equipment;
    private final RoleService roles;
    private final JsonNode rules;

    /**
     * 加载服务端和原客户端评分面板共同使用的档位配置。
     * @param equipment 穿戴部位存档
     * @param roles 最新角色等级与事务锁
     * @param json 配置读取器
     * @throws IOException 配置资源读取失败
     */
    TrainerEquipmentRatingService(TrainerEquipmentService equipment, RoleService roles, ObjectMapper json) throws IOException {
        this.equipment = equipment;
        this.roles = roles;
        try (InputStream input = new ClassPathResource("catalog/equipment-rating.json").getInputStream()) {
            rules = json.readTree(input);
        }
    }

    /**
     * 按六个部位计算穿戴覆盖率、当前可达强化的完成率和进阶覆盖率。
     * @param roleId 已登录角色编号
     * @return 原协议的分类、档位与百分比，功能未解锁时返回空列表
     */
    @Transactional
    public List<Map<String, Integer>> scores(String roleId) {
        LocalRole role = roles.lockRole(roleId);
        if (role.level < rules.get("unlockLevel").asInt()) {
            return Collections.emptyList();
        }
        int slots = rules.get("slots").asInt();
        int cap = Math.min(role.level, rules.get("enhanceCap").asInt());
        int worn = 0;
        int enhancement = 0;
        int advanced = 0;
        for (AdventureEquipment item : equipment.equipped(role.id)) {
            if (item.slot >= 1 && item.slot <= slots) {
                worn++;
                enhancement += Math.max(0, Math.min(item.level, cap));
                advanced += item.advanced ? 1 : 0;
            }
        }
        List<Map<String, Integer>> rows = new ArrayList<>(3);
        rows.add(row(1, worn * 100 / slots));
        rows.add(row(2, enhancement * 100 / (slots * cap)));
        rows.add(row(3, advanced * 100 / slots));
        return rows;
    }

    /**
     * 生成原评分页的一行，进度达到档位下限时立即升档。
     * @param id 分类编号
     * @param percent 百分比进度
     * @return 原协议 prop_t 字段
     */
    private Map<String, Integer> row(int id, int percent) {
        int grade = 0;
        for (JsonNode threshold : rules.get("gradeMinimums")) {
            if (percent >= threshold.asInt()) {
                grade++;
            }
        }
        Map<String, Integer> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("type", grade);
        row.put("value", percent);
        return row;
    }
}
