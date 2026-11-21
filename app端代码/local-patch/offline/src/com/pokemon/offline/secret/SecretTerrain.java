package com.pokemon.offline.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;

/**
 * 从秘境首层的原地图碰撞层定位可行走区域，恢复旧版地图中的对象和探索记录。
 * @author tongruiyuan
 * @since 2026-11-20 11:58
 */
@Service
class SecretTerrain {
    private final ObjectMapper json;
    private final JsonNode rows;

    /** 加载原场景的通行格。
     * @param json 地图配置解析器
     * @throws IOException 地形文件读取失败
     */
    SecretTerrain(ObjectMapper json) throws IOException {
        this.json = json;
        try (InputStream stream = new ClassPathResource("secret/map40001.json").getInputStream()) {
            rows = json.readTree(stream).get("rows");
        }
    }

    /** 检查像素位置对应的格是否可通行。
     * @param x 横向像素
     * @param y 纵向像素
     * @return 是否位于原场景通行区域内
     */
    boolean walkable(int x, int y) {
        if (x < 0 || y < 0 || y / 32 >= rows.size()) {
            return false;
        }
        String row = rows.get(y / 32).asText();
        return x / 32 < row.length() && row.charAt(x / 32) == '.';
    }

    /** 生成出生点或对象的像素位置，同时验证原地图通行格。
     * @param x 横向格
     * @param y 纵向格
     * @return 可直接写入场景的位置
     */
    private ObjectNode position(int x, int y) {
        if (!walkable(x * 32, y * 32)) {
            throw new IllegalStateException("秘境对象落在阻挡格 " + x + "," + y);
        }
        return json.createObjectNode().put("x", x * 32).put("y", y * 32);
    }

    /** 对齐新旧委托的原地图位置。保留精灵血量、怪物身份、宝箱牌面和已完成交易。
     * @param run 本次秘境存档
     * @return 是否更新了对象位置
     */
    boolean align(ObjectNode run) {
        if (run.path("map").asInt() != 40001 || !run.has("contacts") || run.has("terrainVersion")) {
            return false;
        }
        Set<JsonNode> open = new LinkedHashSet<>();
        for (JsonNode prior : run.path("open")) {
            int x = prior.path("x").asInt() + 4 * 32, y = prior.path("y").asInt() + 3 * 32;
            if (walkable(x, y)) {
                open.add(position(x / 32, y / 32));
            }
        }
        run.set("spawn", position(12, 11));
        int i = 0;
        for (JsonNode unit : run.path("units")) {
            ((ObjectNode) unit).set("pos", unit.path("sid").asInt() == 230000
                    ? position(16, 13) : position(13 + i % 3, 12 + i / 3));
            i++;
        }
        int wall = 0;
        for (JsonNode obstacle : run.path("walls")) {
            if (open.contains(position(((ObjectNode) obstacle).path("pos").path("x").asInt() / 32 + 4, 14))) {
                open.add(position(10 + wall, 15));
            }
            ((ObjectNode) obstacle).set("pos", position(10 + wall++, 15));
        }
        for (JsonNode event : run.path("events")) {
            JsonNode old = event.path("pos");
            ((ObjectNode) event).set("pos", position(old.path("x").asInt() / 32 + 4, old.path("y").asInt() / 32 + 3));
        }
        run.with("contacts").with("merchant").with("actor").set("pos", position(13, 11));
        run.with("contacts").with("mirror").with("actor").set("pos", position(14, 11));
        // 旧版全开存档仍能访问首层每一个有效位置。
        if (run.path("allRevealed").asBoolean()) {
            run.set("open", all());
        } else {
            ArrayNode migrated = run.putArray("open");
            open.forEach(migrated::add);
        }
        run.put("terrainVersion", 1);
        return true;
    }

    /** 取得全图可通行的格子。
     * @return 首领胜利后可揭开的像素位置
     */
    ArrayNode all() {
        ArrayNode tiles = json.createArrayNode();
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y).asText();
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) == '.') {
                    tiles.add(position(x, y));
                }
            }
        }
        return tiles;
    }

    /** 在同一原地图通行区域内计算本次停步经过的格，绕开仍在场的秘境障碍。
     * @param run 当前委托存档
     * @param px 目标横向像素
     * @param py 目标纵向像素
     * @return 从已确认落点到目标的路线，无路可达返回空引用
     */
    List<JsonNode> route(ObjectNode run, int px, int py) {
        if (!walkable(px, py)) {
            return null;
        }
        final int width = rows.get(0).asText().length();
        JsonNode start = run.has("lastStop") ? run.path("lastStop") : run.path("spawn");
        int first = start.path("x").asInt() / 32 + width * (start.path("y").asInt() / 32);
        int goal = px / 32 + width * (py / 32);
        Set<JsonNode> obstacles = new LinkedHashSet<>();
        for (JsonNode wall : run.path("walls")) {
            obstacles.add(wall.path("pos"));
        }
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        Map<Integer, Integer> parent = new HashMap<>();
        pending.add(first);
        parent.put(first, first);
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!pending.isEmpty() && !parent.containsKey(goal)) {
            int cell = pending.removeFirst();
            for (int[] direction : directions) {
                int x = cell % width + direction[0], y = cell / width + direction[1];
                int next = x + width * y;
                if (walkable(x * 32, y * 32) && !parent.containsKey(next) && !obstacles.contains(position(x, y))) {
                    parent.put(next, cell);
                    pending.add(next);
                }
            }
        }
        if (!parent.containsKey(goal)) {
            return null;
        }
        List<JsonNode> steps = new ArrayList<>();
        for (int cell = goal; cell != first; cell = parent.get(cell)) {
            steps.add(position(cell % width, cell / width));
        }
        Collections.reverse(steps);
        return steps;
    }
}
