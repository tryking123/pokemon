package com.pokemon.local.eden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.activity.ActivityService;
import java.time.Instant;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 神兽岛三岛原生预览与目标身份校验。
 * @author tongruiyuan
 * @since 2026-11-19 18:17
 */
@Service
public class EdenService {
    private final JsonNode islands;
    private final JsonNode actors;
    private final ActivityService activities;

    /**
     * 加载原岛屿普通怪物和神兽权重。
     * @param json 资源读取器
     * @param activities 冒险卡片开放窗口
     * @throws IOException 岛屿配置不可用
     */
    public EdenService(ObjectMapper json, ActivityService activities) throws IOException {
        this.activities = activities;
        try (InputStream input = new ClassPathResource("eden/islands.json").getInputStream()) {
            islands = json.readTree(input);
        }
        try (InputStream input = new ClassPathResource("eden/actors.json").getInputStream()) {
            actors = json.readTree(input);
        }
    }

    /**
     * 按角色及日期固定三岛的原目标。低等级角色也需要锁定岛的预览数据。
     * @param role 当前角色
     * @return 三岛有序演员
     */
    public List<Map<String, Object>> list(LocalRole role) {
        long day = LocalDate.now(ZoneOffset.ofHours(8)).toEpochDay();
        List<Map<String, Object>> result = new ArrayList<>();
        islands.fields().forEachRemaining(entry -> {
            int land = Integer.parseInt(entry.getKey());
            JsonNode island = entry.getValue();
            String scope = role.id + ":" + day + ":" + land;
            Random random = new Random(
                    UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).getLeastSignificantBits());
            add(result, random, scope, land, island.path("boss"), 1, true);
            add(result, random, scope, land, island.path("normal"), island.path("max").asInt(), false);
        });
        return result;
    }

    /**
     * 按底包行权重不放回抽取，给每个角色当天的目标分配稳定编号。
     * @param result 累积的三岛目标
     * @param random 当日随机源
     * @param scope 角色日期岛屿标志
     * @param land 岛号
     * @param pool 配置候选与权重
     * @param count 最大选取数
     * @param boss 是否神兽
     */
    private void add(List<Map<String, Object>> result, Random random, String scope, int land, JsonNode pool, int count,
            boolean boss) {
        List<JsonNode> remaining = new ArrayList<>();
        pool.forEach(remaining::add);
        for (int slot = 0; slot < count && !remaining.isEmpty(); slot++) {
            int pick = random.nextInt(remaining.stream().mapToInt(pair -> pair.get(1).asInt()).sum());
            int pos = 0;
            while (pick >= remaining.get(pos).get(1).asInt()) {
                pick -= remaining.get(pos++).get(1).asInt();
            }
            int index = remaining.remove(pos).get(0).asInt();
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("uid", UUID.nameUUIDFromBytes((scope + ":" + index).getBytes(StandardCharsets.UTF_8)).toString());
            actor.put("index", index);
            actor.put("land", land);
            actor.put("isboss", boss);
            actor.put("state", 0);
            actor.put("deadline", 0);
            actor.put("autotime", 0);
            actor.put("escapetime", 0);
            result.add(actor);
        }
    }

    /**
     * 校验角色是否达到指定岛屿的开放等级。
     * @param role 当前角色
     * @param land 岛屿编号
     * @return 是否达到底包规定等级
     */
    public boolean unlocked(LocalRole role, int land) {
        return land >= 1 && land <= 3 && role.level >= islands.path(String.valueOf(land)).path("level").asInt();
    }

    /** 按冒险卡片 24 的同一开放窗口判定能否开始参与。
     * @return 当前是否在参与窗口
     */
    public boolean open() {
        long now = Instant.now().getEpochSecond();
        return activities.overviewOpenAt(24, now);
    }

    /** 查找底包目标配置。
     * @param index 原表编号
     * @return 演员和结算配置
     */
    JsonNode actor(int index) {
        return actors.path(String.valueOf(index));
    }

    /** 读取岛屿指定类型的候选及权重。
     * @param land 岛屿编号
     * @param boss 是否首领
     * @return 原表候选
     */
    JsonNode pool(int land, boolean boss) {
        return islands.path(String.valueOf(land)).path(boss ? "boss" : "normal");
    }
}
