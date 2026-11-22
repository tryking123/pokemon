package com.pokemon.offline.quest;

import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.battle.AdventureStage;
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

    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final AdventureProgressService adventure;
    private final Map<Integer, Mission> missions = new HashMap<>();
    private final Map<Integer, Chapter> chapters = new HashMap<>();

    /**
     * 加载底包任务首次接取和奖励配置
     *
     * @param jdbc 本地数据库操作模板
     * @param roles 角色锁与货币服务
     * @param inventory 普通背包服务
     * @param adventure 训练家装备实例与冒险通关进度
     * @throws IOException 配置资源读取失败
     */
    public QuestService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                        AdventureProgressService adventure) throws IOException {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.adventure = adventure;
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
        if (!mission.reward.equipment.isEmpty() &&
                mission.reward.equipment.values().stream().mapToLong(Long::longValue).sum()
                        > inventory.ordinaryCapacity(roleId) - inventory.ordinaryUsed(roleId)) {
            return ClaimResult.result(100015);
        }
        grant(roleId, mission.reward, "quest:mission:" + mission.id);
        jdbc.update("UPDATE local_role_task SET claimed=TRUE WHERE role_id=? AND task_id=?", roleId, mission.id);
        if (mission.next > 0 && missions.containsKey(mission.next)) {
            insertTask(roleId, mission.next);
        }
        refresh(roles.lockRole(roleId));
        return new ClaimResult(0, mission.reward, true);
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
        grant(roleId, chapter.reward, "quest:chapter:" + chapterId);
        jdbc.update("UPDATE local_role_story SET chapter=? WHERE role_id=?", current + 1, roleId);
        seedChapter(roleId, current + 2);
        refresh(roles.lockRole(roleId));
        return new ClaimResult(0, chapter.reward, true);
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
        // 旧版在最后一个已配置任务领奖后没有生成后续任务。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_role_task WHERE role_id=? AND task_id=10019 AND claimed=TRUE",
                Integer.class, roleId) > 0 && jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_role_task WHERE role_id=? AND task_id=10020", Integer.class, roleId) == 0) {
            insertTask(roleId, 10020);
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
            int current = mission.finish == COPY_CLEAR ? (cleared(role.id, mission.goal) ? mission.goal : 0)
                    : mission.finish == 6 ? jdbc.queryForObject("SELECT COUNT(*) FROM local_trainer_equipped WHERE role_id=?", Integer.class, role.id)
                    : mission.finish == 34 ? jdbc.queryForObject("SELECT COALESCE(SUM(enhance_level),0) FROM local_trainer_equipped WHERE role_id=?", Integer.class, role.id)
                    : mission.finish == ROLE_LEVEL ? role.level : metric(role.id, mission.finish);
            int progress = Math.min(mission.goal, Math.max(task.progress, current));
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
     * 按已成功结算的培养操作累计原任务条件。
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
     */
    private void grant(String roleId, QuestReward reward, String receipt) {
        roles.addCurrency(roleId, reward.coins, reward.diamonds, reward.exp);
        inventory.grantItemsOrMail(roleId, receipt, reward.items);
        reward.equipment.forEach((id, quantity) -> {
            for (long count = 0; count < quantity; count++) {
                adventure.grantEquipment(roleId, id, AdventureStage.equipmentPosition(id));
            }
        });
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
