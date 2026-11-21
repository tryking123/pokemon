package com.pokemon.local.dojo;

import com.pokemon.local.dojo.api.entity.DojoPlacement;
import com.pokemon.local.dojo.api.vo.DojoTeamSlot;
import com.pokemon.local.elf.ElfMapper;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.role.RoleService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地道馆阵容和助阵编组服务。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:56
 */
@Service
public class DojoService {
    /** 底包 cheer_proto 共有 24 个槽，槽位一到二十四对应道馆 1001 到 1024。 */
    private static final int AID_SLOTS = 24;
    private static final int FIRST_DOJO = 1001;
    private static final int INVALID = 100021;
    private static final int LOCKED_AID = 251422;
    private static final int ALREADY_IN_AID = 18;
    private static final int ALREADY_IN_DOJO = 19;
    private static final int DOJO_BATTLE_ACTIVE = 2011;
    private static final int DOJO_TEAM_LIMIT = 6;
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");

    private final DojoMapper mapper;
    private final RoleService roles;
    private final ElfMapper elves;
    private final DojoCombatService combat;

    /**
     * 创建道馆与助阵编组服务。
     *
     * @param mapper 道馆和助阵存档
     * @param roles 角色服务
     * @param elves 角色精灵存档
     * @param combat 道馆对战状态
     */
    DojoService(DojoMapper mapper, RoleService roles, ElfMapper elves, DojoCombatService combat) {
        this.mapper = mapper;
        this.roles = roles;
        this.elves = elves;
        this.combat = combat;
    }

    /**
     * 查询用于同步的道馆记录与阵容。
     *
     * @param roleId 角色标识
     * @return 最新的道馆进度
     */
    public DojoSnapshot snapshot(String roleId) {
        DojoMapper.Progress progress = mapper.progress(roleId);
        boolean today = progress.day == LocalDate.now(SERVER_ZONE).toEpochDay();
        return new DojoSnapshot(progress.dojo, progress.history, progress.tactics, Integer.bitCount(progress.defeated),
                today ? progress.failures : 0, today ? progress.practices : 0, mapper.team(roleId));
    }

    /**
     * 查询角色的全部助阵记录。
     *
     * @param roleId 角色标识
     * @return 槽位有序的助阵列表
     */
    public List<DojoPlacement> aids(String roleId) {
        return mapper.aid(roleId);
    }

    /**
     * 核对精灵是否正参与当前道馆挑战。
     *
     * @param roleId 角色编号
     * @param elfId 精灵编号
     * @return 是否属于进行中挑战的队伍
     */
    public boolean isFighting(String roleId, String elfId) {
        return combat.active(roleId) && mapper.team(roleId).stream().anyMatch(member -> member.elfId.equals(elfId));
    }

    /**
     * 判断一个精灵是否已安排在助阵。
     *
     * @param roleId 角色标识
     * @param elfId 精灵编号
     * @return 是否占据助阵槽
     */
    public boolean isAiding(String roleId, String elfId) {
        return mapper.aid(roleId).stream().anyMatch(placement -> placement.elfId.equals(elfId));
    }

    /**
     * 持久化新道馆阵容，位置和成员必须唯一，且成员由角色持有。
     *
     * @param roleId 角色标识
     * @param tactics 原客户端阵法类型
     * @param requested 客户端提交的全量阵容
     * @return 原协议错误编号，零为成功
     */
    @Transactional
    public int setTeam(String roleId, long tactics, List<DojoTeamSlot> requested) {
        if (tactics < 1 || tactics > 7 || requested.size() > DOJO_TEAM_LIMIT) {
            return INVALID;
        }
        roles.lockRole(roleId);
        if (combat.active(roleId)) {
            return DOJO_BATTLE_ACTIVE;
        }
        List<LocalElf> owned = elves.list(roleId);
        List<DojoPlacement> aid = mapper.aid(roleId);
        List<DojoPlacement> team = new ArrayList<>();
        Set<Long> positions = new HashSet<>();
        Set<Long> battleIds = new HashSet<>();
        Set<String> elfIds = new HashSet<>();
        for (DojoTeamSlot requestedSlot : requested) {
            LocalElf elf = find(owned, requestedSlot.elfId);
            if (elf == null || mapper.traveling(roleId,elf.id) || requestedSlot.position < 1 || requestedSlot.position > DOJO_TEAM_LIMIT
                    || requestedSlot.battleId < 1 || requestedSlot.battleId > DOJO_TEAM_LIMIT
                    || !positions.add(requestedSlot.position) || !battleIds.add(requestedSlot.battleId)
                    || !elfIds.add(elf.id)) {
                return INVALID;
            }
            if (aid.stream().anyMatch(placement -> placement.elfId.equals(elf.id))) {
                return ALREADY_IN_AID;
            }
            team.add(new DojoPlacement(elf.id, elf.configId, (int) requestedSlot.position, (int) requestedSlot.battleId));
        }
        DojoMapper.Progress progress = mapper.progress(roleId);
        mapper.replaceTeam(roleId, team);
        mapper.saveProgress(roleId, new DojoMapper.Progress(progress.history, progress.dojo, (int) tactics,
                progress.defeated, progress.day, progress.failures, progress.practices));
        return 0;
    }

    /**
     * 保存、替换或撤下某个已解锁槽位的助阵精灵。
     *
     * @param roleId 角色标识
     * @param slot 助阵槽位
     * @param elfId 精灵实例编号，空值表示撤下
     * @return 原协议错误编号，零为成功
     */
    @Transactional
    public int setAid(String roleId, long slot, String elfId) {
        if (!validSlot(slot)) {
            return INVALID;
        }
        roles.lockRole(roleId);
        if (!unlocked(mapper.progress(roleId), slot)) {
            return LOCKED_AID;
        }
        if (elfId == null || elfId.isEmpty()) {
            mapper.deleteAid(roleId, (int) slot);
            return 0;
        }
        LocalElf elf = find(elves.list(roleId), elfId);
        if (elf == null || mapper.traveling(roleId,elf.id) || mapper.rocketMember(roleId,elf.id) || elf.slot > 0) {
            return INVALID;
        }
        if (mapper.team(roleId).stream().anyMatch(placement -> placement.elfId.equals(elfId))) {
            return ALREADY_IN_DOJO;
        }
        for (DojoPlacement placement : mapper.aid(roleId)) {
            if (placement.elfId.equals(elfId)) {
                return placement.position == slot ? 0 : ALREADY_IN_AID;
            }
        }
        mapper.deleteAid(roleId, (int) slot);
        mapper.addAid(roleId, (int) slot, elfId);
        return 0;
    }

    /**
     * 交换两个已解锁的助阵位置，也支持移至空槽。
     *
     * @param roleId 角色标识
     * @param first 源槽位
     * @param second 目标槽位
     * @return 原协议错误编号，零为成功
     */
    @Transactional
    public int swapAid(String roleId, long first, long second) {
        if (!validSlot(first) || !validSlot(second)) {
            return INVALID;
        }
        roles.lockRole(roleId);
        DojoMapper.Progress progress = mapper.progress(roleId);
        if (!unlocked(progress, first) || !unlocked(progress, second)) {
            return LOCKED_AID;
        }
        List<DojoPlacement> aid = mapper.aid(roleId);
        DojoPlacement source = aid.stream().filter(placement -> placement.position == first).findFirst().orElse(null);
        DojoPlacement target = aid.stream().filter(placement -> placement.position == second).findFirst().orElse(null);
        if (source == null && target == null) {
            return INVALID;
        }
        if (first == second) {
            return 0;
        }
        mapper.deleteAid(roleId, (int) first);
        mapper.deleteAid(roleId, (int) second);
        if (source != null) {
            mapper.addAid(roleId, (int) second, source.elfId);
        }
        if (target != null) {
            mapper.addAid(roleId, (int) first, target.elfId);
        }
        return 0;
    }

    /**
     * 在角色精灵列表中查找实例。
     *
     * @param owned 角色拥有的精灵
     * @param id 当前请求引用的精灵编号
     * @return 持有的实例，无有效编号或未拥有时为空
     */
    private LocalElf find(List<LocalElf> owned, String id) {
        return owned.stream().filter(elf -> elf.id.equals(id)).findFirst().orElse(null);
    }

    /**
     * 验证助阵槽位是否出现在底包配置中。
     *
     * @param slot 客户端槽位
     * @return 是否在配置范围内
     */
    private boolean validSlot(long slot) {
        return slot >= 1 && slot <= AID_SLOTS;
    }

    /**
     * 按 cheer_proto 记录的道馆编号检查解锁线。
     *
     * @param progress 已保存道馆进度
     * @param slot 已验证范围的助阵槽位
     * @return 历史通关记录是否覆盖该槽位
     */
    private boolean unlocked(DojoMapper.Progress progress, long slot) {
        return progress.history >= FIRST_DOJO + slot - 1;
    }
}
