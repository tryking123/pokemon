package com.pokemon.offline.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.home.HomeRaceService;
import com.pokemon.offline.inventory.BagCapacityService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 盲盒订单、原权重开箱、精灵银行及实际资产结算。
 * @author tongruiyuan
 * @since 2026-11-20 13:14
 */
@Service
public class BlindBoxService {
    private static final int BANK_LIMIT = 5000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final ElfService elves;
    private final BagCapacityService capacity;
    private final InventoryService inventory;
    private final HomeRaceService dressBag;
    private final JsonNode pools;
    private final JsonNode crystals;

    /**
     * 初始化随包奖池和可在旧存档增建的账本。
     * @param jdbc 本地数据连接
     * @param json 资产快照编解码器
     * @param roles 角色锁
     * @param elves 真实精灵发放
     * @param capacity 精灵背包容量
     * @param inventory 普通道具和溢出邮件
     * @param dressBag 角色时装碎片账本
     * @throws IOException 奖池资源不可用
     */
    BlindBoxService(JdbcTemplate jdbc,ObjectMapper json,RoleService roles,ElfService elves,
                   BagCapacityService capacity,InventoryService inventory,HomeRaceService dressBag) throws IOException {
        this.jdbc=jdbc; this.json=json; this.roles=roles; this.elves=elves;
        this.capacity=capacity; this.inventory=inventory; this.dressBag=dressBag;
        try(InputStream boxes=new ClassPathResource("payment/blind-boxes.json").getInputStream();
            InputStream base=new ClassPathResource("elf/release-crystals.json").getInputStream()) {
            pools=json.readTree(boxes).required("pools");
            crystals=json.readTree(base);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_blind_box(role_id VARCHAR(36) PRIMARY KEY,state_json CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_blind_receipt(role_id VARCHAR(36) NOT NULL,receipt VARCHAR(100) NOT NULL,"
                +"request_json CLOB NOT NULL,reply_json CLOB NOT NULL,PRIMARY KEY(role_id,receipt))");
    }

    /**
     * 将原支付货号换算为盲盒商城展示的行号。
     * @param sku 实际支付货号
     * @return 商城索引，零表示其他商品
     */
    static int index(int sku) {
        switch(sku) {
            case 401:return 1;
            case 402:return 5;
            case 407:return 6;
            case 403:return 7;
            case 404:return 8;
            case 405:return 9;
            default:return 0;
        }
    }

    /**
     * 保存一笔已确认订单的整包库存，支付层在同一事务防重。
     * @param role 角色标识
     * @param sku 已确认档位
     */
    void deliver(String role,int sku) {
        int idx=index(sku);
        if(idx==0)return;
        ObjectNode data=load(role);
        add(data.with("bbox"),idx,1);
        save(role,data);
    }

    /**
     * 读取持有箱子和精灵银行的原 S464 快照。
     * @param role 角色标识
     * @return 按物种和货号统计的余额
     */
    public ObjectNode snapshot(String role) {
        ObjectNode state=load(role), result=json.createObjectNode();
        for(String key:new String[]{"bbox","bank"}) {
            ArrayNode list=result.putArray(key);
            state.path(key).fields().forEachRemaining(entry->list.addObject().put("sid",Integer.parseInt(entry.getKey())).put("num",entry.getValue().asInt()));
        }
        return result;
    }

    /**
     * 校验开箱或银行选择，在一次提交中记录实际到账与响应凭据。
     * @param role 认证角色
     * @param cmd 原请求编号
     * @param input 客户端选择
     * @param receipt 连接和原请求序号
     * @return 原响应和仅首次发送的资产变化
     */
    @Transactional
    public ObjectNode transact(String role,int cmd,ObjectNode input,String receipt) {
        roles.lockRole(role);
        ObjectNode request=json.createObjectNode().put("cmd",cmd);
        request.set("request",input);
        List<String> replies=jdbc.query("SELECT request_json,reply_json FROM local_blind_receipt WHERE role_id=? AND receipt=?",
                (row,i)->request.toString().equals(row.getString(1))?row.getString(2):"{\"errno\":1}",role,receipt);
        if(!replies.isEmpty())return read(replies.get(0));
        ObjectNode state=load(role), reply=json.createObjectNode().put("errno",1), adds=json.createObjectNode();
        Map<Integer,Long> stones=new LinkedHashMap<>();
        if(cmd==399)open(state,input,reply,adds,stones,role);
        else bank(role,cmd,state,input,reply,stones);
        if(reply.path("errno").asInt()!=0)return reply;
        if(!stones.isEmpty()) {
            Map<Integer,Long> direct=inventory.grantItemsOrMail(role,"blind:"+receipt,stones);
            direct.forEach((id,n)->adds.withArray("items").addObject().put("id",id).put("n",n));
        }
        save(role,state);
        jdbc.update("INSERT INTO local_blind_receipt(role_id,receipt,request_json,reply_json) VALUES(?,?,?,?)",
                role,receipt,request.toString(),reply.toString());
        reply.set("adds",adds);
        return reply;
    }

    /**
     * 按购买的包规格随机逐抽，颜色五只对抽中的红色精灵生效。
     * @param state 出入库前的角色账本
     * @param input 原开箱请求
     * @param reply 原回执
     * @param adds 奖励提示
     * @param stones 需要实际入包的晶石
     * @param role 角色标识
     */
    private void open(ObjectNode state,ObjectNode input,ObjectNode reply,ObjectNode adds,Map<Integer,Long> stones,String role) {
        if(!input.path("idx").canConvertToInt() || !input.path("num").canConvertToInt() || input.has("color") && !input.path("color").canConvertToInt())return;
        int idx=input.path("idx").asInt(), count=input.path("num").asInt(), color=input.path("color").asInt();
        int pack=idx==1||idx==8?1:idx==5||idx==9?10:idx==6?50:idx==7?100:0;
        ObjectNode boxes=state.with("bbox"), bank=state.with("bank");
        int owned=boxes.path(String.valueOf(idx)).asInt();
        if(pack==0 || count<1 || count>owned || count>BANK_LIMIT/pack || color!=0 && color!=5)return;
        int draws=count*pack;
        boolean elf=idx<8;
        int stored=0;
        for(JsonNode quantity:bank)stored+=quantity.asInt();
        if(elf && draws>BANK_LIMIT-stored)return;
        JsonNode pool=pools.required(elf?"elf":"dress");
        int total=0;
        for(JsonNode row:pool)total+=row.get(1).asInt();
        Map<Integer,Long> won=new LinkedHashMap<>();
        for(int draw=0;draw<draws;draw++) {
            int ticket=ThreadLocalRandom.current().nextInt(total);
            for(JsonNode row:pool) {
                ticket-=row.get(1).asInt();
                if(ticket>=0)continue;
                int sid=row.get(0).asInt(), n=row.get(2).asInt();
                if(elf && color==5 && row.get(3).asInt()==5)split(stones,sid,n);
                else won.merge(sid,(long)n,Math::addExact);
                break;
            }
        }
        if(!won.isEmpty()) {
            if(elf)won.forEach((sid,n)->add(bank,sid,n));
            else dressBag.addDressChips(role,won);
            ArrayNode changes=adds.putArray(elf?"simelf":"fasheschips");
            won.forEach((sid,n)->changes.addObject().put("id",sid).put("n",n));
        }
        add(boxes,idx,-count);
        reply.put("errno",0).put("idx",idx).put("lastnum",owned-count);
    }

    /**
     * 校验原交替排列的物种、数量后拆解或领取整只精灵。
     * @param role 当前角色
     * @param cmd 银行动作编号
     * @param state 精灵银行
     * @param input 选择的物种与数量
     * @param reply 原应答
     * @param stones 拆解晶石累计
     */
    private void bank(String role,int cmd,ObjectNode state,ObjectNode input,ObjectNode reply,Map<Integer,Long> stones) {
        JsonNode list=input.path("list");
        if(cmd!=400 && cmd!=401 || !list.isArray() || list.size()<2 || list.size()>BANK_LIMIT*2 || list.size()%2!=0)return;
        ObjectNode bank=state.with("bank");
        Map<Integer,Integer> selected=new LinkedHashMap<>();
        int total=0;
        for(int i=0;i<list.size();i+=2) {
            if(!list.get(i).canConvertToInt() || !list.get(i+1).canConvertToInt())return;
            int sid=list.get(i).asInt(), n=list.get(i+1).asInt();
            if(n<1 || n>BANK_LIMIT-total || n>bank.path(String.valueOf(sid)).asInt() || selected.put(sid,n)!=null)return;
            total+=n;
        }
        if(cmd==401 && total>capacity.elves(role)-elves.list(role).size()){reply.put("errno",1008);return;}
        selected.forEach((sid,n)->{
            if(cmd==400)split(stones,sid,n);
            else for(int i=0;i<n;i++)elves.grant(role,sid);
            add(bank,sid,-n);
        });
        reply.put("errno",0).set("list",list);
    }

    /**
     * 根据原精灵模板计算红蓝晶石。
     * @param results 待发道具
     * @param sid 精灵配置号
     * @param n 实际拆解数量
     */
    private void split(Map<Integer,Long> results,int sid,int n) {
        JsonNode row=crystals.required(String.valueOf(sid));
        if(row.get(0).asLong()>0)results.merge(21024,row.get(0).asLong()*n,Math::addExact);
        if(row.get(1).asLong()>0)results.merge(21026,row.get(1).asLong()*n,Math::addExact);
    }

    /**
     * 变更余额并清除已耗尽的种类。
     * @param state 目标库存
     * @param id 模板编号
     * @param amount 变更数
     */
    private void add(ObjectNode state,int id,long amount) {
        String key=String.valueOf(id);
        long balance=Math.addExact(state.path(key).asLong(),amount);
        if(balance==0)state.remove(key);else state.put(key,balance);
    }

    /** 读取角色已有库存。
     * @param role 当前角色
     * @return 角色状态
     */
    private ObjectNode load(String role) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_blind_box WHERE role_id=?",String.class,role);
        return rows.isEmpty()?json.createObjectNode():read(rows.get(0));
    }

    /** 解析库存或已记账的原回执。
     * @param text 持久化数据
     * @return JSON 对象
     */
    private ObjectNode read(String text) {
        try{return (ObjectNode)json.readTree(text);}catch(IOException error){throw new IllegalStateException("盲盒存档格式错误",error);}
    }

    /** 提交本次库存变化。
     * @param role 当前角色
     * @param state 新余额
     */
    private void save(String role,ObjectNode state) {
        jdbc.update("MERGE INTO local_blind_box(role_id,state_json) KEY(role_id) VALUES(?,?)",role,state.toString());
    }
}
