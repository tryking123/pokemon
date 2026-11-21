package com.pokemon.local.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家园神塔遗迹翻牌配对、原奖励与本服周榜。
 * @author tongruiyuan
 * @since 2026-11-19 21:47
 */
@Service
public class LinkGameService {
    private final com.pokemon.local.guild.PersonalGuildService guilds;
    private final HomeService home;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private static final int[][] AWARDS = {{100,5000,10,5},{150,4000,8,4},{200,2500,5,2},
            {250,1500,3,1},{300,1000,2,0},{1000,500,1,0}};

    /** 绑定同一数据库的家园、资产和角色服务。
     * @param guilds 徽章研究状态
     * @param home 家园
     * @param roles 角色及货币
     * @param jdbc 本地存档
     * @param json 原消息转换
     * @param inventory 背包与邮箱
     */
    public LinkGameService(com.pokemon.local.guild.PersonalGuildService guilds,HomeService home,RoleService roles,JdbcTemplate jdbc,ObjectMapper json,InventoryService inventory) {
        this.guilds=guilds;
        this.home=home;
        this.roles=roles;
        this.jdbc=jdbc;
        this.json=json;
        this.inventory=inventory;
    }

    /** 解析已保存的盘面或回执。
     * @param value 数据库内容
     * @return 游戏对象
     */
    private ObjectNode decode(String value) {
        try {
            return (ObjectNode)json.readTree(value);
        } catch(IOException e) {
            throw new IllegalStateException("神塔遗迹存档损坏",e);
        }
    }

    /** 读取角色盘面和本日次数。
     * @param roleId 角色编号
     * @return 当前盘面状态
     */
    private ObjectNode state(String roleId) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_link_state WHERE role_id=?",String.class,roleId);
        ObjectNode state=rows.isEmpty()?json.createObjectNode():decode(rows.get(0));
        long day=(Instant.now().getEpochSecond()+28800)/86400;
        if(state.path("day").asLong()!=day) {
            state.put("day",day).put("times",0).put("buytimes",0);
        }
        return state;
    }

    /** 返回原 S821 次数。
     * @param roleId 角色编号
     * @return 已用次数和已购次数
     */
    public ObjectNode snapshot(String roleId) {
        ObjectNode saved = state(roleId);
        return json.createObjectNode().put("times",saved.path("times").asInt()).put("buytimes",saved.path("buytimes").asInt());
    }

    /** 处理原神塔遗迹入口及每一次合法翻牌。
     * @param roleId 当前角色
     * @param command 原命令
     * @param idx 前端格号
     * @param conn 连接编号
     * @param session 客户端请求号
     * @return 原响应与本次实际到账内容
     */
    @Transactional
    public ObjectNode handle(String roleId,int command,long idx,String conn,long session) {
        LocalRole role=roles.lockRole(roleId);
        ObjectNode fail=json.createObjectNode().put("errno",100021);
        if(role.sceneId!=80011 || home.level(roleId)<1) {
            return fail;
        }
        String key="link:"+conn+":"+session+":"+command;
        String record=home.reply(roleId,key);
        if(record!=null) {
            ObjectNode old=decode(record);
            return old.path("input").asLong()==idx?(ObjectNode)old.get("reply"):fail;
        }
        ObjectNode s=state(roleId),reply=json.createObjectNode().put("errno",0);
        if(command==353) {
            reply.set("list",ranking(role.realmId));
            return reply;
        }
        int bought = s.path("buytimes").asInt();
        if (command == 352) {
            if (bought >= 3 || s.path("times").asInt() < 2 + bought
                    || !inventory.spendMaterials(roleId, 0, 0, 0, 50L * (bought + 1))) return fail;
            s.put("buytimes", bought + 1);
        } else if(command==349) {
            if(s.path("times").asInt()>=2+bought || s.has("board") && conn.equals(s.path("conn").asText())) {
                return fail;
            }
            List<Integer> signs=new ArrayList<>();
            for(int i=1;i<=28;i++) {
                signs.add(i);
                signs.add(i);
            }
            Collections.shuffle(signs);
            s.set("board",json.valueToTree(signs));
            s.put("conn",conn).put("first",0).put("remaining",28).put("steps",0).put("times",s.path("times").asInt()+1);
        } else if(command==412) {
            if (s.has("board") && conn.equals(s.path("conn").asText()) || s.path("times").asInt()>=2+bought || guilds.stoneLeagueSuitLevel(roleId)<6) {
                return fail;
            }
            s.put("times",s.path("times").asInt()+1);
            s.remove("board");
            award(role,reply,key,AWARDS[java.util.concurrent.ThreadLocalRandom.current().nextInt(4)][0]);
        } else if(command==351 && conn.equals(s.path("conn").asText())) {
            s.remove("board");
        } else if(command==350 && s.has("board") && conn.equals(s.path("conn").asText())) {
            int line=(int)(idx/10),column=(int)(idx%10);
            if(idx<11 || idx>87 || line<1 || line>8 || column<1 || column>7) {
                return fail;
            }
            int pos=(line-1)*7+column-1,first=s.path("first").asInt();
            ArrayNode board=(ArrayNode)s.get("board");
            int sign=board.get(pos).asInt();
            if(sign==0 || first==pos+1) {
                return fail;
            }
            int steps=s.path("steps").asInt()+1;
            s.put("steps",steps).put("first",first==0?pos+1:0);
            reply.put("idx",idx).put("num",sign).put("allstep",steps);
            if(first!=0 && board.get(first-1).asInt()==sign) {
                board.set(first-1,json.getNodeFactory().numberNode(0));
                board.set(pos,json.getNodeFactory().numberNode(0));
                int left=s.path("remaining").asInt()-1;
                s.put("remaining",left);
                if(left==0) {
                    s.remove("board");
                    finish(role,s,reply,key,steps);
                }
            }
        } else {
            return fail;
        }
        ObjectNode wire=reply.deepCopy();
        wire.remove("adds");
        ObjectNode saved=json.createObjectNode().put("input",idx);
        saved.set("reply",wire);
        home.record(roleId,key,saved.toString());
        jdbc.update("MERGE INTO local_link_state(role_id,state_json) KEY(role_id) VALUES(?,?)",roleId,s.toString());
        return reply;
    }

    /** 本服本周的最佳步数。
     * @param realm 区服编号
     * @return 原榜单行
     */
    private ArrayNode ranking(int realm) {
        long week=((Instant.now().getEpochSecond()+28800)/86400+3)/7;
        ArrayNode result=json.createArrayNode();
        jdbc.query("SELECT r.id,r.name,r.avatar,l.steps FROM local_link_rank l JOIN local_game_role r ON r.id=l.role_id WHERE l.week_id=? AND r.realm_id=? ORDER BY l.steps,r.id",
                (org.springframework.jdbc.core.RowCallbackHandler)row->{
                    if(result.size()<100) result.addObject().put("uid",row.getString(1)).put("name",row.getString(2))
                            .put("icon",row.getInt(3)).put("allstep",row.getInt(4));
                },week,realm);
        return result;
    }

    /** 登记完整配对的成绩并按原表入账。
     * @param role 角色
     * @param state 本局存档
     * @param reply 协议结果
     * @param key 结算回执
     * @param steps 翻牌总数
     */
    private void finish(LocalRole role,ObjectNode state,ObjectNode reply,String key,int steps) {
        long week=((Instant.now().getEpochSecond()+28800)/86400+3)/7;
        List<Integer> old=jdbc.queryForList("SELECT steps FROM local_link_rank WHERE week_id=? AND role_id=?",Integer.class,week,role.id);
        if(old.isEmpty() || old.get(0)>steps) {
            jdbc.update("MERGE INTO local_link_rank(role_id,week_id,steps) KEY(role_id,week_id) VALUES(?,?,?)",role.id,week,steps);
        }
        state.put("best",Math.min(state.path("best").asInt(Integer.MAX_VALUE),steps));
        award(role,reply,key,steps);
    }

    /** 根据步数档发放钻石和家园道具。
     * @param role 角色
     * @param reply 回执
     * @param key 发放凭据
     * @param steps 配对步数
     */
    private void award(LocalRole role,ObjectNode reply,String key,int steps) {
        ObjectNode adds=reply.putObject("adds");
        for(int[] tier:AWARDS) {
            if(steps<=tier[0]) {
                roles.addCurrency(role.id,0,tier[1],0);
                adds.put("diamonds",tier[1]);
                Map<Integer,Long> items=new LinkedHashMap<>();
                items.put(23043,(long)tier[2]);
                if(tier[3]>0) items.put(23045,(long)tier[3]);
                ArrayNode sent=adds.putArray("items");
                inventory.grantItemsOrMail(role.id,key,items).forEach((id,n)->sent.addObject().put("id",id).put("n",n));
                break;
            }
        }
    }
}
