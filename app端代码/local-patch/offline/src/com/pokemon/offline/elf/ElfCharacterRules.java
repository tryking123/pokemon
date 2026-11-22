package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 精灵特性（协议字段 character，整数数组）本地规则。
 *
 * <p>物种固有特性读取模板 character 数组，按原客户端星级规则选取等级；存档 training.character 仅作为额外特性。
 * 无显式数组的旧模板读取底包特性目录，{@code charactername} 仅供文字显示。属性型特性的固定加值与千分比加成落在
 * {@link ElfCombatGrowth#attribute}，被战力结算与开战快照共用，保证两侧口径一致；
 * 效果型特性的触发编号在开战初始化并入 fighter 的 trigger 字段，去重口径与研究共鸣、
 * 极巨化被动一致。</p>
 *
 * <p>有效被动目录从底包及已核验迁移表生成。未在属性规则表登记的特性不额外编造属性加值；
 * 只有客户端支持的编号才能按 {@code identityTrigger} 配置透传为开战触发。</p>
 *
 * @author tongruiyuan
 * @since 2026-11-21
 */
public final class ElfCharacterRules {

    /** 特性规则表根节点：rules 为编号规则，identityTrigger 控制未登记编号是否透传自身。 */
    private static final JsonNode DATA = ElfTrainingCatalog.load("elf/character.json");

    /** 本地特性规则表：特性编号 -> {flat:六项固定加值, percent:六项千分比加成, trigger:触发编号数组}。 */
    private static final JsonNode RULES = DATA.path("rules");

    /** 未登记编号是否把自身编号并入 trigger；关闭时退回“只认 rules 条目”的行为。 */
    private static final boolean IDENTITY_TRIGGER = DATA.path("identityTrigger").asBoolean(false);

    /** 与客户端被动表核对过的编号，防止文字编号导致阵容初始化中断。 */
    private static final Set<Integer> VALID_TRIGGERS = validTriggers();

    /**
     * 读取随包有效被动编号。
     *
     * @return 客户端支持的被动编号集合
     */
    private static Set<Integer> validTriggers() {
        Set<Integer> ids = new HashSet<>();
        for (JsonNode id : DATA.path("validTriggers")) {
            ids.add(id.asInt());
        }
        return ids;
    }

    /** 防止创建规则计算对象。 */
    private ElfCharacterRules() {
    }

    /**
     * 读取存档明确保存的额外特性编号列表；物种固有特性由客户端自行按星级登记。
     *
     * @param elf 精灵存档
     * @return 额外特性编号；去重并排除无效编号，旧档未配置时为空列表
     */
    public static List<Integer> extraAbilities(LocalElf elf) {
        List<Integer> saved = elf.persistedCharacters();
        List<Integer> validSaved = new ArrayList<>();
        for (int ability : saved) {
            if (VALID_TRIGGERS.contains(ability) || RULES.has(Integer.toString(ability))) {
                validSaved.add(ability);
            }
        }
        return validSaved;
    }

    /**
     * 读取按原客户端星级规则计算的物种特性及显式额外特性。
     *
     * @param elf 精灵存档
     * @return 当前星级下有效且去重的特性编号
     */
    public static List<Integer> abilities(LocalElf elf) {
        List<Integer> extra = extraAbilities(elf);
        JsonNode species = ElfTrainingCatalog.row("species", elf.configId);
        JsonNode configured = species.path("character");
        if (!configured.isArray()) {
            configured = DATA.path("species").path(Integer.toString(elf.configId));
        }
        if (configured.isArray()) {
            List<Integer> abilities = new ArrayList<>(extra);
            int star = elf.training.path("star").asInt(1);
            int featuresLv = species.path("featureslv").asInt(DATA.path("featuresLv")
                    .path(Integer.toString(elf.configId)).asInt());
            int offset = star >= 10 ? featuresLv : star;
            for (JsonNode id : configured) {
                int ability = id.asInt() > 0 ? id.asInt() + offset : 0;
                if (VALID_TRIGGERS.contains(ability) && !abilities.contains(ability)) {
                    abilities.add(ability);
                }
            }
            return abilities;
        }
        return extra;
    }

    /**
     * 汇总特性对指定六项属性的固定加值。
     *
     * @param elf 精灵存档
     * @param attr 六项属性名
     * @return 固定加值合计；规则缺失或编号未知时为零
     */
    public static int flatBonus(LocalElf elf, String attr) {
        int total = 0;
        for (int ability : abilities(elf)) {
            total += RULES.path(Integer.toString(ability)).path("flat").path(attr).asInt();
        }
        return total;
    }

    /**
     * 汇总特性对指定六项属性的千分比加成。
     *
     * @param elf 精灵存档
     * @param attr 六项属性名
     * @return 千分比合计；规则缺失或编号未知时为零
     */
    public static int percentBonus(LocalElf elf, String attr) {
        int total = 0;
        for (int ability : abilities(elf)) {
            total += RULES.path(Integer.toString(ability)).path("percent").path(attr).asInt();
        }
        return total;
    }

    /**
     * 将特性及迁移模板达到觉醒门槛的被动并入开战快照，沿用研究共鸣与极巨化被动的去重口径。
     *
     * <p>rules 已有条目的编号按条目内 {@code trigger} 数组并入；无条目时，若
     * {@code identityTrigger} 开关开启，则把特性编号本身作为 trigger 编号并入
     * （与 {@code pcl.json} 共鸣 {@code character} → {@code trigger} 的透传做法一致，
     * 由客户端自身表执行被动）。所有触发编号先核对有效目录，文字编号及未知编号跳过。
     * 迁移模板的 passive 按“觉醒等级、触发编号”成对读取，只追加当前 awakenlv 达到的正编号，
     * 不改变未配置 passive 的旧模板。</p>
     *
     * @param elf 精灵存档
     * @param fighter 开战战斗成员快照
     */
    public static void applyTriggers(LocalElf elf, Map<String, Object> fighter) {
        List<Integer> triggers = new ArrayList<>();
        Object existing = fighter.get("trigger");
        if (existing instanceof Iterable) {
            for (Object value : (Iterable<?>) existing) {
                if (value instanceof Number) {
                    triggers.add(((Number) value).intValue());
                }
            }
        }
        boolean changed = false;
        for (int ability : abilities(elf)) {
            String key = Integer.toString(ability);
            if (RULES.has(key)) {
                // 已登记条目：只按条目内的触发编号走，不再追加特性自身编号。
                JsonNode passive = RULES.path(key).path("trigger");
                if (!passive.isArray()) {
                    continue;
                }
                for (JsonNode id : passive) {
                    int trigger = id.asInt();
                    if (VALID_TRIGGERS.contains(trigger) && !triggers.contains(trigger)) {
                        triggers.add(trigger);
                        changed = true;
                    }
                }
            } else if (IDENTITY_TRIGGER && VALID_TRIGGERS.contains(ability) && !triggers.contains(ability)) {
                // 未登记编号：开关开启时把编号本身透传给客户端，属性加值仍保持为零。
                triggers.add(ability);
                changed = true;
            }
        }
        JsonNode passive = ElfTrainingCatalog.row("species", elf.configId).path("passive");
        if (passive.isArray()) {
            int awaken = elf.training.path("awakenlv").asInt();
            for (int index = 0; index + 1 < passive.size(); index += 2) {
                int threshold = passive.get(index).asInt();
                int trigger = passive.get(index + 1).asInt();
                if (threshold <= awaken && VALID_TRIGGERS.contains(trigger) && !triggers.contains(trigger)) {
                    triggers.add(trigger);
                    changed = true;
                }
            }
        }
        if (changed) {
            fighter.put("trigger", triggers);
        }
    }
}
