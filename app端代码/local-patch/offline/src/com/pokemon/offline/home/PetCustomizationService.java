package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.home.api.vo.PetCustomizeVO;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 当前宠物定制 Service，校验本人实例与原配置并原子保存，不消耗养成材料。
 * @author tongruiyuan
 * @since 2026-11-22 03:53
 */
@Service
public final class PetCustomizationService {
    private final AccountService accounts;
    private final RoleService roles;
    private final HomeService home;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode catalog;

    /**
     * 装配角色存档与合法定制目录。
     * @param accounts 当前账号会话
     * @param roles 角色归属与事务锁
     * @param home 幂等保存回执
     * @param jdbc 私有数据库
     * @param json 配置及存档解析器
     * @throws IOException 随包目录读取失败
     */
    public PetCustomizationService(AccountService accounts, RoleService roles, HomeService home,
            JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.accounts=accounts; this.roles=roles; this.home=home; this.jdbc=jdbc; this.json=json;
        try (InputStream source=new ClassPathResource("home/pet-customization.json").getInputStream()) {
            catalog=json.readTree(source);
        }
    }

    /**
     * 查询当前账号指定宠物与可用定制选项。
     * @param authorization 当前账号会话
     * @param roleId 角色编号
     * @param petId 宠物实例编号
     * @return 当前宠物与合法选项
     */
    public ObjectNode read(String authorization,String roleId,String petId) {
        owned(authorization,roleId);
        ObjectNode out=json.createObjectNode();
        out.set("current",pet(state(roleId),petId).deepCopy());
        out.set("catalog",catalog.deepCopy());
        return out;
    }

    /**
     * 校验并保存当前宠物配置，同一请求重复提交不重复改动存档。
     * @param authorization 当前账号会话
     * @param vo 角色、宠物及定制选项
     * @return 保存后的宠物
     */
    @Transactional
    public ObjectNode save(String authorization,PetCustomizeVO vo) {
        if(vo==null)throw invalid("缺少宠物定制参数");
        owned(authorization,vo.roleId);
        roles.lockRole(vo.roleId);
        if(vo.requestId==null || !vo.requestId.matches("[A-Za-z0-9_-]{1,64}"))throw invalid("保存编号无效");
        ObjectNode signature=json.valueToTree(vo);
        String key="pet-custom:"+vo.requestId;
        String previous=home.reply(vo.roleId,key);
        if(previous!=null) {
            JsonNode receipt=parse(previous);
            if(!signature.equals(receipt.path("request")))throw invalid("同一保存编号不能更改参数");
            ObjectNode out=json.createObjectNode();out.set("current",receipt.path("current"));return out;
        }
        ObjectNode state=state(vo.roleId),pet=pet(state,vo.petId);
        int level=integer(vo.level),aptitude=integer(vo.aptitudes),talent=integer(vo.talent),talent2=integer(vo.talent2);
        if(level<catalog.path("level").path("min").asInt() || level>catalog.path("level").path("max").asInt()
                || option("aptitudes",aptitude)==null || option("talents",talent)==null
                || talent2!=0 && option("talents",talent2)==null)throw invalid("等级、资质或天赋不在可选范围");
        if(vo.aureoles==null || !vo.aureoles.isArray() || vo.aureoles.size()>4)throw invalid("光环最多设置四条");
        Set<Integer> families=new HashSet<>();
        ArrayNode auras=json.createArrayNode();
        for(JsonNode value:vo.aureoles) {
            int id=integer(value);
            if(option("aureoles",id)==null || !families.add(id/1000))throw invalid("光环无效或存在相同类型");
            auras.add(id);
        }
        pet.put("level",level).put("aptitudes",aptitude).put("talent",talent).put("talent_2",talent2);
        positions(pet,talent,"talent_pos");positions(pet,talent2,"talent_2_pos");
        pet.set("aureole_id",auras);
        state.with("petCustomizations").with(vo.petId).put("talent2Disabled",talent2==0);
        if(state.path("washPending").isObject())((ObjectNode)state.path("washPending")).remove(vo.petId);
        jdbc.update("MERGE INTO local_home_breeding(role_id,state_json) KEY(role_id) VALUES(?,?)",vo.roleId,state.toString());
        ObjectNode receipt=json.createObjectNode();receipt.set("request",signature);receipt.set("current",pet.deepCopy());
        home.record(vo.roleId,key,receipt.toString());
        ObjectNode out=json.createObjectNode();out.set("current",pet.deepCopy());return out;
    }

    /**
     * 验证当前账号拥有角色。
     * @param authorization 账号会话
     * @param roleId 角色编号
     */
    private void owned(String authorization,String roleId) {
        String account=accounts.requireAccount(authorization).id;
        if(roleId==null || !roles.findById(roleId).filter(r->account.equals(r.accountId)).isPresent())
            throw new ApiException(HttpStatus.NOT_FOUND,"ROLE_NOT_FOUND","当前账号未找到该角色");
    }

    /**
     * 从合法目录按编号查询选项。
     * @param group 选项类别
     * @param id 原配置编号
     * @return 原选项，未找到时为空
     */
    private JsonNode option(String group,int id) {
        for(JsonNode row:catalog.path(group))if(row.path("id").asInt()==id)return row;
        return null;
    }

    /**
     * 按原天赋选位数生成合法且稳定的阵位，保留未变化的合法选择。
     * @param pet 当前宠物
     * @param talent 天赋编号
     * @param field 保存阵位的字段
     */
    private void positions(ObjectNode pet,int talent,String field) {
        JsonNode rule=option("talents",talent);
        int count=rule==null?0:rule.path("positionCount").asInt();
        if(pet.path(field).size()==count)return;
        List<Integer> slots=new ArrayList<>();
        if(rule!=null)for(JsonNode slot:rule.path("positions"))slots.add(slot.asInt());
        Collections.shuffle(slots,new Random((pet.path("uid").asText()+talent+field).hashCode()));
        ArrayNode result=pet.putArray(field);
        for(int i=0;i<count;i++)result.add(slots.get(i));
    }

    /**
     * 读取完整饲育存档。
     * @param roleId 角色编号
     * @return 饲育存档
     */
    private ObjectNode state(String roleId) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?",String.class,roleId);
        return rows.isEmpty()?json.createObjectNode():(ObjectNode)parse(rows.get(0));
    }

    /**
     * 获取存档中确实属于当前角色的宠物。
     * @param state 饲育存档
     * @param id 宠物实例编号
     * @return 宠物数据
     */
    private ObjectNode pet(ObjectNode state,String id) {
        JsonNode pet=id==null?null:state.path("pets").get(id);
        if(pet==null || !pet.isObject())throw new ApiException(HttpStatus.NOT_FOUND,"PET_NOT_FOUND","当前角色未找到该宠物");
        return (ObjectNode)pet;
    }

    /**
     * 校验原始值为整数。
     * @param value 原始字段
     * @return 整数值
     */
    private int integer(JsonNode value) {
        if(value==null || !value.isIntegralNumber() || !value.canConvertToInt())throw invalid("定制选项必须使用整数");
        return value.intValue();
    }

    /**
     * 解析已保存的对象。
     * @param value 存档内容
     * @return 配置对象
     */
    private JsonNode parse(String value) {
        try{return json.readTree(value);}catch(IOException error){throw new IllegalStateException("宠物定制存档损坏",error);}
    }

    /**
     * 创建定制参数错误。
     * @param message 中文说明
     * @return 业务异常
     */
    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PET_CUSTOMIZATION",message);
    }
}
