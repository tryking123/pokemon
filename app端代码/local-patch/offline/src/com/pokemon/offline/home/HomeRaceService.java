package com.pokemon.offline.home;

import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.pokemon.offline.guild.PersonalGuildService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 饲育屋自行车赛，保存每局五条赛道和服务端计时成绩。
 * @author tongruiyuan
 * @since 2026-11-19 21:57
 */
@Service
public class HomeRaceService {
    private final HomeService home;
    private final com.pokemon.offline.activity.LuckyBagService luckyBag;
    private final PersonalGuildService guild;
    private final RoleService roles;
    private final InventoryService bag;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final JsonNode rules;
    private static final int[] PRICES={1000,2000,2000,3000,3000};

    /** 绑定家园和资产服务，加载原赛跑配置。
     * @param luckyBag 福袋真实自行车参赛任务
     * @param home 家园
     * @param guild 徽章套装
     * @param roles 角色钱包
     * @param bag 背包
     * @param json 协议对象转换
     * @param jdbc 本地数据库
     */
    public HomeRaceService(HomeService home,PersonalGuildService guild,RoleService roles,InventoryService bag,ObjectMapper json,JdbcTemplate jdbc,com.pokemon.offline.activity.LuckyBagService luckyBag) {
        this.luckyBag=luckyBag;
        this.home=home;this.guild=guild;this.roles=roles;this.bag=bag;this.json=json;this.jdbc=jdbc;
        try(InputStream in=new ClassPathResource("home/race.json").getInputStream()) {
            rules=json.readTree(in);
        } catch(IOException e) {throw new IllegalStateException("自行车赛配置损坏",e);}
    }

    /** 解码已写入的数据。
     * @param text 保存内容
     * @return 游戏对象
     */
    private ObjectNode read(String text) {
        try {return (ObjectNode)json.readTree(text);} catch(IOException e) {throw new IllegalStateException("自行车赛存档损坏",e);}
    }

    /** 查询盘面及自然日额度。
     * @param roleId 当前角色
     * @return 游戏状态
     */
    private ObjectNode state(String roleId) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_home_race WHERE role_id=?",String.class,roleId);
        ObjectNode s=rows.isEmpty()?json.createObjectNode():read(rows.get(0));
        long day=(Instant.now().getEpochSecond()+28800)/86400;
        if(s.path("day").asLong()!=day) s.put("day",day).put("times",0).put("buytimes",0);
        return s;
    }

    /** 写入本次动作。
     * @param id 角色
     * @param s 当前状态
     */
    private void save(String id,ObjectNode s) {
        jdbc.update("MERGE INTO local_home_race(role_id,state_json) KEY(role_id) VALUES(?,?)",id,s.toString());
    }

    /** 原 S822。
     * @param id 角色
     * @return 当日已用及已购次数
     */
    public ObjectNode snapshot(String id) {
        ObjectNode s=state(id);
        return json.createObjectNode().put("times",s.path("times").asInt()).put("buytimes",s.path("buytimes").asInt());
    }

    /**
     * 把盲盒得到的时装碎片加入同一角色的服饰背包。
     * @param id 已持有角色锁的角色
     * @param rewards 按原时装碎片编号汇总的到账数
     */
    @Transactional
    public void addDressChips(String id,Map<Integer,Long> rewards) {
        roles.lockRole(id);
        ObjectNode s=state(id), chips=s.with("chips");
        rewards.forEach((item,amount)->{
            String key=String.valueOf(item);
            chips.put(key,Math.addExact(chips.path(key).asLong(),amount));
        });
        save(id,s);
    }

    /** 查询角色通过自行车赛和盲盒取得的时装碎片。
     * @param id 角色
     * @return 原时装碎片背包条目
     */
    public ArrayNode chips(String id) {
        ArrayNode out=json.createArrayNode();
        state(id).path("chips").fields().forEachRemaining(e->out.addObject().put("id",Integer.parseInt(e.getKey())).put("n",e.getValue().asLong()));
        return out;
    }

    /** 创建五道原台面并冻结预览奖。
     * @return 原赛道列表
     */
    private ArrayNode board() {
        ArrayNode out=json.createArrayNode();
        for(int i=1;i<=5;i++) {
            JsonNode row=rules.path(Integer.toString(i));
            ObjectNode racer=out.addObject().put("idx",i).put("sid",i).put("length",0).put("frame",0)
                    .put("pace",random(row,"inception")).put("rank",i).put("bet",false).put("cheer",false).put("cooldown",0).put("gas",false);
            JsonNode pool=row.path("reward");
            int ticket=ThreadLocalRandom.current().nextInt(10000);
            for(int j=0;j<pool.size();j+=4) {
                ticket-=pool.get(j).asInt();
                if(ticket<0) {
                    racer.putArray("award").add(pool.get(j+1).asInt()).add(pool.get(j+2).asInt()).add(pool.get(j+3).asInt());
                    break;
                }
            }
        }
        return out;
    }

    /** 读取一个原配置区间的随机整数。
     * @param rule 选手配置
     * @param name 原字段名
     * @return 区间内整数
     */
    private int random(JsonNode rule,String name) {
        JsonNode range=rule.path(name);
        return ThreadLocalRandom.current().nextInt(range.get(0).asInt(),range.get(1).asInt()+1);
    }

    /** 响应原赛跑操作并持久化每个请求。
     * @param id 角色
     * @param type 原命令
     * @param idx 赛道号
     * @param conn 连接编号
     * @param sequence 原请求号
     * @return 原应答数据
     */
    @Transactional
    public ObjectNode handle(String id,int type,long idx,String conn,long sequence) {
        LocalRole role=roles.lockRole(id);
        ObjectNode fail=json.createObjectNode().put("errno",100021);
        if(role.sceneId!=80011 || home.level(id)<3) return fail;
        ObjectNode s=state(id),r=json.createObjectNode().put("errno",0);
        String key="race:"+conn+":"+type+":"+sequence;
        String history=home.reply(id,key);
        if(history!=null) {
            ObjectNode saved=read(history);
            return saved.path("idx").asLong()==idx?(ObjectNode)saved.get("response"):fail;
        }
        boolean active=conn.equals(s.path("conn").asText()) && s.path("board").isArray();
        long now=Instant.now().getEpochSecond();
        int used=s.path("times").asInt(),bought=s.path("buytimes").asInt();
        if(type==360) {
            r.set("list",ranking(role.realmId));
            return r;
        }
        if(type==355) {
            if(bought>=PRICES.length) return fail;
            if(!bag.spendMaterials(id,Collections.emptyMap(),0,PRICES[bought])) return fail.put("errno",100006);
            s.put("buytimes",bought+1);
        } else if(type==354) {
            if(active || used>=1+bought) return fail;
            s.remove(Arrays.asList("start","lastCheer","boost","natural"));
            s.put("conn",conn).put("times",used+1).put("frame",0);
            s.set("board",board());
            r.set("board",s.get("board").deepCopy());
        } else if(type==359 && conn.equals(s.path("conn").asText())) {
            s.remove(Arrays.asList("board","start"));
        } else if(!active) {
            return fail;
        } else if(type==357 && !s.has("start")) {
            luckyBag.participate(id,61001);
            s.put("start",now);r.put("startime",now).set("board",s.get("board").deepCopy());
        } else if(idx>=1 && idx<=5) {
            ObjectNode racer=(ObjectNode)s.path("board").get((int)idx-1);
            int suit=guild.raceSuitLevel(id);
            if(type==356 && !s.has("start") && !racer.path("bet").asBoolean()) {
                int count=0;
                for(JsonNode other:s.path("board")) if(other.path("bet").asBoolean()) count++;
                if(count>=(suit>=6?2:1)) return fail;
                racer.put("bet",true);
            } else if(type==358 && s.has("start") && now<s.path("start").asLong()+30 && racer.path("bet").asBoolean()) {
                if(now-s.path("lastCheer").asLong()<4) return fail;
                if(suit<3 && !bag.spendMaterials(id,Collections.emptyMap(),50000,0)) return fail.put("errno",100005);
                s.put("lastCheer",now);
                JsonNode rule=rules.path(String.valueOf(idx));
                if(ThreadLocalRandom.current().nextInt(1000)<rule.path("probability").asInt()) {
                    s.with("boost").put(String.valueOf(idx),now-s.path("start").asLong()+rule.path("last").asInt());
                }
            } else return fail;
            r.put("idx",idx);
        } else return fail;
        save(id,s);
        ObjectNode receipt=json.createObjectNode().put("idx",idx);
        receipt.set("response",r);home.record(id,key,receipt.toString());
        return r;
    }

    /** 每秒按真实赛时生成 S823，连接断开后该连接的推进随即终止。
     * @param id 角色
     * @param conn 原连接
     * @return 当前帧，已离开赛场时为空
     */
    @Transactional
    public ObjectNode tick(String id,String conn) {
        LocalRole role=roles.lockRole(id);
        ObjectNode s=state(id);
        if(!conn.equals(s.path("conn").asText()) || !s.has("start") || !s.has("board") || role.sceneId!=80011) return null;
        int previous=s.path("frame").asInt();
        int frame=(int)Math.min(30,Instant.now().getEpochSecond()-s.path("start").asLong());
        ArrayNode board=(ArrayNode)s.get("board");
        for(int t=previous+1;t<=frame;t++) {
            for(JsonNode node:board) {
                ObjectNode racer=(ObjectNode)node;
                String lane=racer.path("idx").asText();
                JsonNode rule=rules.path(lane);
                ObjectNode natural=s.with("natural");
                int next=natural.path(lane).asInt();
                if(next==0 || t==next) {
                    if(next!=0) s.with("boost").put(lane,Math.max(s.path("boost").path(lane).asInt(),t+rule.path("last").asInt()-1));
                    natural.put(lane,t+random(rule,"time"));
                }
                boolean gas=t<=s.path("boost").path(lane).asInt();
                int pace=random(rule,"inception")+(gas?random(rule,"speed"):0);
                racer.put("pace",pace).put("length",racer.path("length").asInt()+pace).put("frame",t).put("gas",gas)
                        .put("cheer",racer.path("bet").asBoolean()).put("cooldown",Math.max(0,s.path("lastCheer").asLong()+4-s.path("start").asLong()-t));
            }
        }
        List<JsonNode> sorted=new ArrayList<>();
        board.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt((JsonNode n)->n.path("length").asInt()).reversed().thenComparingInt(n->n.path("idx").asInt()));
        for(int i=0;i<sorted.size();i++) ((ObjectNode)sorted.get(i)).put("rank",i+1);
        ObjectNode output=json.createObjectNode().put("isend",frame==30);
        output.set("board",board.deepCopy());
        s.put("frame",frame);
        if(frame==30) {
            JsonNode winner=sorted.get(0);
            ObjectNode adds=output.putObject("adds");
            if(winner.path("bet").asBoolean()) award(id,s,winner.path("award"),adds,conn+":"+s.path("start").asText());
            else {
                roles.addCurrency(id,350000,500,0);
                adds.put("coins",350000).put("diamonds",500);
            }
            int best=0;
            for(JsonNode racer:board) if(racer.path("bet").asBoolean()) best=Math.max(best,racer.path("length").asInt());
            if(best>0) {
                long week=(s.path("day").asLong()+3)/7;
                List<Integer> old=jdbc.queryForList("SELECT distance FROM local_home_race_rank WHERE role_id=? AND week_id=?",Integer.class,id,week);
                if(old.isEmpty() || old.get(0)<best) jdbc.update("MERGE INTO local_home_race_rank(role_id,week_id,distance) KEY(role_id,week_id) VALUES(?,?,?)",id,week,best);
            }
            s.remove(Arrays.asList("board","start"));
        }
        save(id,s);
        return output;
    }

    /** 将已展示的冠军奖励按原类型到账。
     * @param id 角色
     * @param s 存档
     * @param award 原三元组
     * @param adds 实际到账
     * @param key 发放回执
     */
    private void award(String id,ObjectNode s,JsonNode award,ObjectNode adds,String key) {
        int type=award.get(0).asInt(),item=award.get(1).asInt(),amount=award.get(2).asInt();
        if(type==103 || type==104) {
            roles.addCurrency(id,type==103?amount:0,type==104?amount:0,0);
            adds.put(type==103?"coins":"diamonds",amount);
        } else if(type==1) {
            ArrayNode sent=adds.putArray("items");
            bag.grantItemsOrMail(id,"race:"+key,Collections.singletonMap(item,(long)amount)).forEach((i,n)->sent.addObject().put("id",i).put("n",n));
        } else if(type==7) {
            String keyId=String.valueOf(item);
            ObjectNode chips=s.with("chips");
            chips.put(keyId,chips.path(keyId).asLong()+amount);
            adds.putArray("fasheschips").addObject().put("id",item).put("n",amount);
        } else if(type==6) {
            // 本地角色默认拥有全部时装，整件同款的获得次数另存。
            String keyId=String.valueOf(item);
            ObjectNode dresses=s.with("dresses");
            dresses.put(keyId,dresses.path(keyId).asLong()+amount);
            adds.putArray("fashes").add(item);
        }
    }

    /** 同服本周有效成绩，按支持选手的最远距离排序。
     * @param realm 本服编号
     * @return 原排行行
     */
    private ArrayNode ranking(int realm) {
        long week=((Instant.now().getEpochSecond()+28800)/86400+3)/7;
        ArrayNode out=json.createArrayNode();
        jdbc.query("SELECT r.id,r.name,r.avatar,l.distance FROM local_home_race_rank l JOIN local_game_role r ON r.id=l.role_id WHERE l.week_id=? AND r.realm_id=? ORDER BY l.distance DESC,r.id",
                (com.pokemon.offline.platform.jdbc.core.RowCallbackHandler)row->{if(out.size()<100) out.addObject().put("uid",row.getString(1)).put("name",row.getString(2)).put("icon",row.getInt(3)).put("alldistance",row.getInt(4));},week,realm);
        return out;
    }
}
