package com.pokemon.offline.activitylevel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 根据实际冒险进度与活动窗口结算原版闯关礼包。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:30
 */
@Service
public class LevelGiftService {
    public static final int ACTIVITY = 10024;
    public static final int ELF = 2;
    private static final int ITEM = 1;
    private static final int DIAMONDS = 104;
    private final ActivityService activity;
    private final AdventureProgressService adventure;
    private final LevelGiftMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ElfService elfs;
    private final List<Tier> tiers;

    /**
     * 从客户端的闯关礼包配置加载原序号、关卡和奖励。
     *
     * @param activity 运营活动周期服务
     * @param adventure 冒险存档读取服务
     * @param mapper 奖励档位领取存档
     * @param inventory 背包发放服务
     * @param roles 角色服务及交易锁
     * @param elfs 精灵发放服务
     * @param json 配置解析器
     * @throws IOException 配置无法读取
     */
    LevelGiftService(ActivityService activity, AdventureProgressService adventure, LevelGiftMapper mapper,
                     InventoryService inventory, RoleService roles, ElfService elfs, ObjectMapper json) throws IOException {
        this.activity = activity;
        this.adventure = adventure;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.elfs = elfs;
        try (InputStream content = new ClassPathResource("activitylevel/chuangguanlibao.json").getInputStream()) {
            List<Tier> loaded = json.readValue(content, new TypeReference<List<Tier>>() { });
            int lastGrade = 10000;
            for (Tier tier : loaded) {
                if (tier.grade <= lastGrade || tier.rew.size() != 6 || tier.rew.get(3) != ITEM
                        || tier.rew.get(5) <= 0 || tier.rew.get(2) <= 0) {
                    throw new IllegalStateException("闯关礼包配置无效：" + tier.grade);
                }
                int type = tier.rew.get(0);
                int id = tier.rew.get(1);
                if (!(type == DIAMONDS && id == 0 || type == ELF && tier.rew.get(2) == 1
                        && FirstBattleData.starter(id) != null)) {
                    throw new IllegalStateException("闯关礼包奖励类型无效：" + tier.grade);
                }
                inventory.itemType(tier.rew.get(4));
                tier.rew = Collections.unmodifiableList(tier.rew);
                lastGrade = tier.grade;
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("闯关礼包配置为空");
            }
            this.tiers = Collections.unmodifiableList(loaded);
        }
    }

    /**
     * 读取固定顺序的档位与当前的领取标记。
     *
     * @param roleId 角色编号
     * @return 本周期的档位、是否领完及刷新时间
     */
    public State state(String roleId) {
        ActivityPeriod period = activity.cycle(ACTIVITY);
        Set<Integer> flags = mapper.claimed(roleId, period.start);
        List<Integer> tierFlags = new ArrayList<>(tiers.size());
        for (int index = 0; index < tiers.size(); index++) {
            tierFlags.add(flags.contains(index + 1) ? 1 : 0);
        }
        return new State(tiers, tierFlags, !tierFlags.contains(0), period.end);
    }

    /**
     * 在同一角色事务内核实资格、发放全部原始奖励并写入领取记录。
     *
     * @param roleId 角色编号
     * @param index 原始礼包序号，从一计数
     * @return 已发放的档位，条件不满足时为空
     */
    @Transactional
    public Tier claim(String roleId, long index) {
        if (index < 1 || index > tiers.size()) {
            return null;
        }
        roles.lockRole(roleId);
        int tierIndex = (int) index;
        Tier tier = tiers.get(tierIndex - 1);
        ActivityPeriod period = activity.cycle(ACTIVITY);
        long now = Instant.now().getEpochSecond();
        if (now < period.start || now >= period.end || adventure.nextMission(roleId) <= tier.grade
                || mapper.claimed(roleId, period.start).contains(tierIndex)) {
            return null;
        }
        int item = tier.rew.get(4);
        int quantity = tier.rew.get(5);
        long owned = inventory.count(roleId, item);
        if (owned > Integer.MAX_VALUE - quantity) {
            return null;
        }
        String receipt = "level-gift:" + period.start + ":" + tierIndex;
        if (tier.rew.get(0) == ELF) {
            if (elfs.list(roleId).size() < inventory.elfCapacity(roleId)) {
                elfs.grant(roleId, tier.rew.get(1));
            } else {
                long until = now + 30L * 86400;
                inventory.createRewardMail(roleId, java.util.UUID.nameUUIDFromBytes(
                                (roleId + ":" + receipt + ":elf").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                        "闯关礼包奖励", "精灵背包已满，腾出位置后可领取。", now, until,
                        java.util.Arrays.asList(ELF, tier.rew.get(1), 1));
            }
        } else {
            roles.addCurrency(roleId, 0, tier.rew.get(2), 0);
        }
        // 道具满包时余量转邮件，避免整档礼包因一个堆叠位不足而领不到。
        inventory.grantItemsOrMail(roleId, receipt, Collections.singletonMap(item, (long) quantity));
        mapper.insertClaim(roleId, period.start, tierIndex);
        return tier;
    }

    /**
     * 底包中的礼包档位与原始奖励三元组。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:30
     */
    public static final class Tier {
        /** 要求完成的最后一关。 */
        public int grade;
        /** 按类型、编号、数量组成的奖励数组。 */
        public List<Integer> rew;
    }

    /**
     * 当前活动周期的档位和领取状态。
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:30
     */
    public static final class State {
        public final List<Tier> tiers;
        public final List<Integer> flags;
        public final boolean all;
        public final long end;

        /**
         * 保存供网关编码的领取状态。
         *
         * @param tiers 原始礼包档位
         * @param flags 领取标记列表
         * @param all 是否领取了全部档位
         * @param end 本周期结束时间
         */
        private State(List<Tier> tiers, List<Integer> flags, boolean all, long end) {
            this.tiers = tiers;
            this.flags = flags;
            this.all = all;
            this.end = end;
        }
    }
}
