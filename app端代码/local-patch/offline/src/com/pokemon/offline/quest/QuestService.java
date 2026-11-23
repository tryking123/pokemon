package com.pokemon.offline.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.battle.AdventureStage;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.quest.api.entity.QuestTask;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地主线、章节奖励与首次教学进度服务
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
@Service
public class QuestService {

    private static final int FIRST_TASK = 10001;
    private static final int UNKNOWN_TASK = 5001;
    private static final int UNFINISHED_TASK = 100032;
    private static final int UNAVAILABLE_REWARD = 100003;
    private static final int ADVANCED_GACHA = 1;
    private static final int PARTY_SIZE = 2;
    private static final int COPY_CLEAR = 3;
    private static final int NORMAL_GACHA = 4;
    private static final int ROLE_LEVEL = 19;
    private static final int ELF_LEVEL_COUNT = 24;

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final AdventureProgressService adventure;
    private final ElfService elves;
    private final Map<Integer, Mission> missions = new HashMap<>();
    private final Map<Integer, Chapter> chapters = new HashMap<>();

    /**
     * 加载底包任务首次接取和奖励配置
     *
     * @param jdbc 本地数据库操作模板
     * @param roles 角色锁与货币服务
     * @param inventory 普通背包服务
     * @param adventure 训练家装备实例与冒险通关进度
     * @param elves 任务奖励精灵入库
     * @param json 竞技场及矿洞存档解析器
     * @throws IOException 配置资源读取失败
     */
    public QuestService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                        AdventureProgressService adventure, ObjectMapper json, ElfService elves) throws IOException {
        this.json = json;
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.adventure = adventure;
        this.elves = elves;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("quest/story.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split(" ");
                int id = Integer.parseInt(fields[1]);
                if ("T".equals(fields[0])) {
                    missions.put(id, new Mission(fields));
                } else {
                    chapters.put(id, new Chapter(fields));
                }
            }
        }
    }

    /**
     * 初始化旧档与新档的任务记录，并读取待同步的任务快照
     *
     * @param roleId 角色主键
     * @return 已领取章节和当前任务
     */
    @Transactional
    public QuestSnapshot snapshot(String roleId) {
        LocalRole role = prepare(roleId);
        refresh(role);
        return new QuestSnapshot(chapter(roleId), visibleTasks(roleId));
    }

    /**
     * 在三选一事务中记录精灵选择，不将教学选择记为主线扭蛋次数
     *
     * @param roleId 角色主键
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordStarterSelected(String roleId) {
        prepare(roleId);
        jdbc.update("UPDATE local_role_story SET starter_selected=TRUE WHERE role_id=?", roleId);
        return Collections.emptyList();
    }

    /**
     * 在教程战斗结算事务中记录首胜，不将教程战计作冒险关卡
     *
     * @param roleId 角色主键
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordTutorialBattleWin(String roleId) {
        prepare(roleId);
        jdbc.update("UPDATE local_role_story SET tutorial_won=TRUE WHERE role_id=? AND starter_selected=TRUE", roleId);
        return Collections.emptyList();
    }

    /**
     * 记录首次通过某一真实场景或冒险关卡
     *
     * @param roleId 角色主键
     * @param stageId 关卡原编号
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordBattleClear(String roleId, int stageId) {
        LocalRole role = prepare(roleId);
        if (!cleared(roleId, stageId)) {
            jdbc.update("INSERT INTO local_role_story_clear (role_id,stage_id) VALUES (?,?)", roleId, stageId);
        }
        return refresh(role);
    }

    /**
     * 读取已经选择初始精灵的状态
     *
     * @param roleId 角色主键
     * @return 是否已选择初始精灵
     */
    public boolean starterSelected(String roleId) {
        return !jdbc.query("SELECT role_id FROM local_role_story WHERE role_id=? AND starter_selected=TRUE",
                (row, index) -> row.getString(1), roleId).isEmpty();
    }

    /**
     * 在已成功提交的扭蛋事务中增加原任务的抽取计数
     *
     * @param roleId 角色主键
     * @param type 抽取类型，1 为高级，2 为普通
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordGacha(String roleId, int type) {
        if (type != 1 && type != 2) {
            throw new IllegalArgumentException("无法识别扭蛋类型");
        }
        return addMetric(roleId, type == 1 ? ADVANCED_GACHA : NORMAL_GACHA);
    }

    /**
     * 用已保存的队伍成员数推进要求一定上阵数量的任务
     *
     * @param roleId 角色主键
     * @param size 当前可用上阵精灵数
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordPartySize(String roleId, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("上阵精灵数不能为负");
        }
        LocalRole role = prepare(roleId);
        setMetric(roleId, PARTY_SIZE, Math.max(size, metric(roleId, PARTY_SIZE)));
        return refresh(role);
    }

    /**
     * 记录一次确已结算的精灵升级动作
     *
     * @param roleId 角色主键
     * @return 受影响的任务进度
     */
    @Transactional
    public List<QuestTask> recordElfLevelUp(String roleId) {
        return addMetric(roleId, ELF_LEVEL_COUNT);
    }

    /**
     * 提交已完成任务，在同一事务中发放配置奖励及接取下一项
     *
     * @param roleId 角色主键
     * @param taskId 请求中的任务编号
     * @return 提交结果及新增奖励
     */
    @Transactional
    public ClaimResult submitTask(String roleId, long taskId) {
        LocalRole role = prepare(roleId);
        Mission mission = lookupMission(taskId);
        if (mission == null) {
            return ClaimResult.result(UNKNOWN_TASK);
        }
        refresh(role);
        List<Boolean> state = jdbc.query("SELECT complete,claimed FROM local_role_task WHERE role_id=? AND task_id=?",
                (row, index) -> row.getBoolean("claimed") || row.getBoolean("complete"), roleId, mission.id);
        if (state.isEmpty() || !state.get(0)) {
            return ClaimResult.result(UNFINISHED_TASK);
        }
        Boolean claimed = jdbc.queryForObject("SELECT claimed FROM local_role_task WHERE role_id=? AND task_id=?",
                Boolean.class, roleId, mission.id);
        if (Boolean.TRUE.equals(claimed)) {
            return ClaimResult.result(0);
        }
        if (!mission.reward.supported || mission.elfExp != 0) {
            return ClaimResult.result(UNAVAILABLE_REWARD);
        }
        long gearSlots = mission.reward.equipment.values().stream().mapToLong(Long::longValue).sum()
                + mission.reward.stones.values().stream().mapToLong(Long::longValue).sum();
        if (gearSlots > 0 && gearSlots > inventory.ordinaryCapacity(roleId) - inventory.ordinaryUsed(roleId)) {
            return ClaimResult.result(100015);
        }
        long elfSlots = mission.reward.elves.values().stream().mapToLong(Long::longValue).sum();
        if (elfSlots > 0 && elfSlots > inventory.elfCapacity(roleId) - elves.list(roleId).size()) {
            return ClaimResult.result(100015);
        }
        ClaimResult result = grant(roleId, mission.reward, "quest:mission:" + mission.id);
        jdbc.update("UPDATE local_role_task SET claimed=TRUE WHERE role_id=? AND task_id=?", roleId, mission.id);
        if (mission.next > 0 && missions.containsKey(mission.next)) {
            insertTask(roleId, mission.next);
        }
        refresh(roles.lockRole(roleId));
        return result;
    }

    /**
     * 领取下一章节奖励，重复请求不重复增加资产
     *
     * @param roleId 角色主键
     * @param chapterId 要领取的章节编号
     * @return 领取结果及新增奖励
     */
    @Transactional
    public ClaimResult claimChapter(String roleId, long chapterId) {
        LocalRole role = prepare(roleId);
        int current = chapter(roleId);
        if (chapterId > 0 && chapterId <= current) {
            return ClaimResult.result(0);
        }
        if (chapterId != current + 1 || !chapters.containsKey(current + 1)) {
            return ClaimResult.result(UNKNOWN_TASK);
        }
        refresh(role);
        Chapter chapter = chapters.get(current + 1);
        for (int id : chapter.taskIds) {
            List<Boolean> complete = jdbc.query("SELECT complete FROM local_role_task WHERE role_id=? AND task_id=?",
                    (row, index) -> row.getBoolean(1), roleId, id);
            if (complete.isEmpty() || !complete.get(0)) {
                return ClaimResult.result(UNFINISHED_TASK);
            }
        }
        if (!chapter.reward.supported) {
            return ClaimResult.result(UNAVAILABLE_REWARD);
        }
        ClaimResult result = grant(roleId, chapter.reward, "quest:chapter:" + chapterId);
        jdbc.update("UPDATE local_role_story SET chapter=? WHERE role_id=?", current + 1, roleId);
        seedChapter(roleId, current + 2);
        refresh(roles.lockRole(roleId));
        return result;
    }

    /**
     * 锁定角色行并为首次访问的账号建立已验证的初始任务
     *
     * @param roleId 角色主键
     * @return 锁定后的角色状态
     */
    private LocalRole prepare(String roleId) {
        LocalRole role = roles.lockRole(roleId);
        if (jdbc.query("SELECT role_id FROM local_role_story WHERE role_id=?",
                (row, index) -> row.getString(1), roleId).isEmpty()) {
            jdbc.update("INSERT INTO local_role_story (role_id) VALUES (?)", roleId);
            insertTask(roleId, FIRST_TASK);
            seedChapter(roleId, 1);
        }
        // 主线按编号递增。仅从最后一条已领奖记录恢复后继，已有待办时保留原进度。
        List<Integer> lastClaimed = jdbc.query("SELECT task_id FROM local_role_task WHERE role_id=? "
                        + "AND task_id BETWEEN 10001 AND 19999 AND claimed=TRUE AND NOT EXISTS "
                        + "(SELECT 1 FROM local_role_task WHERE role_id=? AND task_id BETWEEN 10001 AND 19999 "
                        + "AND claimed=FALSE) ORDER BY task_id DESC LIMIT 1",
                (row, index) -> row.getInt(1), roleId, roleId);
        if (!lastClaimed.isEmpty()) {
            Mission previous = missions.get(lastClaimed.get(0));
            if (previous != null && missions.containsKey(previous.next)) {
                insertTask(roleId, previous.next);
            }
        }
        return role;
    }

    /**
     * 插入本地任务的初始状态
     *
     * @param roleId 角色主键
     * @param taskId 任务编号
     */
    private void insertTask(String roleId, int taskId) {
        jdbc.update("INSERT INTO local_role_task (role_id,task_id,progress) VALUES (?,?,0)", roleId, taskId);
    }

    /**
     * 初始化当前章节需要的所有并行里程碑
     *
     * @param roleId 角色主键
     * @param index 章节索引
     */
    private void seedChapter(String roleId, int index) {
        Chapter chapter = chapters.get(index);
        if (chapter != null) {
            for (int id : chapter.taskIds) {
                insertTask(roleId, id);
            }
        }
    }

    /**
     * 查询已领奖章节的最后索引
     *
     * @param roleId 角色主键
     * @return 已领奖章节索引
     */
    private int chapter(String roleId) {
        return jdbc.queryForObject("SELECT chapter FROM local_role_story WHERE role_id=?", Integer.class, roleId);
    }

    /**
     * 查询客户端应看到的任务，保留领奖后仍用于章节检查的里程碑
     *
     * @param roleId 角色主键
     * @return 当前任务列表
     */
    private List<QuestTask> visibleTasks(String roleId) {
        return jdbc.query("SELECT task_id,complete,progress FROM local_role_task "
                        + "WHERE role_id=? AND (claimed=FALSE OR task_id>=31000) ORDER BY task_id",
                (row, index) -> new QuestTask(row.getInt(1), row.getBoolean(2), row.getInt(3)), roleId);
    }

    /**
     * 按服务器记录重新计算支持的任务条件，并保存新增进度
     *
     * @param role 事务中锁定的角色
     * @return 有变化的任务
     */
    private List<QuestTask> refresh(LocalRole role) {
        List<QuestTask> changed = new ArrayList<>();
        for (QuestTask task : visibleTasks(role.id)) {
            Mission mission = missions.get(task.id);
            if (mission == null || task.state == 2) {
                continue;
            }
            long current = mission.type == 3 ? mission.goal : currentProgress(role, mission);
            int progress = (int) Math.min(mission.goal, Math.max(task.progress, current));
            if (progress > task.progress) {
                boolean complete = progress >= mission.goal;
                jdbc.update("UPDATE local_role_task SET progress=?,complete=? WHERE role_id=? AND task_id=?",
                        progress, complete, role.id, task.id);
                changed.add(new QuestTask(task.id, complete, progress));
            }
        }
        return changed;
    }

    /**
     * 按底包条件读取已经提交的业务流水、装备状态及关卡进度。
     * @param role 当前角色
     * @param mission 当前主线任务
     * @return 实际完成数量或关卡编号
     */
    private long currentProgress(LocalRole role, Mission mission) {
        String query;
        switch (mission.finish) {
            case COPY_CLEAR: return cleared(role.id, mission.goal) ? mission.goal : 0;
            case ROLE_LEVEL: return role.level;
            case 28: return mission.id == 10129 || mission.id == 10153 ? mission.goal : metric(role.id, 28);
            case 6: query = "SELECT COUNT(*) FROM local_trainer_equipped WHERE role_id=?"; break;
            case 11: case 17:
                return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM local_vitality_event WHERE role_id=? AND task_id=?",
                        Long.class, role.id, mission.finish == 11 ? 15 : 17);
            case 13: return arenaState(role.id, "stats:arena_training").path("wins").asLong();
            case 14: query = "SELECT COUNT(*) FROM local_elf_individual_receipt WHERE role_id=?"; break;
            case 15: case 16: case 23:
                int event = mission.finish == 15 ? 112 : mission.finish == 16 ? 50015 : 101;
                return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM local_carnival_progress WHERE role_id=? AND task_type=?",
                        Long.class, role.id, event);
            case 20: query = "SELECT COALESCE(MAX(layer),0) FROM local_tower_clear WHERE role_id=?"; break;
            case 21: query = "SELECT COUNT(*) FROM local_adventure_quicken WHERE role_id=?"; break;
            case 27: query = "SELECT COALESCE(SUM(amount),0) FROM local_diamond_spend WHERE role_id=?"; break;
            case 29: query = "SELECT COUNT(*) FROM local_personal_guild_donation WHERE role_id=?"; break;
            case 31: query = "SELECT COALESCE(SUM(normal_total+advanced_total+friend_total),0) FROM local_gacha_state WHERE role_id=?"; break;
            case 32:
                JsonNode mine = arenaState(role.id, "mine-account");
                return Math.max(mine.path("occupations").asLong(), mine.has("lease") ? 1 : 0);
            case 34: query = "SELECT COALESCE(SUM(enhance_level),0) FROM local_trainer_equipped WHERE role_id=?"; break;
            case 35: query = "SELECT COUNT(*) FROM local_elf_handbook WHERE role_id=?"; break;
            case 36: query = "SELECT COUNT(*) FROM local_elf_gear g JOIN local_elf e ON g.elf_id=e.id "
                    + "WHERE g.role_id=? AND g.kind=4 AND e.slot>0 AND e.team=COALESCE((SELECT team_value FROM local_team_choice WHERE role_id=g.role_id AND purpose='default'),1)"; break;
            case 37: query = "SELECT MAX(0,COALESCE(MAX(history),0)-1000) FROM local_dojo_progress WHERE role_id=?"; break;
            default: return metric(role.id, mission.finish);
        }
        return jdbc.queryForObject(query, Long.class, role.id);
    }

    /**
     * 读取当前角色已保存的竞技或矿洞统计。
     * @param roleId 角色编号
     * @param key 模式存档键
     * @return 对应统计，首次参与前为空对象
     */
    private JsonNode arenaState(String roleId, String key) {
        List<String> rows = jdbc.queryForList("SELECT state_data FROM local_arena_state WHERE role_id=? AND state_key=?",
                String.class, roleId, key);
        try {
            return rows.isEmpty() ? json.createObjectNode() : json.readTree(rows.get(0));
        } catch (IOException invalid) {
            throw new IllegalStateException("无法读取主线任务所需的模式存档", invalid);
        }
    }

    /**
     * 玩法结算后读取应推送给界面的任务进度变化。
     * @param roleId 角色编号
     * @return 本次刷新后有变化的任务
     */
    @Transactional
    public List<QuestTask> refreshProgress(String roleId) {
        return refresh(prepare(roleId));
    }

    /**
     * 判断持久化的关卡首通状态
     *
     * @param roleId 角色主键
     * @param stageId 客户端数据中的关卡编号
     * @return 是否已有首通记录
     */
    private boolean cleared(String roleId, int stageId) {
        return !jdbc.query("SELECT stage_id FROM local_role_story_clear WHERE role_id=? AND stage_id=?",
                (row, index) -> row.getInt(1), roleId, stageId).isEmpty();
    }

    /**
     * 查询特定任务条件的累计次数
     *
     * @param roleId 角色主键
     * @param type 原任务条件编号
     * @return 计数，未记录时为零
     */
    private int metric(String roleId, int type) {
        return jdbc.query("SELECT progress FROM local_role_story_metric WHERE role_id=? AND metric=?",
                (row, index) -> row.getInt(1), roleId, type).stream().findFirst().orElse(0);
    }

    /**
     * 保存已经验证的任务计数
     *
     * @param roleId 角色主键
     * @param type 原任务条件编号
     * @param progress 最新计数
     */
    private void setMetric(String roleId, int type, int progress) {
        jdbc.update("MERGE INTO local_role_story_metric (role_id,metric,progress) KEY (role_id,metric) VALUES (?,?,?)",
                roleId, type, progress);
    }

    /**
     * 为具备唯一结算凭据的已完成动作增加一次计数
     *
     * @param roleId 角色主键
     * @param type 原任务条件编号
     * @return 受影响的任务进度
     */
    private List<QuestTask> addMetric(String roleId, int type) {
        LocalRole role = prepare(roleId);
        setMetric(roleId, type, Math.addExact(metric(roleId, type), 1));
        return refresh(role);
    }

    /**
     * 按已成功结算的玩法操作累计原任务条件。
     * @param roleId 角色编号
     * @param type 原任务条件编号
     * @param amount 实际提升等级、次数或努力点数
     */
    @Transactional
    public void recordTraining(String roleId, int type, int amount) {
        if (amount > 0) {
            prepare(roleId);
            setMetric(roleId, type, Math.addExact(metric(roleId, type), amount));
        }
    }

    /**
     * 将可发放的奖励加入角色存档，道具满包时余量转入系统邮件
     *
     * @param roleId 角色主键
     * @param reward 原始奖励项
     * @param receipt 本档奖励的唯一回执
     * @return 已落库的奖励及新增实例
     */
    private ClaimResult grant(String roleId, QuestReward reward, String receipt) {
        ClaimResult result = new ClaimResult(0, reward, true);
        roles.addCurrency(roleId, reward.coins, reward.diamonds, reward.exp);
        inventory.grantItemsOrMail(roleId, receipt, reward.items);
        reward.equipment.forEach((id, quantity) -> {
            for (long count = 0; count < quantity; count++) {
                adventure.grantEquipment(roleId, id, AdventureStage.equipmentPosition(id));
            }
        });
        reward.elves.forEach((id, quantity) -> {
            for (long count = 0; count < quantity; count++) result.elves.add(elves.grant(roleId, id));
        });
        reward.stones.forEach((id, quantity) -> {
            for (long count = 0; count < quantity; count++) {
                String sid = java.util.UUID.randomUUID().toString();
                jdbc.update("INSERT INTO local_secret_stone(sid,role_id,template_id) VALUES(?,?,?)", sid, roleId, id);
                result.stones.put(sid, id);
            }
        });
        if (reward.currency121 > 0 && jdbc.update("UPDATE local_inventory_currency SET balance=balance+? "
                + "WHERE role_id=? AND currency=121", reward.currency121, roleId) == 0) {
            jdbc.update("INSERT INTO local_inventory_currency(role_id,currency,balance) VALUES(?,121,?)",
                    roleId, reward.currency121);
        }
        return result;
    }

    /**
     * 在原游戏任务编号范围内读取任务配置
     *
     * @param taskId 请求中的任务编号
     * @return 任务定义，无匹配项时为空
     */
    private Mission lookupMission(long taskId) {
        return taskId > 0 && taskId <= Integer.MAX_VALUE ? missions.get((int) taskId) : null;
    }

    /**
     * 从本地提取的数据载入主线任务
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:46
     */
    private static final class Mission {
        private final int id;
        /** 原表任务类别，3 为无操作引导的章节目标。 */
        private final int type;
        private final int finish;
        private final int goal;
        private final int next;
        private final int elfExp;
        private final QuestReward reward;

        /**
         * 读取任务条件和奖励字段
         *
         * @param fields 按顺序的原始配置字段
         */
        private Mission(String[] fields) {
            id = Integer.parseInt(fields[1]);
            type = Integer.parseInt(fields[2]);
            finish = Integer.parseInt(fields[3]);
            goal = Integer.parseInt(fields[4]);
            next = Integer.parseInt(fields[5]);
            elfExp = Integer.parseInt(fields[9]);
            reward = new QuestReward(Long.parseLong(fields[6]), Long.parseLong(fields[7]),
                    Long.parseLong(fields[8]), fields[10]);
        }
    }

    /**
     * 原始章节的任务集合和奖励
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:46
     */
    private static final class Chapter {
        private final List<Integer> taskIds = new ArrayList<>();
        private final QuestReward reward;

        /**
         * 读取章节任务编号与奖励
         *
         * @param fields 原始配置字段
         */
        private Chapter(String[] fields) {
            for (String item : fields[2].split(",")) {
                taskIds.add(Integer.parseInt(item));
            }
            reward = new QuestReward(0, 0, 0, fields[3]);
        }
    }
}
