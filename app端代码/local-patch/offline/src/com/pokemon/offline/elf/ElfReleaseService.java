package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 精灵批量放生 Service，核验真实占用后将经验、培养材料和装配一次返还。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:12
 */
@Service
public class ElfReleaseService {
    private static final JsonNode CRYSTALS = ElfTrainingCatalog.load("elf/release-crystals.json");
    private static final List<String> EFFORT = Arrays.asList("hp_max", "speed", "special_attack",
            "physical_attack", "special_defense", "physical_defense");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final ElfMapper elves;
    private final ElfGearMapper gears;
    private final InventoryService inventory;
    private final HangupService hangup;
    private final com.pokemon.offline.activitydaily.DailyVitalityEvents vitality;

    /**
     * 连接精灵、装配、经验池和背包的本地事务。
     * @param jdbc 本地存档
     * @param json 状态解析器
     * @param roles 角色锁
     * @param elves 精灵实例
     * @param gears 携带品和力石实例
     * @param inventory 返还材料与容量
     * @param hangup 精灵经验池
     * @param vitality 每日放生真实数量
     */
    public ElfReleaseService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, ElfMapper elves,
            ElfGearMapper gears, InventoryService inventory, HangupService hangup, com.pokemon.offline.activitydaily.DailyVitalityEvents vitality) {
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.elves = elves;
        this.gears = gears;
        this.inventory = inventory;
        this.hangup = hangup;
        this.vitality = vitality;
    }

    /**
     * 根据原界面的批次选择放生并保存所得。
     * @param role 当前角色
     * @param request 已选择的精灵实例编号
     * @return 结果及直接卸下的奥义石
     */
    @Transactional
    public ObjectNode release(String role, List<?> request) {
        roles.lockRole(role);
        ObjectNode answer = json.createObjectNode().put("errno", 100021);
        Set<String> selected = new HashSet<>();
        for (Object id : request) {
            if (!(id instanceof String) || !selected.add((String) id)) return answer;
        }
        if (selected.isEmpty()) return answer;
        Set<String> blocked = unavailable(role);
        List<LocalElf> batch = new ArrayList<>();
        for (LocalElf elf : elves.list(role)) {
            if (!selected.contains(elf.id)) continue;
            if (elf.slot != 0 || blocked.contains(elf.id) || elf.training.path("lock").asInt() != 0
                    || !CRYSTALS.has(Integer.toString(elf.configId))) return answer;
            batch.add(elf);
        }
        if (batch.size() != selected.size()) return answer;
        List<ElfGear> equipment = gears.list(role);
        Map<Integer, Integer> gearSlots = new HashMap<>();
        Set<Integer> returnedKinds = new HashSet<>();
        for (ElfGear gear : equipment) {
            if (gear.elfId == null || selected.contains(gear.elfId)) gearSlots.merge(gear.kind, 1, Integer::sum);
            if (selected.contains(gear.elfId)) returnedKinds.add(gear.kind);
        }
        for (int kind : returnedKinds) {
            if (gearSlots.get(kind) > ElfGearService.BAG_CAPACITY) return answer;
        }
        long stones = batch.stream().filter(elf -> elf.stone.has("sid")).count();
        if (stones > 0 && inventory.ordinaryUsed(role) + stones > inventory.ordinaryCapacity(role)) return answer;
        Map<Integer, Long> rewards = new LinkedHashMap<>();
        long[] efforts = new long[EFFORT.size()];
        long experience = 0;
        for (LocalElf elf : batch) {
            refunds(elf, rewards, efforts);
            experience += ElfTrainingService.spentExperience(elf.level) * 4 / 5;
        }
        for (long attribute : efforts) add(rewards, 10002, attribute * 4 / 5);
        ArrayNode returned = answer.putArray("Z_Stone_s");
        for (ElfGear gear : equipment) {
            if (selected.contains(gear.elfId) && gears.equip(role, gear.id, null, 0) != 1) {
                throw new IllegalStateException("放生时装配归属变化");
            }
        }
        for (LocalElf elf : batch) {
            if (elf.stone.has("sid")) {
                if (jdbc.update("UPDATE local_secret_stone SET elf_id=NULL WHERE role_id=? AND elf_id=? AND sid=?",
                        role, elf.id, elf.stone.path("sid").asText()) != 1) throw new IllegalStateException("奥义石归属变化");
                returned.add(elf.stone);
            }
            if (elves.consumeElf(role, elf.id) != 1) throw new IllegalStateException("放生时精灵占用变化");
        }
        Map<Integer, Long> direct = inventory.grantItemsOrMail(role, "release:" + UUID.randomUUID(), rewards);
        for (Map.Entry<Integer, Long> entry : direct.entrySet()) {
            answer.withArray("items").addObject().put("id", entry.getKey()).put("n", entry.getValue());
        }
        answer.put("experience", experience).put("pool", hangup.addElfPool(role, experience));
        vitality.record(role,15,"release:"+UUID.randomUUID(),batch.size());
        return answer.put("errno", 0);
    }

    /**
     * 按原每只精灵的水晶底数、逐星消耗和亲密度计算返还，并累计六项努力值。
     * @param elf 已验证的精灵
     * @param rewards 整批道具
     * @param effortTotals 六项属性的整批合计
     */
    private void refunds(LocalElf elf, Map<Integer, Long> rewards, long[] effortTotals) {
        JsonNode base = CRYSTALS.path(Integer.toString(elf.configId));
        add(rewards, 21024, base.get(0).asLong());
        add(rewards, 21026, base.get(1).asLong());
        Map<Integer, Long> costs = new HashMap<>();
        int star = elf.training.path("star").asInt(1);
        for (int i = 1; i <= star; i++) {
            JsonNode row = ElfTrainingCatalog.row("variable", 1019 + i).path("data7");
            for (int f = 1; f + 2 <= row.size(); f += 3) {
                if (row.path(String.valueOf(f)).asInt() == 1) {
                    add(costs, row.path(String.valueOf(f + 1)).asInt(), row.path(String.valueOf(f + 2)).asLong());
                }
            }
        }
        costs.forEach((id, count) -> add(rewards, id, count * 4 / 5));
        int love = elf.training.path("love").asInt(1);
        if (love > 1) {
            long points = elf.training.path("loveexp").asLong();
            for (int i = 1; i < love; i++) points += ElfTrainingCatalog.row("love", i).path("exp").asLong();
            add(rewards, 10001, points / 50);
        }
        for (int i = 0; i < EFFORT.size(); i++) {
            effortTotals[i] += elf.training.path("effort").path(EFFORT.get(i)).asLong();
        }
    }

    /**
     * 累加实际可发数量。
     * @param target 奖励汇总
     * @param id 物品模板
     * @param count 数量
     */
    private static void add(Map<Integer, Long> target, int id, long count) {
        if (count > 0) target.merge(id, count, Long::sum);
    }

    /**
     * 为精灵兑换选取可消耗的实例。优先低等级、低星，最后按实例编号排序。
     * @param role 角色编号
     * @param template 所需精灵模板
     * @param quantity 本档所需数量
     * @return 未锁且未占用、未装配物品的候选，数量不足时按实际返回
     */
    public List<LocalElf> exchangeCandidates(String role, int template, int quantity) {
        return detachedCandidates(role, template, false).stream()
                .sorted(java.util.Comparator.comparingInt((LocalElf elf) -> elf.level)
                        .thenComparingInt(elf -> elf.training.path("star").asInt(1)).thenComparing(elf -> elf.id))
                .limit(quantity).collect(java.util.stream.Collectors.toList());
    }

    /** 找出未上阵、未锁定、没有外部占用且已取下携带品和奥义石的指定模板实例。
     * @param role 角色编号
     * @param template 所需模板
     * @param allowBook 是否允许按原合体确认消耗已装极巨技能的实例
     * @return 可参与本次材料选择的实例
     */
    List<LocalElf> detachedCandidates(String role, int template, boolean allowBook) {
        Set<String> blocked = unavailable(role);
        for (ElfGear gear : gears.list(role)) {
            if (gear.elfId != null) {
                blocked.add(gear.elfId);
            }
        }
        return elves.list(role).stream().filter(elf -> elf.configId == template && elf.slot == 0
                && !blocked.contains(elf.id) && elf.training.path("lock").asInt() == 0
                && !elf.stone.has("sid") && (allowBook || elf.training.path("dynabook").asInt() == 0))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 在角色锁保护下移除本次已经重新验证并确认的材料精灵。
     * @param role 结算角色
     * @param batch 与确认回执一致的现有实例
     */
    @Transactional(propagation = com.pokemon.offline.platform.transaction.annotation.Propagation.MANDATORY)
    public void consumeExchangeCandidates(String role, List<LocalElf> batch) {
        for (LocalElf elf : batch) {
            if (elves.consumeElf(role, elf.id) != 1) {
                throw new IllegalStateException("精灵兑换材料的归属或占用已变化");
            }
        }
    }

    /**
     * 读取道馆、跟随、旅行、竞技、寄存和初始伙伴的实际占用。
     * @param role 角色编号
     * @return 仍须保留的精灵实例
     */
    private Set<String> unavailable(String role) {
        Set<String> result = new HashSet<>(jdbc.queryForList("SELECT id FROM local_elf WHERE role_id=? AND starter=TRUE "
                + "UNION SELECT elf_id FROM local_dojo_team WHERE role_id=? UNION SELECT elf_id FROM local_elf_aid WHERE role_id=? "
                + "UNION SELECT elf_id FROM local_elf_follow WHERE role_id=? UNION SELECT elf_id FROM local_travel_dispatch WHERE role_id=?",
                String.class, role, role, role, role, role));
        List<String> lineups = jdbc.queryForList("SELECT state_data FROM local_arena_state WHERE role_id=? AND state_key LIKE 'lineup:%'",
                String.class, role);
        for (String payload : lineups) {
            for (JsonNode elf : object(payload).path("battle")) result.add(elf.path("id").asText());
        }
        for (String payload : jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?", String.class, role)) {
            for (JsonNode elf : object(payload).path("pair")) result.add(elf.asText());
        }
        return result;
    }

    /**
     * 解析已经提交的玩法引用。
     * @param payload 存档内容
     * @return 玩法状态
     */
    private JsonNode object(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException e) {
            throw new IllegalStateException("精灵占用记录无法解析", e);
        }
    }
}
