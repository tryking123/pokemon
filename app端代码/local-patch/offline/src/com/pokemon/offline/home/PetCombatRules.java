package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宠物原表战斗规则，按实际出战个体、光环槽与天赋阵位生成有效加成。
 * @author tongruiyuan
 * @since 2026-11-22 04:26
 */
public final class PetCombatRules {
    private static final JsonNode RULES=load();

    /** 禁止创建纯规则工具实例。 */
    private PetCombatRules() { }

    /**
     * 读取随包原宠物战斗目录。
     * @return 已核对的原表配置
     */
    private static JsonNode load() {
        try(InputStream input=new ClassPathResource("home/pet-combat.json").getInputStream()) {
            return new ObjectMapper().readTree(input);
        }catch(IOException error){throw new IllegalStateException("宠物战斗配置无法读取",error);}
    }

    /**
     * 识别当前快照是否为合法且已配置的宠物。
     * @param pet 当前队伍宠物
     * @return 是否具有原模板、等级与资质
     */
    public static boolean valid(JsonNode pet) {
        return pet!=null && !pet.path("uid").asText().isEmpty()
                && RULES.path("petTemplates").has(pet.path("id").asText())
                && RULES.path("levels").has(pet.path("level").asText())
                && RULES.path("aptitudes").has(pet.path("aptitudes").asText());
    }

    /**
     * 汇总实际解锁的不同家族光环，保留原属性编号及原单位。
     * @param pet 当前出战宠物
     * @return 原属性编号与累计数值
     */
    public static Map<Integer,Integer> attributes(JsonNode pet) {
        Map<Integer,Integer> result=new LinkedHashMap<>();
        if(!valid(pet))return result;
        int count=activeSlots(pet);
        Set<Integer> families=new HashSet<>();
        JsonNode auras=pet.path("aureole_id");
        for(int i=0;i<Math.min(count,auras.size());i++) {
            JsonNode row=RULES.path("auras").path(auras.get(i).asText());
            if(!row.isObject() || !families.add(row.path("family").asInt()))continue;
            JsonNode values=row.path("attributes");
            for(int n=0;n+1<values.size();n+=2) {
                int code=values.get(n).asInt();
                if(RULES.path("attributeRules").has(Integer.toString(code)))
                    result.merge(code,values.get(n+1).asInt(),Math::addExact);
            }
        }
        return result;
    }

    /**
     * 读取原累加函数所允许的光环槽数，兼容目录中显式声明的门槛。
     * @param pet 当前宠物
     * @return 有效槽数
     */
    private static int activeSlots(JsonNode pet) {
        int count=4;
        if(RULES.path("auraUnlock").path("requireStar").asBoolean())count=Math.min(count,pet.path("star").asInt());
        if(RULES.path("auraUnlock").path("requireLevel").asBoolean())
            count=Math.min(count,RULES.path("levels").path(pet.path("level").asText()).path("aureoleCount").asInt());
        return Math.max(0,count);
    }

    /**
     * 判断是否为六维基础属性的千分比代码。
     * @param code 原属性编号
     * @return 是否与角色百分比共同累加
     */
    public static boolean percent(int code) {
        return "percent".equals(RULES.path("attributeRules").path(Integer.toString(code)).path("mode").asText());
    }

    /**
     * 查询原属性对应的战斗协议字段。
     * @param code 原属性编号
     * @return 原战斗字段名
     */
    public static String field(int code) {
        return RULES.path("attributeRules").path(Integer.toString(code)).path("field").asText();
    }

    /**
     * 判断第二天赋是否达到原光环总阶与全部解锁条件。
     * @param pet 当前宠物
     * @return 是否可以附入第二天赋
     */
    public static boolean secondUnlocked(JsonNode pet) {
        if(!valid(pet))return false;
        JsonNode auras=pet.path("aureole_id");
        int sum=0;
        for(JsonNode aura:auras) {
            JsonNode row=RULES.path("auras").path(aura.asText());
            if(!row.isObject())return false;
            sum+=row.path("rank").asInt();
        }
        int threshold=RULES.path("petTemplates").path(pet.path("id").asText()).path("secondTalentRankSum")
                .asInt(RULES.path("secondTalent").path("requiredAuraRankSum").asInt(10));
        return pet.path("star").asInt()>0 && auras.size()<=activeSlots(pet) && sum>=threshold;
    }

    /**
     * 按精灵当前阵位提取原天赋触发编号，双天赋去重且不改精灵固有特性。
     * @param pet 当前队伍宠物
     * @param position 精灵实际战斗阵位
     * @return 原引擎可执行的触发编号
     */
    public static List<Integer> triggers(JsonNode pet,int position) {
        List<Integer> result=new ArrayList<>();
        if(!valid(pet) || position<1 || position>6)return result;
        talent(pet,"talent","talent_pos",position,result);
        if(secondUnlocked(pet))talent(pet,"talent_2","talent_2_pos",position,result);
        return result;
    }

    /**
     * 追加指定天赋在有效阵位上的原触发。
     * @param pet 当前宠物
     * @param field 天赋编号字段
     * @param slots 已保存的阵位字段
     * @param position 当前阵位
     * @param result 去重后的触发结果
     */
    private static void talent(JsonNode pet,String field,String slots,int position,List<Integer> result) {
        JsonNode rule=RULES.path("talents").path(pet.path(field).asText());
        if(!rule.isObject() || pet.path(slots).size()!=rule.path("positionCount").asInt())return;
        Set<Integer> selected=new HashSet<>();
        for(JsonNode slot:pet.path(slots)) {
            int value=slot.asInt();
            if(value<1 || value>6 || !selected.add(value))return;
        }
        if(!selected.contains(position))return;
        for(JsonNode character:rule.path("characters")) {
            int value=character.asInt();
            if(RULES.path("characterRules").has(Integer.toString(value)) && !result.contains(value))result.add(value);
        }
    }

    /**
     * 生成原普通PVE宠物对象，生命按原等级和资质换算；不在PVE开启抵挡。
     * @param pet 当前队伍宠物
     * @return 原petbtl字段，无有效宠物时为空映射
     */
    public static Map<String,Object> battlePet(JsonNode pet) {
        if(!valid(pet))return Collections.emptyMap();
        long hp=RULES.path("levels").path(pet.path("level").asText()).path("hp").asLong()
                * RULES.path("aptitudes").path(pet.path("aptitudes").asText()).path("hpMultiplierPermille").asLong()/1000;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("sid",pet.path("id").asInt());result.put("uid",pet.path("uid").asText());
        result.put("hp",Math.toIntExact(hp));result.put("percent",0);
        return result;
    }
}
