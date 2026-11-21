package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import java.io.IOException;
import java.util.List;

/**
 * 普通队伍宠物战斗 Service，从角色已提交的队伍映射附入只读战斗快照。
 * @author tongruiyuan
 * @since 2026-11-22 04:26
 */
@Service
public final class PetCombatService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 绑定应用私有存档与解析器。
     * @param jdbc 私有数据库
     * @param json 宠物存档解析器
     */
    public PetCombatService(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    /**
     * 给同角色的各只精灵附入自身普通队伍的宠物，不写入精灵养成存档。
     * @param roleId 当前角色
     * @param elves 该角色查询出的精灵
     */
    public void attach(String roleId,List<LocalElf> elves) {
        if(elves.isEmpty())return;
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?",String.class,roleId);
        if(rows.isEmpty())return;
        JsonNode state;
        try{state=json.readTree(rows.get(0));}catch(IOException error){throw new IllegalStateException("宠物战斗存档损坏",error);}
        for(LocalElf elf:elves) {
            String uid=state.path("petTeams").path("10").path(Integer.toString(elf.team)).asText();
            JsonNode pet=state.path("pets").path(uid);
            boolean deployed=false;
            for(JsonNode status:pet.path("status"))if(status.asInt()==10)deployed=true;
            if(!uid.isEmpty() && uid.equals(pet.path("uid").asText()) && deployed && PetCombatRules.valid(pet))
                elf.battlePet.setAll((ObjectNode)pet.deepCopy());
        }
    }

    /**
     * 根据实际参战的本人精灵查询普通队伍宠物，避免误用默认队伍。
     * @param roleId 当前角色
     * @param elfId 实际参战精灵
     * @return 原petbtl对象，无本人精灵或有效出战宠物时为空
     */
    public java.util.Map<String,Object> battlePetForMember(String roleId,String elfId) {
        List<Integer> teams=jdbc.queryForList("SELECT team FROM local_elf WHERE role_id=? AND id=?",Integer.class,roleId,elfId);
        if(teams.isEmpty())return java.util.Collections.emptyMap();
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?",String.class,roleId);
        if(rows.isEmpty())return java.util.Collections.emptyMap();
        try {
            JsonNode state=json.readTree(rows.get(0));
            String uid=state.path("petTeams").path("10").path(Integer.toString(teams.get(0))).asText();
            JsonNode pet=state.path("pets").path(uid);
            for(JsonNode status:pet.path("status"))
                if(status.asInt()==10 && uid.equals(pet.path("uid").asText()))return PetCombatRules.battlePet(pet);
            return java.util.Collections.emptyMap();
        }catch(IOException error){throw new IllegalStateException("宠物战斗存档损坏",error);}
    }
}
