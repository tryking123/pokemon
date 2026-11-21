package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.FixedStageCombatService;
import com.pokemon.local.dojo.DojoService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 精灵旅行团生成、派遣、便当、行程及事件结算 Service
 * @author tongruiyuan
 * @since 2026-11-20 09:27
 */
@Service
public class ElfTravelService {
    private static final List<String> ATTRIBUTES = Arrays.asList("special_attack","physical_attack","special_defense","physical_defense","speed","hp_max");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final InventoryService inventory;
    private final GamePaymentService payments;
    private final ElfMapper elves;
    private final DojoService dojo;
    private final FixedStageCombatService combat;
    private final JsonNode groups;
    private final JsonNode events;
    private final JsonNode enemies;

    /**
     * 加载原始团次、事件和敌方配置并创建旅行存档。
     * @param jdbc 本地数据库
     * @param json 配置解析器
     * @param roles 角色及资产锁
     * @param inventory 背包和钱包
     * @param payments VIP 等级
     * @param elves 精灵存档
     * @param dojo 道馆占用
     * @param combat 本地战斗引擎
     * @throws IOException 配置读取失败
     */
    public ElfTravelService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, InventoryService inventory,
            GamePaymentService payments, ElfMapper elves, DojoService dojo, FixedStageCombatService combat) throws IOException {
        this.jdbc=jdbc; this.json=json; this.roles=roles; this.inventory=inventory; this.payments=payments;
        this.elves=elves; this.dojo=dojo; this.combat=combat;
        try (InputStream in = new ClassPathResource("elf/travel.json").getInputStream()) {
            JsonNode data=json.readTree(in); groups=data.get("groups"); events=data.get("events");
        }
        try (InputStream in = new ClassPathResource("battle/travel-rosters.json").getInputStream()) {
            enemies=json.readTree(in).path("groups");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_travel_state(role_id VARCHAR(64) PRIMARY KEY,payload TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_travel_dispatch(elf_id VARCHAR(64) PRIMARY KEY,role_id VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_travel_receipt(role_id VARCHAR(64) NOT NULL,receipt VARCHAR(96) NOT NULL,reply TEXT NOT NULL,PRIMARY KEY(role_id,receipt))");
    }

    /** 从原加权序列抽取编号。 */
    private int weighted(JsonNode values) {
        int sum=0;
        for (int i=1;i<values.size();i+=2) sum+=values.get(i).asInt();
        int pick=ThreadLocalRandom.current().nextInt(sum);
        for (int i=0;i<values.size();i+=2) {
            pick-=values.get(i+1).asInt();
            if(pick<0) return values.get(i).asInt();
        }
        throw new IllegalStateException("旅行奖池未命中");
    }

    /** 生成一个旅行团，预先冻结团星级、行程、努力值、奖励和便当。 */
    private ObjectNode offer(int vip) {
        ArrayNode weights=json.createArrayNode();
        int bracket=vip>=6?2:vip>=3?1:0;
        for(int i=1;i<=5;i++) {
            JsonNode time=groups.path(String.valueOf(i)).path("time");
            weights.add(i).add(time.get(bracket*4+2).asInt());
        }
        int stars=weighted(weights);
        JsonNode g=groups.path(String.valueOf(stars));
        ObjectNode row=json.createObjectNode().put("index",stars).put("itype",1)
                .put("time",g.path("time").get(bracket*4+3).asInt()).put("speed_time",0)
                .put("itemid",weighted(g.path("item"))).put("region",1);
        List<String> fields=new ArrayList<>(ATTRIBUTES);
        Collections.shuffle(fields);
        ObjectNode efforts=row.putObject("efforts");
        int count=weighted(g.path("attributenum"));
        for(int i=0;i<count;i++) efforts.put(fields.get(i),g.path("attribute").asInt());
        int category=bracket+1;
        row.putArray("debris").addObject().put("id",weighted(g.path("rew"+category)))
                .put("n",weighted(g.path("rewnum"+category))).put("type",3);
        row.putArray("fruit");
        return row;
    }

    /** 读取角色行锁内的旅行存档并处理北京时间日界线及四小时自然恢复。 */
    private ObjectNode load(String roleId,long now) {
        List<String> rows=jdbc.queryForList("SELECT payload FROM local_travel_state WHERE role_id=?",String.class,roleId);
        ObjectNode state=rows.isEmpty()?json.createObjectNode():parse(rows.get(0));
        long day=Math.floorDiv(now+28800,86400);
        if(state.path("day").asLong(-1)!=day){
            state.put("day",day).put("buy_num",0).put("free_refresh_num",0).put("refresh_bento_num",0);
            ArrayNode oldCounts=state.withArray("num_tab");
            ArrayNode newCounts=state.putArray("num_tab");
            for(JsonNode entry:oldCounts){
                long until=entry.path("limit_times").asLong();
                if(until>now)newCounts.addObject().put("uid",entry.path("uid").asText()).put("num",0).put("limit_times",until);
            }
        }
        long period=Math.floorDiv(now+28800,14400);
        int spent=state.path("travel_times").asInt();
        long restored=Math.max(0,period-state.path("period").asLong(period));
        state.put("period",period).put("travel_times",spent<0?spent:(int)Math.max(0,spent-restored));
        if(!state.has("travel")){
            ArrayNode offers=state.putArray("travel");
            int vip=payments.vip(roleId).level;
            for(int i=0;i<3;i++) offers.add(offer(vip));
            state.putArray("events");state.putArray("pice");state.put("star",0);
        }
        if(state.has("fight") && state.path("fight").path("expiry").asLong()<=now)state.remove("fight");
        return state;
    }

    /** 解析已写入的对象快照。 */
    private ObjectNode parse(String value) {
        try{return (ObjectNode)json.readTree(value);}catch(IOException e){throw new IllegalStateException(e);}
    }

    /** 与本次扣款、派遣占用和奖励一并保存。 */
    private void save(String role,ObjectNode state){
        jdbc.update("MERGE INTO local_travel_state(role_id,payload) KEY(role_id) VALUES(?,?)",role,state.toString());
    }

    /** 查询 S2019 的原页面状态。
     * @param role 角色标识
     * @return 可发送给原界面的字段
     */
    @Transactional
    public ObjectNode snapshot(String role){
        roles.lockRole(role);
        ObjectNode s=load(role,Instant.now().getEpochSecond());save(role,s);
        return s.retain("travel","num_tab","free_refresh_num","refresh_bento_num","travel_times","events","pice","buy_num","star");
    }

    /** 处理有会话号的旅行请求并记录已成交动作。
     * @param role 角色标识
     * @param cmd 原协议编号
     * @param req 原客户端动作
     * @param key 网关连接和请求号
     * @return 应答和关联推送
     */
    @Transactional
    public ObjectNode act(String role,int cmd,JsonNode req,String key) {
        LocalRole actor=roles.lockRole(role);
        ObjectNode out=json.createObjectNode().put("errno",1);
        if(actor.level<36)return out;
        List<String> old=jdbc.queryForList("SELECT reply FROM local_travel_receipt WHERE role_id=? AND receipt=?",String.class,role,key);
        if(!old.isEmpty()){
            // 原领取回调会在客户端累加努力值，重复应答只提示已经领取。
            if(cmd==5066)return out.put("errno",100011);
            return parse(old.get(0)).put("replayed",true);
        }
        long now=Instant.now().getEpochSecond();
        ObjectNode s=load(role,now);
        ArrayNode board=(ArrayNode)s.path("travel");
        long index=req.path("index").asLong(0);
        ObjectNode row=index>0&&index<=board.size()?(ObjectNode)board.get((int)index-1):null;
        long mode=req.path("itype").asLong(0);
        boolean success=false;
        switch(cmd){
        case 5095:
            if(s.path("buy_num").asInt()<5 && inventory.spendMaterials(role,0,0,0,500)){
                s.put("buy_num",s.path("buy_num").asInt()+1).put("travel_times",s.path("travel_times").asInt()-1);
                success=true;
            }
            break;
        case 5064:
            success=refresh(role,s,board,mode);break;
        case 5078:
            if(row!=null&&!row.has("uid")){
                int count=s.path("refresh_bento_num").asInt();
                if(count<3 || inventory.spendMaterials(role,0,0,0,1000)){
                    row.put("itemid",weighted(groups.path(row.path("index").asText()).path("item")));
                    s.put("refresh_bento_num",count+1);
                    success=true;
                }
            }
            break;
        case 5063:
            success=row!=null&&depart(role,s,row,req,now);break;
        case 5065:
            if(row!=null&&row.has("uid")&&(mode==1||mode==2)){
                long left=endTime(row)-now;
                if(left>0){
                    int minutes=mode==1?10:row.path("time").asInt();
                    if(inventory.spendMaterials(role,0,0,0,400L*minutes)){
                        row.put("speed_time",row.path("speed_time").asInt()+minutes);
                        success=true;
                    }
                }
            }
            break;
        case 5066:
            success=row!=null && row.has("uid") && now>=endTime(row) && !s.has("eventBattle")
                    && !s.has("fight") && finish(role,s,row,out);
            if(success)board.set((int)index-1,offer(payments.vip(role).level));
            break;
        case 5069:
            success=chooseBattle(actor,s,req.path("choose").asLong(-1),out);
            break;
        default:
            return out;
        }
        if(success){
            out.put("errno",0);
            save(role,s);
            jdbc.update("INSERT INTO local_travel_receipt(role_id,receipt,reply) VALUES(?,?,?)",role,key,out.toString());
        }
        return out;
    }

    /** 刷新未出发的团次。免费、 refresh 券、钻石三类各走原请求模式。 */
    private boolean refresh(String role,ObjectNode state,ArrayNode board,long mode){
        boolean available=false;
        for(JsonNode row:board) if(!row.has("uid"))available=true;
        if(!available)return false;
        boolean free=state.path("free_refresh_num").asInt()>0;
        if(free && mode==1)state.put("free_refresh_num",state.path("free_refresh_num").asInt()-1);
        else if(!free && mode==2 && inventory.count(role,21060)>0){
            if(!inventory.spendMaterials(role,21060,1,0,0))return false;
        }else if(!free && mode==3 && inventory.count(role,21060)==0){
            if(!inventory.spendMaterials(role,0,0,0,1000))return false;
        }else return false;
        int vip=payments.vip(role).level;
        for(int i=0;i<board.size();i++)if(!board.get(i).has("uid"))board.set(i,offer(vip));
        return true;
    }

    /** 从界面指定槽位派遣本角色实例并冻结真实到期时间。 */
    private boolean depart(String role,ObjectNode state,ObjectNode row,JsonNode req,long now){
        String uid=req.path("uid").asText();
        LocalElf elf=elves.findOwned(role,uid).orElse(null);
        if(elf==null || req.path("amulet").asLong()!=0 || req.path("itemid").asLong()!=0 || req.path("itype").asLong()!=0
                || jdbc.queryForObject("SELECT COUNT(*) FROM local_rocket_slot WHERE role_id=? AND elf_id=?",Integer.class,role,uid)>0
                || row.has("uid") || state.path("travel_times").asInt()>=3 || dojo.isAiding(role,uid) || dojo.isFighting(role,uid)
                || jdbc.queryForObject("SELECT COUNT(*) FROM local_dojo_team WHERE role_id=? AND elf_id=?",Integer.class,role,uid)>0)return false;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM local_travel_dispatch WHERE elf_id=?",Integer.class,uid)>0)return false;
        ArrayNode counts=state.withArray("num_tab");ObjectNode entry=null;
        for(JsonNode n:counts)if(uid.equals(n.path("uid").asText()))entry=(ObjectNode)n;
        if(elf.training.path("travelUnavailableUntil").asLong()>now || (entry!=null && entry.path("num").asInt()>=3))return false;
        JsonNode price=groups.path(row.path("index").asText()).path("price");
        int cost=price.get(2).asInt();
        if(!inventory.spendMaterials(role,0,0,price.get(0).asInt()==103?cost:0,price.get(0).asInt()==104?cost:0))return false;
        if(entry==null)entry=counts.addObject().put("uid",uid);
        int used=entry.path("num").asInt()+1;
        entry.put("num",used).put("limit_times",used==3?(state.path("day").asLong()+1)*86400-28800:0);
        row.put("uid",uid).put("opemtime",now);
        jdbc.update("INSERT INTO local_travel_dispatch(elf_id,role_id) VALUES(?,?)",uid,role);
        state.put("travel_times",state.path("travel_times").asInt()+1);
        return true;
    }

    /** 旅行的实际结束秒数。 */
    private long endTime(JsonNode row){
        return row.path("opemtime").asLong()+60L*(row.path("time").asInt()-row.path("speed_time").asInt());
    }

    /** 发放团次奖励、更新精灵努力值和事件图册，并解除派遣占用。 */
    private boolean finish(String role,ObjectNode state,ObjectNode row,ObjectNode out){
        String uid=row.path("uid").asText();
        LocalElf elf=elves.findOwned(role,uid).orElse(null);
        if(elf==null)return false;
        JsonNode chip=row.path("debris").get(0);
        if(!inventory.canGrantChip(role,chip.path("id").asInt(),chip.path("n").asInt()))return false;
        inventory.grantChip(role,chip.path("id").asInt(),chip.path("n").asInt());
        ObjectNode actual=out.putObject("efforts");
        ObjectNode current=elf.training.with("effort");
        for(String field:ATTRIBUTES){
            int gain=row.path("efforts").path(field).asInt();
            if(gain==0)continue;
            int cap=ElfTrainingCatalog.row("variable",1019+elf.training.path("star").asInt(1)).path("data2").asInt()
                    +ElfTrainingCatalog.row("break",elf.training.path("veinlevel").asInt()).path("strive").asInt()
                    +elf.training.path("effort_monopoly_max").path(field).asInt();
            int delta=Math.max(0,Math.min(gain,cap-current.path(field).asInt()));
            actual.put(field,delta);current.put(field,current.path(field).asInt()+delta);
        }

        jdbc.update("DELETE FROM local_travel_dispatch WHERE elf_id=? AND role_id=?",uid,role);
        out.put("uid",uid).put("region",row.path("region").asInt()).put("itype",1);
        out.set("fruit",row.path("fruit"));out.set("debris",row.path("debris"));
        int bento=row.path("itemid").asInt();
        ArrayNode pool=json.createArrayNode();
        events.fields().forEachRemaining(e->{
            for(JsonNode food:e.getValue().path("item")) if(food.asInt()==bento)
                pool.add(Integer.parseInt(e.getKey())).add(e.getValue().path("probability").asInt());
        });
        int event=weighted(pool);
        JsonNode detail=events.path(String.valueOf(event));
        if(detail.path("battle").asInt()!=1){
            applyEvent(role,elf,detail,out);
            if(detail.path("debuff3").isArray()){
                ArrayNode counts=state.withArray("num_tab");
                ObjectNode entry=null;
                for(JsonNode n:counts)if(uid.equals(n.path("uid").asText()))entry=(ObjectNode)n;
                if(entry==null)entry=counts.addObject().put("uid",uid).put("num",0);
                entry.put("limit_times",Math.max(entry.path("limit_times").asLong(),elf.training.path("travelUnavailableUntil").asLong()));
            }
        }
        ObjectNode record=null;
        for(JsonNode n:state.withArray("events"))if(n.path("id").asInt()==event)record=(ObjectNode)n;
        if(record==null)record=state.withArray("events").addObject().put("id",event);
        record.put("num",record.path("num").asInt()+1);
        ObjectNode trigger=out.putObject("event").put("id",event);
        JsonNode first=detail.path("pic1"),second=detail.path("pic2");
        ObjectNode picture=trigger.putObject("pice");
        picture.put("pice1",first.get(ThreadLocalRandom.current().nextInt(first.size())).asInt())
                .put("pice2",second.get(ThreadLocalRandom.current().nextInt(second.size())).asInt());
        boolean known=false;
        for(JsonNode n:state.withArray("pice"))if(picture.equals(n))known=true;
        if(!known)state.withArray("pice").add(picture.deepCopy());
        if(detail.path("battle").asInt()==1){
            if(elves.saveTraining(role,uid,elf.configId,elf.training)!=1)throw new IllegalStateException("旅行精灵丢失");
            state.put("eventBattle",event);
            state.set("eventPicture",picture.deepCopy());
            out.put("prompt",true);
        }
        state.put("star",state.path("star").asInt()+row.path("index").asInt());
        return true;
    }

    /** 按事件中记录的收益、损失和持续时间结算。 */
    private void applyEvent(String role,LocalElf elf,JsonNode event,ObjectNode out){
        LocalRole wallet=roles.findById(role).orElseThrow(IllegalStateException::new);
        JsonNode loss=event.path("debuff1");
        if(loss.isArray()){
            int currency=loss.get(0).asInt();
            long cost=loss.get(2).asLong();
            if(!inventory.spendMaterials(role,0,0,currency==103?Math.min(wallet.coins,cost):0,
                    currency==104?Math.min(wallet.diamonds,cost):0))throw new IllegalStateException("旅行事件扣款失败");
        }
        JsonNode reward=event.path("buff1");
        if(reward.isArray()){
            int type=reward.get(0).asInt(), id=reward.get(1).asInt(), amount=reward.get(2).asInt();
            ObjectNode adds=out.putObject("adds");
            if(type==1){
                if(!inventory.grantItemsOrMail(role,"travel:"+java.util.UUID.randomUUID(),Collections.singletonMap(id,(long)amount)).isEmpty())
                    adds.putArray("items").addObject().put("id",id).put("n",amount);
            }else if(type==3){
                inventory.grantChip(role,id,amount);
                adds.putArray("elschips").addObject().put("id",id).put("n",amount).put("type",3);
            }else if(type==103 || type==104){
                roles.addCurrency(role,type==103?amount:0,type==104?amount:0,0);
                adds.put(type==103?"coins":"diamonds",amount);
            }else if(type==6){
                // 本底包默认已拥有全部时装，只同步实际所有权。
                adds.putArray("fashes").add(id);
            }
        }
        if(elf==null)return;
        long now=Instant.now().getEpochSecond();
        int buff=event.path("buff2").asInt(event.path("debuff2").asInt());
        if(buff>=1009 && buff<=1020){
            String[] names={"physical_attack","physical_defense","special_attack","special_defense","hp_max","speed"};
            elf.training.with("travelEffects").putObject(names[(buff-1009)/2])
                    .put("percent",buff%2==1?10:-10).put("until",now+3600);
        }
        if(event.path("debuff3").isArray())elf.training.put("travelUnavailableUntil",now+86400);
        if(elves.saveTraining(role,elf.id,elf.configId,elf.training)!=1)throw new IllegalStateException("旅行状态未保存");
    }

    /** 根据本地待处理事件接受或跳过战斗。 */
    private boolean chooseBattle(LocalRole role,ObjectNode state,long choose,ObjectNode out){
        int event=state.path("eventBattle").asInt();
        if(event==0 || (choose!=0 && choose!=1))return false;
        if(choose==1){
            JsonNode cfg=enemies.path(events.path(String.valueOf(event)).path("monster").asText());
            ObjectNode fight=combat.start(role,event,cfg);
            if(fight.path("errno").asInt()!=0)return false;
            state.set("fight",fight);
            out.set("start",fight.deepCopy());
        }
        state.remove("eventBattle");
        state.remove("eventPicture");
        return true;
    }

    /**
     * 还原待处理的事件提示和中断的战斗。
     * @param role 角色标识
     * @return 事件或战斗回环数据
     */
    @Transactional
    public ObjectNode resume(String role){
        roles.lockRole(role);
        ObjectNode s=load(role,Instant.now().getEpochSecond());
        ObjectNode out=json.createObjectNode();
        if(s.has("eventBattle")){
            ObjectNode event=out.putObject("event").put("id",s.path("eventBattle").asInt());
            if(s.has("eventPicture"))event.set("pice",s.path("eventPicture"));
        }
        if(s.has("fight")){
            if(s.path("fight").path("expiry").asLong()>Instant.now().getEpochSecond())out.set("start",s.path("fight"));
            else{s.remove("fight");save(role,s);}
        }
        return out;
    }

    /** 检查待结束的事件战斗。
     * @param role 角色标识
     * @return 是否有占用战斗通道的旅行事件
     */
    public boolean active(String role){
        List<String> rows=jdbc.queryForList("SELECT payload FROM local_travel_state WHERE role_id=? AND payload LIKE ?",
                String.class,role,"%\"fight\":%");
        return !rows.isEmpty() && parse(rows.get(0)).path("fight").path("expiry").asLong()>Instant.now().getEpochSecond();
    }

    /**
     * 接收真实战斗动作并仅在有效胜利时发放原事件奖励。
     * @param role 角色标识
     * @param skill 原技能编号
     * @param targets 选取的敌方单位
     * @param flag 动作类型
     * @param flee 是否离场
     * @return 旅行战斗处理结果；不存在时为空
     */
    @Transactional
    public ObjectNode advance(String role,int skill,List<?> targets,int flag,boolean flee){
        if(!active(role))return null;
        roles.lockRole(role);
        ObjectNode s=load(role,Instant.now().getEpochSecond());
        if(!s.has("fight"))return null;
        ObjectNode fight=(ObjectNode)s.get("fight");
        int result=fight.path("expiry").asLong()<=Instant.now().getEpochSecond()?2:combat.advance(fight,skill,targets,flag,flee);
        ObjectNode out=json.createObjectNode().put("outcome",result);
        if(result==1)applyEvent(role,null,events.path(fight.path("stage").asText()),out);
        if(result==1 || result==2)s.remove("fight");
        save(role,s);
        return out;
    }
}
