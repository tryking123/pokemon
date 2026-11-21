package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.activity.MazeService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 宝可梦迷宫地图、宝箱、原奖励面板和每周榜单。
 * @author tongruiyuan
 * @since 2026-11-20 13:32
 */
@Service
final class MazeGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> WIRE = new TypeReference<Map<String,Object>>(){};
    private final MazeService maze;
    private final ObjectMapper json;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final InventoryGameFeature mailbox;
    private final Map<GameReply,View> views=new WeakHashMap<>();

    /** 绑定地图、原资产快照和积分邮件。
     * @param maze 迷宫服务
     * @param json 原协议转换器
     * @param roles 角色钱包
     * @param bags 道具存量
     * @param mailbox 邮件角标
     */
    MazeGameFeature(MazeService maze,ObjectMapper json,RoleService roles,BagSnapshot bags,InventoryGameFeature mailbox){
        this.maze=maze;this.json=json;this.roles=roles;this.bags=bags;this.mailbox=mailbox;
    }

    /** 将原字段转为网关协议对象。 */
    private Map<String,Object> wire(JsonNode node){return json.convertValue(node,WIRE);}

    /** 获取连接已展示的地图及商店版本。 */
    private synchronized View view(GameReply out){return views.computeIfAbsent(out,k->new View());}

    /** 读取并结清断线期间结束的活动。
     * @param role 当前角色
     * @param out 当前连接
     * @throws IOException 网络写入失败
     */
    @Override public void synchronize(LocalRole role,GameReply out)throws IOException{tick(role,out);}

    /** 在线经过周日结算和下周开始时重新计算结算时间。
     * @param role 当前角色
     * @param out 当前连接
     * @throws IOException 网络写入失败
     */
    @Override public void tick(LocalRole role,GameReply out)throws IOException{
        View v=view(out);long now=System.currentTimeMillis()/1000;
        if(now<v.next)return;
        ActivityPeriod p=maze.period();if(maze.settle(role.id)>0)mailbox.pushMailCount(role,out);
        v.next=now<p.end?p.end:p.showUntil;
    }

    /** 处理原地图七项操作，让宝箱与奖励在移动动画结束时出现。
     * @param role 当前角色
     * @param req 客户端请求
     * @param out 当前连接
     * @return 是否处理该请求
     * @throws IOException 网络写入失败
     */
    @Override public boolean handle(LocalRole role,SprotoProtocol.Request req,GameReply out)throws IOException{
        int id=req.type;if(id<10026 || id>10032)return false;if(req.session<=0)return true;
        View v=view(out);
        ObjectNode result=maze.act(role.id,id,json.valueToTree(req.body),id==10032?v.shopWeek:v.boardWeek,
                id==10032?v.shopRev:v.boardRev,v.connection+":"+req.session);
        JsonNode reply=result.get("reply");
        if(reply.path("errno").asInt()==0){
            long week=result.path("season").asLong();int rev=result.path("revision").asInt();
            if(id==10027 || id==10032){v.shopWeek=week;v.shopRev=rev;}
            else if(id>=10028){v.boardWeek=week;v.boardRev=rev;}
        }
        if(result.has("box"))out.push(10005,wire(result.get("box")));
        if(result.has("adds"))out.push(14,data("flags",id==10030?"do_open_maze_box":id==10029?"do_maze_throw":"buy_maze_shop","adds",wire(result.get("adds"))));
        if(result.has("wallet")){
            LocalRole fresh=roles.findById(role.id).orElse(role);
            out.push(3,data("diamonds",fresh.diamonds,"coins",fresh.coins));
        }
        out.send(req,wire(reply));
        if(result.has("adds"))bags.push(role.id,out);
        if(result.has("mailed"))mailbox.pushMailCount(role,out);
        return true;
    }

    /** 当前连接已经展示过的周次及动作版本。 */
    private static final class View {
        final String connection=UUID.randomUUID().toString();
        long boardWeek=-1,shopWeek=-1,next;
        int boardRev=-1,shopRev=-1;
    }
}