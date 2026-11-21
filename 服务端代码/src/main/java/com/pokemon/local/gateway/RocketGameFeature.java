package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.RocketService;
import com.pokemon.local.inventory.ArenaPointShopService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 火箭队试练的原面板、排行及三队连续战斗协议。
 * @author tongruiyuan
 * @since 2026-11-20 12:48
 */
@Order(-141)
@Service
final class RocketGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> FIELDS=new TypeReference<Map<String,Object>>(){};
    private static final TypeReference<List<Map<String,Object>>> LIST=new TypeReference<List<Map<String,Object>>>(){};
    private final RocketService rocket;
    private final BagSnapshot bags;
    private final ArenaPointShopService points;
    private final RoleService roles;
    private final ObjectMapper json;
    private final Map<GameReply,View> views=Collections.synchronizedMap(new WeakHashMap<>());

    /** 连接的赛季与到期时间，保护过期预览和同一请求的消费。 */
    private static final class View {
        final String id=UUID.randomUUID().toString();
        long week,next;
        JsonNode state;
    }

    /**
     * 连接火箭队协议与各资产快照。
     * @param rocket 火箭队业务
     * @param bags 真实背包及邮件
     * @param points 原 PK 积分钱包
     * @param roles 角色钱包
     * @param json 编解码工具
     */
    RocketGameFeature(RocketService rocket,BagSnapshot bags,ArenaPointShopService points,RoleService roles,ObjectMapper json) {
        this.rocket=rocket;this.bags=bags;this.points=points;this.roles=roles;this.json=json;
    }

    /**
     * 读取会随本机任务列表一起下发的赛季任务。
     * @param role 认证角色
     * @return 原任务快照
     */
    List<Map<String,Object>> tasks(LocalRole role) {
        return role.level<55?Collections.emptyList():json.convertValue(rocket.panel(role.id).get("tasks"),LIST);
    }

    /**
     * 推送已提交的三支队伍、活动状态和任务进度。
     * @param role 认证角色
     * @param output 原客户端连接
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply output) throws IOException {
        if(role.level<55)return;
        View view=views.computeIfAbsent(output,key->new View());
        ObjectNode state=rocket.panel(role.id);
        if(view.state==null || !state.get("panel").equals(view.state.get("panel")))
            output.push(457,json.convertValue(state.get("panel"),FIELDS));
        if(view.state==null || !state.get("tasks").equals(view.state.get("tasks")))
            for(JsonNode task:state.get("tasks"))output.push(101,json.convertValue(task,FIELDS));
        view.state=state;view.week=rocket.week();view.next=Instant.now().getEpochSecond()+30;
    }

    /**
     * 发送原协议需要的战斗字段，内部伤害缓存保留在存档。
     * @param snapshot 事务返回的独立快照
     * @param type 原请求或推送编号
     * @return 精确的原协议字段
     */
    private Map<String,Object> wire(ObjectNode snapshot,int type) {
        if(type==332) {
            for(JsonNode team:snapshot.path("teams"))wireTeam(team);
        } else if(type==333 || type==458) {
            snapshot.retain("errno","seed","weather","player","monster");
            if(type==458)snapshot.remove("errno");
            wireTeam(snapshot.path("player"));
            wireTeam(snapshot.path("monster"));
        }
        return json.convertValue(snapshot,FIELDS);
    }

    /**
     * 裁剪队伍中只供本机回合引擎使用的字段。
     * @param team 待发送的原阵容
     */
    private void wireTeam(JsonNode team) {
        for(JsonNode member:team.path("pokemon"))
            ((ObjectNode)member).remove(java.util.Arrays.asList("goldCarry", "homeStatues","baseDamage","targetLimit","physical","actions"));
    }

    /**
     * 自然日及赛季阶段切换后更新面板。
     * @param role 当前角色
     * @param output 原连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role,GameReply output) throws IOException {
        View view=views.get(output);
        if(view==null || view.next<=Instant.now().getEpochSecond())synchronize(role,output);
    }

    /**
     * 响应火箭队操作并按原先后发送下一场或最终结算。
     * @param role 认证角色
     * @param request 原请求
     * @param output 游戏连接
     * @return 是否处理请求
     * @throws IOException 应答失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        int type=request.type;
        if(type==3001 || type==3002) {
            if(!rocket.active(role.id))return false;
            Object argument=request.body.get("objs");
            ObjectNode event=rocket.turn(role.id,(int)number(request.body,"sid",0),
                    argument instanceof List?(List<?>)argument:Collections.emptyList(),(int)number(request.body,"flag",-1),type==3002);
            if(event.has("next"))output.push(458,wire((ObjectNode)event.get("next"),458));
            if(event.has("result")) {output.push(459,json.convertValue(event.get("result"),FIELDS));synchronize(role,output);}
            return true;
        }
        long task=number(request.body,"taskid",0);
        if(type>=330 && type<=336 || type==1201 && task>=60001 && task<=60020) {
            if(request.session<=0)return true;
            View view=views.computeIfAbsent(output,k->new View());
            ObjectNode result=rocket.act(role.id,type,json.valueToTree(request.body),view.week,view.id+":"+request.session);
            output.send(request,wire(result,type));
            if(result.path("errno").asInt()==0 && (type==336 || type==1201 || type==335)) {
                LocalRole saved=roles.findById(role.id).orElse(role);
                output.push(3,data("diamonds",saved.diamonds,"pkpoint",points.balance(role.id)));
                bags.push(role.id,output);bags.pushMailCount(role.id,output);
            }
            if(type!=333)synchronize(role,output);
            return true;
        }
        switch(type) {
            case 8: case 66: case 68: case 69: case 102: case 105: case 109: case 121: case 123: case 127:
            case 168: case 169: case 181: case 182: case 268: case 272: case 273: case 285: case 300:
            case 2203: case 3037: case 5069: case 5071: case 5072: case 5101: case 10016: case 10040:
                if(!rocket.active(role.id))return false;
                if(request.session>0)output.send(request,data("errno",2002));return true;
            default:return false;
        }
    }
}
