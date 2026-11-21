package com.pokemon.offline.elf;

import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 精灵跟随槽位、离线亲密结算和请求重放控制。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:02
 */
@Service
public class ElfFollowService {
    /** leading_proto 1130 的角色等级门槛。 */
    private static final int REQUIRED_LEVEL = 38;
    /** pkmfollow_model 仅列出红色及以上品质精灵。 */
    private static final int REQUIRED_QUALITY = 5;
    /** followunlock_proto 的训练师阶段门槛。 */
    private static final int[] SLOT_STAGES = {0, 1, 2};
    /** variable_proto 3611 的每分钟亲密度与离线分钟上限。 */
    private static final int EXPERIENCE_PER_MINUTE = 5;
    private static final int MAX_MINUTES_PER_SETTLEMENT = 720;
    private static final int INVALID = 100021;
    private static final int REPEATED = 100011;

    private final RoleService roles;
    private final ElfMapper elves;
    private final JdbcTemplate jdbc;

    /**
     * 连接角色事务、精灵培养状态及跟随存档。
     *
     * @param roles 本地角色服务
     * @param elves 精灵实例和培养存档
     * @param jdbc 跟随槽位和请求回执
     */
    ElfFollowService(RoleService roles, ElfMapper elves, JdbcTemplate jdbc) {
        this.roles = roles;
        this.elves = elves;
        this.jdbc = jdbc;
    }

    /** 初次启动时建立跟随槽位及修改回执。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("elf/follow.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取跟随槽位并发放最近经过时间产生的亲密经验。
     *
     * @param roleId 角色编号
     * @return 槽位快照及这次提交的亲密经验
     */
    @Transactional
    public Result synchronize(String roleId) {
        LocalRole role = roles.lockRole(roleId);
        Map<Integer, Slot> slots = slots(roleId);
        Map<Integer, List<String>> gains = settle(role, slots, Instant.now().getEpochSecond());
        return new Result(0, visible(role, slots), gains);
    }

    /**
     * 设置或清除跟随精灵，修改之前结清原精灵的完整分钟。
     *
     * @param roleId 角色编号
     * @param index 跟随槽位编号，从一开始
     * @param elfId 角色拥有的精灵实例编号，空字符串表示清空
     * @param requestKey 来源连接与协议请求号组成的键
     * @return 已保存的槽位及亲密度
     */
    @Transactional
    public Result set(String roleId, long index, String elfId, String requestKey) {
        LocalRole role = roles.lockRole(roleId);
        if (!unlocked(role, index) || elfId == null || elfId.length() > 40) {
            return failure(INVALID);
        }
        if (recorded(roleId, requestKey)) {
            return failure(REPEATED);
        }
        Map<Integer, Slot> slots = slots(roleId);
        int position = (int) index;
        if (!elfId.isEmpty()) {
            LocalElf elf = elves.findOwned(roleId, elfId).orElse(null);
            if (elf == null || ElfTrainingCatalog.row("species", elf.configId).path("quality").asInt() < REQUIRED_QUALITY
                    || slots.entrySet().stream().anyMatch(slot -> slot.getKey() != position
                    && slot.getValue().elfId.equals(elfId))) {
                return failure(INVALID);
            }
        }
        long now = Instant.now().getEpochSecond();
        Map<Integer, List<String>> gains = settle(role, slots, now);
        Slot old = slots.get(position);
        if (old == null || !old.elfId.equals(elfId)) {
            remove(roleId, position);
            slots.remove(position);
            if (!elfId.isEmpty()) {
                Slot inserted = new Slot(elfId, now);
                insert(roleId, position, inserted);
                slots.put(position, inserted);
            }
        }
        record(roleId, requestKey);
        return new Result(0, visible(role, slots), gains);
    }

    /**
     * 交换两个已经开放的槽位，保留各精灵的结算游标。
     *
     * @param roleId 角色编号
     * @param first 第一个槽位编号
     * @param second 第二个槽位编号
     * @param requestKey 来源连接与协议请求号组成的键
     * @return 已保存的槽位及亲密度
     */
    @Transactional
    public Result swap(String roleId, long first, long second, String requestKey) {
        LocalRole role = roles.lockRole(roleId);
        if (first == second || !unlocked(role, first) || !unlocked(role, second)) {
            return failure(INVALID);
        }
        if (recorded(roleId, requestKey)) {
            return failure(REPEATED);
        }
        int a = (int) first;
        int b = (int) second;
        Map<Integer, Slot> slots = slots(roleId);
        if (!slots.containsKey(a) && !slots.containsKey(b)) {
            return failure(INVALID);
        }
        Map<Integer, List<String>> gains = settle(role, slots, Instant.now().getEpochSecond());
        Slot left = slots.remove(a);
        Slot right = slots.remove(b);
        remove(roleId, a);
        remove(roleId, b);
        if (left != null) {
            insert(roleId, b, left);
            slots.put(b, left);
        }
        if (right != null) {
            insert(roleId, a, right);
            slots.put(a, right);
        }
        record(roleId, requestKey);
        return new Result(0, visible(role, slots), gains);
    }

    /**
     * 按同一时点结清角色可用槽位的完整分钟，先保存亲密度再推进游标。
     *
     * @param role 角色快照
     * @param slots 已保存的槽位
     * @param now 当前 Unix 秒
     * @return 原客户端按同等亲密增量合并的精灵编号
     */
    private Map<Integer, List<String>> settle(LocalRole role, Map<Integer, Slot> slots, long now) {
        Map<Integer, List<String>> gains = new LinkedHashMap<>();
        for (Map.Entry<Integer, Slot> entry : slots.entrySet()) {
            int index = entry.getKey();
            Slot slot = entry.getValue();
            long elapsed = (now - slot.creditedAt) / 60;
            if (elapsed <= 0 || !unlocked(role, index)) {
                continue;
            }
            int award = (int) Math.min(elapsed, MAX_MINUTES_PER_SETTLEMENT) * EXPERIENCE_PER_MINUTE;
            LocalElf elf = elves.findOwned(role.id, slot.elfId).orElseThrow(IllegalStateException::new);
            if (ElfTrainingService.advanceIntimacy(elf, elf.training.path("loveexp").asLong() + award)) {
                if (elves.saveTraining(role.id, elf.id, elf.configId, elf.training) != 1) {
                    throw new IllegalStateException("精灵跟随亲密度未写入存档");
                }
                gains.computeIfAbsent(award, unused -> new ArrayList<>()).add(elf.id);
            }
            long credited = elapsed > MAX_MINUTES_PER_SETTLEMENT ? now : slot.creditedAt + elapsed * 60;
            jdbc.update("UPDATE local_elf_follow SET credited_at=? WHERE role_id=? AND position=?", credited, role.id, index);
            entry.setValue(new Slot(slot.elfId, credited));
        }
        return gains;
    }

    /**
     * 返回原客户端识别的锁定、可用和已配置槽位。
     *
     * @param role 角色快照
     * @param slots 绑定的精灵
     * @return 三个槽位状态或实例编号
     */
    private static List<String> visible(LocalRole role, Map<Integer, Slot> slots) {
        List<String> result = new ArrayList<>(SLOT_STAGES.length);
        for (int index = 1; index <= SLOT_STAGES.length; index++) {
            Slot slot = slots.get(index);
            result.add(!unlocked(role, index) ? "lock" : slot == null ? "unlock" : slot.elfId);
        }
        return result;
    }

    /**
     * 校验底包训练师阶段和等级门槛。
     *
     * @param role 角色快照
     * @param index 目标槽位编号
     * @return 是否已开放该槽位
     */
    private static boolean unlocked(LocalRole role, long index) {
        return index > 0 && index <= SLOT_STAGES.length && role.level >= REQUIRED_LEVEL
                && role.stage >= SLOT_STAGES[(int) index - 1];
    }

    /**
     * 读取角色跟随实例以及已结算时点。
     *
     * @param roleId 角色编号
     * @return 按位置索引的已占用槽位
     */
    private Map<Integer, Slot> slots(String roleId) {
        Map<Integer, Slot> slots = new LinkedHashMap<>();
        jdbc.query("SELECT position,elf_id,credited_at FROM local_elf_follow WHERE role_id=? ORDER BY position",
                row -> { slots.put(row.getInt(1), new Slot(row.getString(2), row.getLong(3))); }, roleId);
        return slots;
    }

    /**
     * 插入已经校验归属的跟随精灵。
     *
     * @param roleId 角色编号
     * @param position 槽位
     * @param value 实例和结算时间
     */
    private void insert(String roleId, int position, Slot value) {
        jdbc.update("INSERT INTO local_elf_follow(role_id,position,elf_id,credited_at) VALUES(?,?,?,?)",
                roleId, position, value.elfId, value.creditedAt);
    }

    /**
     * 清除指定位置的跟随精灵。
     *
     * @param roleId 角色编号
     * @param position 槽位
     */
    private void remove(String roleId, int position) {
        jdbc.update("DELETE FROM local_elf_follow WHERE role_id=? AND position=?", roleId, position);
    }

    /**
     * 检查跟随变更是否已处理。
     *
     * @param roleId 角色编号
     * @param key 原请求唯一键
     * @return 已经提交过该请求时为真
     */
    private boolean recorded(String roleId, String key) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM local_elf_follow_action WHERE role_id=? AND request_key=?",
                Integer.class, roleId, key);
        return found != null && found != 0;
    }

    /**
     * 在槽位提交事务内保存协议回执。
     *
     * @param roleId 角色编号
     * @param key 原请求唯一键
     */
    private void record(String roleId, String key) {
        jdbc.update("INSERT INTO local_elf_follow_action(role_id,request_key) VALUES(?,?)", roleId, key);
    }

    /**
     * 返回没有状态变更的协议错误。
     *
     * @param errno 原协议错误码
     * @return 错误结果
     */
    private static Result failure(int errno) {
        return new Result(errno, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * 跟随槽位的实际精灵编号与亲密度结算时点。
     *
     * @author tongruiyuan
     * @since 2026-11-19 15:02
     */
    private static final class Slot {
        private final String elfId;
        private final long creditedAt;

        /**
         * 初始化槽位值。
         *
         * @param elfId 实例编号
         * @param creditedAt Unix 秒结算时点
         */
        private Slot(String elfId, long creditedAt) {
            this.elfId = elfId;
            this.creditedAt = creditedAt;
        }
    }

    /**
     * 已提交的跟随数据和亲密度变化。
     *
     * @author tongruiyuan
     * @since 2026-11-19 15:02
     */
    public static final class Result {
        public final int errno;
        public final List<String> slots;
        public final Map<Integer, List<String>> gains;

        /**
         * 生成可同步到原客户端的结果。
         *
         * @param errno 原协议错误码
         * @param slots 所有槽位状态
         * @param gains 已结算的亲密度增加量与对应精灵
         */
        private Result(int errno, List<String> slots, Map<Integer, List<String>> gains) {
            this.errno = errno;
            this.slots = slots;
            this.gains = gains;
        }
    }
}
