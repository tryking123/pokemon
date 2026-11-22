package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 原精灵重生页的普通和完美重生。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:49
 */
@Service
public class ElfRebirthService {
    private static final JsonNode RULES = ElfTrainingCatalog.load("elf/rebirth.json");
    private final RoleService roles;
    private final ElfMapper mapper;
    private final ElfService elves;
    private final ElfReleaseService release;
    private final InventoryService inventory;
    private final HangupService hangup;
    private final JdbcTemplate jdbc;

    /** 初始化重生使用的实例、资产、经验池与回执。
     * @param roles 角色锁
     * @param mapper 精灵实例存储
     * @param elves 返回消耗的培养精灵
     * @param release 受保护实例查询
     * @param inventory 道具和容量
     * @param hangup 精灵经验池
     * @param jdbc 重生状态与回执
     */
    public ElfRebirthService(RoleService roles, ElfMapper mapper, ElfService elves, ElfReleaseService release,
                             InventoryService inventory, HangupService hangup, JdbcTemplate jdbc) {
        this.roles = roles;
        this.mapper = mapper;
        this.elves = elves;
        this.release = release;
        this.inventory = inventory;
        this.hangup = hangup;
        this.jdbc = jdbc;
    }

    /** 按原重生按钮扣除重生石、返还预览内物品并重置原实例。
     * @param role 认证角色
     * @param uid 选中的精灵实例
     * @param mode 原按钮类型，1 为完美，2 为普通
     * @param receipt 本次连接与请求号
     * @return 原请求是否成功
     */
    @Transactional
    public boolean rebirth(String role, String uid, int mode, String receipt) {
        roles.lockRole(role);
        if (uid == null || (mode != 1 && mode != 2)) return false;
        List<Boolean> previous = jdbc.query("SELECT elf_id,mode_value FROM local_elf_rebirth WHERE role_id=? AND receipt=?",
                (row, index) -> uid.equals(row.getString(1)) && mode == row.getInt(2), role, receipt);
        if (!previous.isEmpty()) return previous.get(0);
        LocalElf elf = mapper.findOwned(role, uid).orElse(null);
        if (elf == null || !trained(elf) || release.detachedCandidates(role, elf.configId, true).stream().noneMatch(e -> uid.equals(e.id))) return false;

        Refund refund = new Refund(elf, mode);
        long returnedElves = refund.elves.values().stream().mapToLong(Long::longValue).sum();
        if (returnedElves > Math.max(0, inventory.elfCapacity(role) - elves.list(role).size())) return false;
        int stone = RULES.path("cost").path(String.valueOf(mode)).asInt();
        if (!inventory.spendMaterials(role, stone, 1, 0, 0)) return false;
        inventory.grantItemsOrMail(role, "rebirth:" + receipt, refund.items);
        if (refund.experience > 0) hangup.addElfPool(role, refund.experience);
        refund.elves.forEach((id, count) -> {
            for (long i = 0; i < count; i++) elves.grant(role, id);
        });
        ObjectNode reset = elf.training.deepCopy();
        reset.remove(Arrays.asList("loveexp","effort","effort_item","skillBar","skill_cost","skill_lv","awakenHistory",
                "advanceCosts","upstar","veinluck","dynabook","dynalevel","dynapath","dynatype","dynabooklv"));
        reset.put("love", 1).put("star", 0).put("advanced", 0).put("awakenlv", 0).put("veinlevel", 0);
        JsonNode skills = ElfTrainingCatalog.row("species", elf.configId).path("skills");
        if (skills.path(1).asInt() > 0) reset.putArray("skillBar").add(skills.get(1).asInt());
        if (reset.has("ancient")) reset.put("ancient", 0);
        if (jdbc.update("UPDATE local_elf SET level=1,training_data=? WHERE role_id=? AND id=?", reset.toString(), role, uid) != 1)
            throw new IllegalStateException("重生时精灵实例已变化");
        jdbc.update("INSERT INTO local_elf_rebirth(role_id,receipt,elf_id,mode_value) VALUES(?,?,?,?)", role, receipt, uid, mode);
        return true;
    }

    /** 对应原 sprite_model.get_behind 中已经培养的候选精灵。
     * @param elf 当前实例
     * @return 原重生页是否显示该实例
     */
    private boolean trained(LocalElf elf) {
        JsonNode s = elf.training;
        return elf.level > 1 || s.path("star").asInt(1) > 0 || s.path("loveexp").asLong() > 0
                || s.path("advanced").asInt() > 0 || s.path("effort_item").size() > 0
                || s.path("awakenlv").asInt() > 0 || s.path("love").asInt(1) > 1 || s.path("skill_cost").size() > 0;
    }

    /** 根据 recycle_sub2 的两页预览区分按百分比返还的道具与原数返还的素材精灵。 */
    private static final class Refund {
        final Map<Integer,Long> items = new LinkedHashMap<>();
        final Map<Integer,Long> elves = new LinkedHashMap<>();
        final long experience;

        /** 汇总同一来源实例的重生收益。
         * @param elf 通过占用检查的实例
         * @param mode 重生类型
         */
        Refund(LocalElf elf, int mode) {
            JsonNode state = elf.training;
            JsonNode species = ElfTrainingCatalog.row("species", elf.configId);
            int percent = RULES.path("percent").path(String.valueOf(mode)).asInt();
            experience = ElfTrainingService.spentExperience(elf.level) * percent / 100;
            state.path("effort_item").fields().forEachRemaining(e -> add(items, Integer.parseInt(e.getKey()), e.getValue().asLong()));
            for (int star = 1; star <= state.path("star").asInt(1); star++) {
                JsonNode row = ElfTrainingCatalog.row("variable", 1019 + star).path("data7");
                for (int pos = 1; pos + 2 <= row.size(); pos += 3) {
                    if (row.path(String.valueOf(pos)).asInt() == 1)
                        add(items, row.path(String.valueOf(pos + 1)).asInt(), row.path(String.valueOf(pos + 2)).asLong());
                }
            }
            JsonNode growth = ElfTrainingCatalog.row("growth", elf.configId).path("cost");
            for (int step = 0; step < state.path("advanced").asInt(); step++) {
                JsonNode paid = state.path("advanceCosts").path(Integer.toString(step));
                JsonNode pairs = (paid.isMissingNode() ? growth.path(step) : paid).path("items");
                for (int i = 0; i + 1 < pairs.size(); i += 2) add(items, pairs.path(i).asInt(), pairs.path(i + 1).asLong());
            }
            int vein = state.path("veinlevel").asInt();
            for (int i = 1; i <= vein; i++) {
                if (i % 11 == 0) {
                    long count = RULES.path("vein").path(species.path("assess").asText()).path(i/11 - 1).asLong();
                    add(elves, species.path("starsprint").asInt(), count);
                } else {
                    JsonNode pay = ElfTrainingCatalog.row("break", i).path("lighten");
                    add(items, pay.path("2").asInt(), pay.path("3").asLong());
                }
            }
            for (int i = 1; i <= state.path("ancient").asInt(); i++)
                add(items, 30014, ElfTrainingCatalog.row("ancient", i).path("material").asLong());
            int love = state.path("love").asInt(1);
            if (love > 1) {
                long points = state.path("loveexp").asLong();
                for (int i = 1; i < love; i++) points += ElfTrainingCatalog.row("love", i).path("exp").asLong();
                add(items, 10001, points / 50);
            }
            for (JsonNode level : state.path("awakenHistory"))
                for (JsonNode speciesId : level.path("consume_elf_num")) add(elves, speciesId.asInt(), 1);
            for (JsonNode paid : state.path("skill_cost")) {
                int type = paid.path("itype").asInt();
                if (type == 1 || type == 2) add(type == 2 ? elves : items, paid.path("id").asInt(), paid.path("num").asLong());
            }
            items.replaceAll((id, quantity) -> quantity * percent / 100);
            items.values().removeIf(quantity -> quantity == 0);
        }

        /** 合并预览中同种来源的数量。
         * @param totals 预览中的道具或精灵
         * @param id 实际模板
         * @param quantity 原始数量
         */
        private static void add(Map<Integer,Long> totals, int id, long quantity) {
            if (id > 0 && quantity > 0) totals.merge(id, quantity, Math::addExact);
        }
    }
}
