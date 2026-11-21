package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 极巨化路线升级、技能书镶嵌、取下、整书升级及遗忘。
 * @author tongruiyuan
 * @since 2026-11-20 11:03
 */
@Service
public class DynamaxService {
    private final ElfMapper elves;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    /** 绑定持有精灵、角色锁、资产及回执存档。
     * @param elves 精灵存档
     * @param roles 角色服务
     * @param inventory 道具与钱包
     * @param json 回包读取器
     * @param jdbc 操作流水
     */
    public DynamaxService(ElfMapper elves, RoleService roles, InventoryService inventory, ObjectMapper json, JdbcTemplate jdbc) {
        this.elves=elves; this.roles=roles; this.inventory=inventory; this.json=json; this.jdbc=jdbc;
    }

    /** 用同一事务更新当前精灵、背包和请求回执。
     * @param role 角色编号
     * @param cmd 原协议
     * @param args 原客户端参数
     * @param key 连接请求标识
     * @return 原协议字段
     */
    @Transactional
    public ObjectNode perform(String role,int cmd,Map<String,Object> args,String key) {
        roles.lockRole(role);
        JsonNode input=json.valueToTree(args);
        String uid=input.path("uid").asText("");
        long rawBook=input.path("book").asLong(), rawPath=input.path("path").asLong();
        ObjectNode bad=json.createObjectNode().put("errno",100021);
        if (rawBook<0 || rawBook>Integer.MAX_VALUE || rawPath<0 || rawPath>3) { return bad; }
        int book=(int)rawBook;
        LocalElf elf=uid.isEmpty()?null:elves.findOwned(role,uid).orElse(null);
        if (uid.isEmpty() ? cmd!=315 || book==0 : elf==null) { return bad; }
        if (!jdbc.query("SELECT request_key FROM local_dynamax_receipt WHERE role_id=? AND request_key=?",
                (r,n)->r.getString(1),role,key).isEmpty()) { return json.createObjectNode().put("errno",100011); }
        ObjectNode state=elf==null?json.createObjectNode():elf.training;
        int level=state.path("dynalevel").asInt(), path=state.path("dynapath").asInt(), worn=state.path("dynabook").asInt();
        int outputBook=0, price=0;
        Map<Integer,Long> costs=new LinkedHashMap<>();
        ObjectNode out=json.createObjectNode().put("errno",0);
        switch(cmd) {
            case 312:
                if (path==0) { path=(int)rawPath; }
                else if (input.has("path") && rawPath!=path) { return bad; }
                if (elf==null || path==0 || level>=DynamaxRules.maxLevel(elf,path)) { return bad; }
                price=recipe(DynamaxRules.route(level,path,"_cost"),costs);
                state.put("dynapath",path).put("dynalevel",level+1);
                out.put("dynalevel",level+1);
                break;
            case 313:
                if (path==0 || !inventory.isDynamaxBook(book) ||
                        level<DynamaxRules.bookLevel(book).path("level").asInt(Integer.MAX_VALUE)) { return bad; }
                costs.put(book,1L);
                int type=ThreadLocalRandom.current().nextInt(1,3);
                state.put("dynabook",book).put("dynatype",type);
                out.put("dynatype",type);
                break;
            case 314:
                if (worn==0) { return bad; }
                outputBook=worn; price=100;
                state.remove("dynabook"); state.remove("dynatype");
                break;
            case 315:
                if (elf!=null && input.has("book")) { return bad; }
                int current=elf==null?book:worn;
                int next=current+100;
                if (!inventory.isDynamaxBook(current) || !inventory.isDynamaxBook(next)) { return bad; }
                if (elf!=null && level<DynamaxRules.bookLevel(next).path("level").asInt(Integer.MAX_VALUE)) { return bad; }
                JsonNode rule=DynamaxRules.bookLevel(current);
                price=recipe(rule.path("cost"),costs);
                int element=current%100;
                costs.put(8000+current/10000*100+element,rule.path("broken").asLong());
                costs.put(20003+element,rule.path("stone").asLong());
                if (elf==null) { costs.put(current,1L); outputBook=next; }
                else { state.put("dynabook",next); }
                out.put("dynabook",next);
                break;
            case 316:
                if (path==0 || level<1) { return bad; }
                state.remove("dynapath"); state.remove("dynalevel"); state.remove("dynabook"); state.remove("dynatype");
                price=500;
                break;
            default: return bad;
        }
        int error=inventory.exchangeDynamaxBook(role,costs,price,outputBook);
        if (error!=0) { return json.createObjectNode().put("errno",error); }
        if (elf!=null && elves.saveTraining(role,uid,elf.configId,state)!=1) { throw new IllegalStateException("极巨化精灵存档更新失败"); }
        jdbc.update("INSERT INTO local_dynamax_receipt(role_id,request_key) VALUES(?,?)",role,key);
        return out;
    }

    /** 解析原三元组配方。
     * @param list 消耗列表
     * @param costs 本次所需道具
     * @return 钻石总额
     */
    private int recipe(JsonNode list, Map<Integer,Long> costs) {
        int diamonds=0;
        if (!list.isArray() || list.size()==0 || list.size()%3!=0) { throw new IllegalStateException("极巨化消耗未配置"); }
        for(int i=0; i<list.size(); i+=3) {
            if(list.get(i).asInt()==104) { diamonds=Math.addExact(diamonds,list.get(i+2).asInt()); }
            else if(list.get(i).asInt()==1) { costs.merge(list.get(i+1).asInt(),list.get(i+2).asLong(),Long::sum); }
            else { throw new IllegalStateException("极巨化消耗类型有误"); }
        }
        return diamonds;
    }
}
