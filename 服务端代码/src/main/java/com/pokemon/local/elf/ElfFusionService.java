package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按合体配方消耗两个精灵实例，并办理原合体页的本地合体器补给。
 * @author tongruiyuan
 * @since 2026-11-20 13:18
 */
@Service
public class ElfFusionService {
    private static final int DEVICE = 21086;
    private static final int PRICE = 5000;
    private static final int INVALID = 100021;
    private static final List<String> STATS = Arrays.asList("hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed");
    private final RoleService roles;
    private final ElfService elves;
    private final ElfReleaseService release;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode recipes;

    /** 加载原配方并绑定精灵、资产和回执存档。
     * @param roles 角色与事务锁
     * @param elves 精灵实例存档
     * @param release 材料占用检查和消耗
     * @param inventory 道具、钻石与邮件
     * @param jdbc 本地存档
     * @param json 原配方解析器
     * @throws IOException 配方资源无法读取
     */
    public ElfFusionService(RoleService roles, ElfService elves, ElfReleaseService release,
                            InventoryService inventory, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.roles = roles;
        this.elves = elves;
        this.release = release;
        this.inventory = inventory;
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream input = new ClassPathResource("elf/fusion.json").getInputStream()) {
            this.recipes = json.readTree(input);
        }
    }

    /** 在同一角色事务内校验配方、扣除两只材料和合体器，生成全新一级精灵。
     * @param roleId 角色编号
     * @param target 合体结果的原精灵配置编号
     * @param ids 玩家选定的两个精灵实例
     * @param receipt 当前连接和请求编号
     * @return 原协议错误码
     */
    @Transactional
    public int fuse(String roleId, int target, List<?> ids, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        JsonNode recipe = recipes.path(Integer.toString(target));
        if (role.stage < 1 || recipe.isMissingNode() || ids == null || ids.size() != 2
                || !(ids.get(0) instanceof String) || !(ids.get(1) instanceof String) || ids.get(0).equals(ids.get(1))) {
            return INVALID;
        }
        List<String> selected = Arrays.asList((String) ids.get(0), (String) ids.get(1));
        String signature = json.valueToTree(Arrays.asList(target, selected)).toString();
        String previous = receipt(roleId, receipt);
        if (previous != null) return previous.equals(signature) ? 0 : INVALID;
        List<LocalElf> materials = new ArrayList<>(2);
        for (JsonNode demand : recipe.path("materials")) {
            LocalElf found = release.detachedCandidates(roleId, demand.path("id").asInt(), true).stream()
                    .filter(e -> selected.contains(e.id) && meets(e, demand)).findFirst().orElse(null);
            if (found == null || materials.contains(found)) return INVALID;
            materials.add(found);
        }
        if (materials.size() != 2 || !inventory.spendMaterials(roleId, recipe.path("item").asInt(), 1, 0, 0)) return INVALID;
        release.consumeExchangeCandidates(roleId, materials);
        elves.grant(roleId, target);
        save(roleId, receipt, signature);
        return 0;
    }

    /** 计算原材料详情页显示的六项个体值总和以及培养门槛。
     * @param elf 真实精灵实例
     * @param demand 原配方中的一只材料
     * @return 是否满足全部养成条件
     */
    private boolean meets(LocalElf elf, JsonNode demand) {
        long individual = 0;
        for (String stat : STATS) individual += elf.training.path("individual").path(stat).asLong();
        return elf.level >= demand.path("level").asInt() && elf.training.path("star").asInt(1) >= demand.path("star").asInt()
                && elf.training.path("advanced").asInt() >= demand.path("advanced").asInt()
                && elf.training.path("awakenlv").asInt() >= demand.path("awake").asInt()
                && individual >= demand.path("individual").asLong();
    }

    /** 办理终身首次免费补给或当前自然周确认购买，背包不足时奖励进入邮件。
     * @param roleId 角色编号
     * @param action 一为免费领取，二为付费购买
     * @param receipt 当前连接和请求编号
     * @return 本地补给结果与原页面提示文案
     */
    @Transactional
    public ObjectNode supply(String roleId, int action, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        if (role.stage < 1 || (action != 1 && action != 2)) return result(INVALID, "合体器补给在一转后开放");
        String signature = "supply-" + action;
        String previous = receipt(roleId, receipt);
        if (previous != null) return previous.equals(signature) ? result(0, "合体器已发放") : result(INVALID, "本次请求与原操作不符");
        String limit = action == 1 ? "first" : "week:" + LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (receipt(roleId, limit) != null) return result(INVALID, action == 1 ? "首次合体器已经领取" : "本周合体器已经购买");
        if (action == 1 && !hasPair(elves.list(roleId))) return result(INVALID, "需同时拥有一份配方的两种80级材料精灵");
        if (action == 2 && !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, PRICE)) return result(INVALID, "购买需5000钻石");
        inventory.grantItemsOrMail(roleId, "fusion-device:" + limit, Collections.singletonMap(DEVICE, 1L));
        save(roleId, limit, signature);
        save(roleId, receipt, signature);
        return result(0, "合体器已发放，背包满时请查收邮件");
    }

    /** 核对任一完整配方的两种八十级素材是否均属于本角色。
     * @param owned 角色全部已持有精灵
     * @return 是否获得免费补给资格
     */
    private boolean hasPair(List<LocalElf> owned) {
        for (JsonNode recipe : recipes) {
            boolean complete = true;
            for (JsonNode requirement : recipe.path("materials")) {
                if (owned.stream().noneMatch(elf -> elf.configId == requirement.path("id").asInt() && elf.level >= 80)) {
                    complete = false;
                    break;
                }
            }
            if (complete) return true;
        }
        return false;
    }

    /** 查询已提交动作的签名。
     * @param roleId 角色编号
     * @param key 限次标志或连接请求编号
     * @return 已提交签名，未提交时为空
     */
    private String receipt(String roleId, String key) {
        List<String> saved = jdbc.queryForList("SELECT action_signature FROM local_elf_fusion_receipt WHERE role_id=? AND request_key=?", String.class, roleId, key);
        return saved.isEmpty() ? null : saved.get(0);
    }

    /** 记录当前原子事务实际办理的合体或补给。
     * @param roleId 角色编号
     * @param key 回执编号
     * @param signature 实例组合或补给方式
     */
    private void save(String roleId, String key, String signature) {
        jdbc.update("INSERT INTO local_elf_fusion_receipt(role_id,request_key,action_signature) VALUES(?,?,?)", roleId, key, signature);
    }

    /** 生成本地补给响应。
     * @param errno 原客户端错误码
     * @param message 面板提示文案
     * @return 本地响应
     */
    private ObjectNode result(int errno, String message) { return json.createObjectNode().put("errno", errno).put("message", message); }
}
