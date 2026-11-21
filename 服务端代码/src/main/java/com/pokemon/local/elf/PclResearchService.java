package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 精灵 PCL 研究方向、注入、升级和重置 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:36
 */
@Service
public class PclResearchService {
    static final JsonNode RULES = ElfTrainingCatalog.load("elf/pcl.json");
    private final ElfMapper elves;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectMapper json;

    /** 注入精灵、角色及共用背包。
     * @param elves 精灵培养存档
     * @param roles 角色事务锁
     * @param inventory 统一道具与货币库存
     * @param json 请求回执编码
     */
    PclResearchService(ElfMapper elves, RoleService roles, InventoryService inventory, ObjectMapper json) {
        this.elves = elves;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
    }

    /** 按原协议结算一次研究操作并持久化完整回执。
     * @param roleId 当前角色
     * @param command 原请求编号
     * @param body 原请求体
     * @param receipt 连接内回执
     * @return 原客户端响应字段
     */
    @Transactional
    public ObjectNode perform(String roleId, int command, Map<String,Object> body, String receipt) {
        int trainerLevel = roles.lockRole(roleId).level;
        ObjectNode input = json.valueToTree(body);
        ObjectNode saved = elves.pclReceipt(roleId,receipt).orElse(null);
        ObjectNode invalid = json.createObjectNode().put("errno",100021);
        if (saved != null) {
            return saved.path("input").toString().equals(input.toString()) ? (ObjectNode)saved.get("reply") : invalid;
        }
        String id = input.path("target").asText("");
        LocalElf elf = elves.findOwned(roleId,id).orElse(null);
        FirstBattleData.Starter base = elf == null ? null : FirstBattleData.starter(elf.configId);
        if (trainerLevel < 80 || base == null || base.quality < 5) {
            return invalid;
        }
        ObjectNode state = elf.training;
        ObjectNode reply = json.createObjectNode().put("errno",0).put("target",id);
        if (command == 417) {
            if (!activate(state,input.path("path"),reply)) {
                return invalid;
            }
        } else {
            JsonNode pits = state.path("pcl");
            if (!pits.isArray() || pits.size()!=2) {
                return invalid;
            }
            if (command == 420) {
                if (!reset(roleId,state,input,reply,receipt)) {
                    return invalid;
                }
            } else {
                long index = input.path("index").asLong();
                if (index < 1 || index > 2) {
                    return invalid;
                }
                ObjectNode pit = (ObjectNode)pits.get((int)index-1);
                if (!research(roleId,command,input,state,pit,reply)) {
                    return invalid;
                }
            }
        }
        if (elves.saveTraining(roleId,id,elf.configId,state)!=1) {
            throw new IllegalStateException("精灵研究存档写入失败");
        }
        ObjectNode record = json.createObjectNode();
        record.set("input",input);
        record.set("reply",reply);
        elves.recordPcl(roleId,receipt,record);
        return reply;
    }

    /** 按原顺序创建两个不同研究方向。
     * @param state 目标精灵存档
     * @param path 原方向数组
     * @param reply 应答
     * @return 目标此前未激活且方向有效
     */
    private boolean activate(ObjectNode state, JsonNode path, ObjectNode reply) {
        if (state.has("pcl") || !path.isArray() || path.size()!=2) {
            return false;
        }
        long a = path.get(0).asLong();
        long b = path.get(1).asLong();
        if (a<1 || a>5 || b<1 || b>5 || a==b) {
            return false;
        }
        ArrayNode pits = state.putArray("pcl");
        for (int i=0; i<2; i++) {
            pits.addObject().put("index",i+1).put("path",path.get(i).asInt()).put("level",0).put("num",0);
        }
        reply.set("path",path.deepCopy());
        return true;
    }

    /** 注入当前研究晶体或用原辅助材料完成进阶。
     * @param roleId 当前角色
     * @param command 注入或升级命令
     * @param input 请求
     * @param state 精灵存档
     * @param pit 所选方向
     * @param reply 原应答
     * @return 是否已完成
     */
    private boolean research(String roleId,int command,ObjectNode input,ObjectNode state,ObjectNode pit,ObjectNode reply) {
        int next = pit.path("level").asInt()+1;
        JsonNode rule = RULES.path("levels").path(String.valueOf(pit.path("path").asInt()*100+next));
        int required = rule.path("experience").asInt();
        int current = pit.path("num").asInt();
        if (required<=0) {
            return false;
        }
        Map<Integer,Long> spend = new LinkedHashMap<>();
        long quantity = input.path("num").asLong();
        if (command == 418) {
            if (quantity <= 0 || quantity > required-current) {
                return false;
            }
            spend.put(rule.path("item").asInt(),quantity);
        } else if (command == 419 && current == required) {
            JsonNode cost = rule.get("cost");
            for (int i=0; i<cost.size(); i+=3) {
                spend.merge(cost.get(i+1).asInt(),cost.get(i+2).asLong(),Long::sum);
            }
        } else {
            return false;
        }
        if (!inventory.spendMaterials(roleId,spend,0,0)) {
            return false;
        }
        ObjectNode ledger = state.with("pclSpent");
        spend.forEach((item,n)-> {
            String key = item.toString();
            ledger.put(key,Math.addExact(ledger.path(key).asLong(),n));
        });
        reply.put("index",pit.path("index").asInt());
        if (command == 418) {
            pit.put("num",current+quantity);
            reply.put("num",quantity).put("final",current+quantity);
        } else {
            pit.put("level",next).put("num",0);
            reply.put("final",next);
            refreshResonance(state,reply);
        }
        return true;
    }

    /** 由两方向总等级匹配原共鸣门槛。
     * @param state 当前研究存档
     * @param reply 本次升级应答
     */
    private void refreshResonance(ObjectNode state,ObjectNode reply) {
        JsonNode first = state.path("pcl").get(0);
        JsonNode second = state.path("pcl").get(1);
        int total = first.path("level").asInt()+second.path("level").asInt();
        int id = RULES.path("groups").path(Math.min(first.path("path").asInt(),second.path("path").asInt())+"-"+Math.max(first.path("path").asInt(),second.path("path").asInt())).asInt();
        int best = 0;
        while (id>0) {
            JsonNode rule = RULES.path("resonance").path(String.valueOf(id));
            if (rule.isMissingNode() || total<rule.path("need").asInt()) {
                break;
            }
            best = id;
            id = rule.path("nextid").asInt();
        }
        if (best>0) {
            state.put("pcltype",best);
            reply.put("pcltype",best);
        }
    }

    /** 逐种材料按已付总量返还；重置介质不计入投入。
     * @param roleId 当前角色
     * @param state 精灵研究存档
     * @param input 原请求
     * @param reply 原响应
     * @param receipt 退款来源标识
     * @return 是否支付成功
     */
    private boolean reset(String roleId,ObjectNode state,ObjectNode input,ObjectNode reply,String receipt) {
        long mode = input.path("itype").asLong();
        if (mode<1 || mode>3) {
            return false;
        }
        if (mode==2 && !inventory.spendMaterials(roleId,Collections.emptyMap(),0,2000)
                || mode==3 && !inventory.spendMaterials(roleId,20027,1,0,0)) {
            return false;
        }
        int rate = mode==1 ? 50 : mode==2 ? 80 : 100;
        Map<Integer,Long> refund = new LinkedHashMap<>();
        state.path("pclSpent").fields().forEachRemaining(entry-> {
            long count=entry.getValue().asLong()*rate/100;
            if (count>0) {
                refund.put(Integer.valueOf(entry.getKey()),count);
            }
        });
        if (!refund.isEmpty()) {
            inventory.grantItemsOrMail(roleId,"pcl-reset:"+receipt,refund);
        }
        state.remove(java.util.Arrays.asList("pcl","pcltype","pclSpent"));
        reply.put("itype",mode);
        return true;
    }
}
