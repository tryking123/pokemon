package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 从实际对局记录保存并展示可分页查看的本地战报。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:12
 */
final class LeagueReports {
    private static final int PAGE_SIZE = 12;
    private final ArenaMapper mapper;
    private final ArenaCombat combat;
    private final ObjectMapper json;

    /**
     * 复用竞技存档，按场分行写入以适配 Android SQLite 游标。
     * @param mapper 竞技存档
     * @param combat 精灵及技能显示名
     * @param json 应答编码器
     */
    LeagueReports(ArenaMapper mapper,ArenaCombat combat,ObjectMapper json) {
        this.mapper=mapper;
        this.combat=combat;
        this.json=json;
    }

    /**
     * 保存对局引擎本场实际行动，名单中仅保留引用键。
     * @param event 冻结名册
     * @param match 对局记录
     * @param steps 每次行动、目标和净生命变化
     * @param ordinal 期内对局序号
     */
    void save(ObjectNode event,ObjectNode match,JsonNode steps,int ordinal) {
        String owner = event.path("owner").asText();
        if (owner.isEmpty() || steps.isEmpty()) return;
        String key = (event.has("buff") ? "elite-log:" : "league-log:") + event.path("week").asLong() + ":" + ordinal;
        ObjectNode record = json.createObjectNode();
        record.set("steps",steps);
        mapper.save(owner,key,record);
        match.put("report",key);
    }

    /**
     * 清理不再处于保留期的赛季日志。
     * @param roleId 当前角色
     * @param event 已到期的存档
     */
    void purge(String roleId,JsonNode event) {
        for (JsonNode match : event.path("matches")) if (match.has("report")) mapper.remove(roleId,match.path("report").asText());
    }

    /**
     * 仅查询已实际发布的对局，与未到期的内部赛程隔离。
     * @param roleId 当前角色
     * @param book 联赛存档
     * @param type 所请求的赛制
     * @param stage 已冻结的训练师阶段
     * @param a 参赛者之一
     * @param b 另一参赛者
     * @param idx 两名参赛者间的第几场
     * @param page 本场日志页
     * @return 可用于原回放按钮的应答
     */
    ObjectNode view(String roleId,ObjectNode book,int type,int stage,String a,String b,int idx,int page) {
        ObjectNode out = json.createObjectNode().put("errno",2003);
        if (a.equals(b) || idx < 1 || page < 1) return out;
        boolean elite = type == 250;
        long today = (Instant.now().getEpochSecond()+28800)/86400;
        List<JsonNode> events = new ArrayList<>();
        events.add(book.path(elite ? "elite" : "current"));
        for (JsonNode old : book.path(elite ? "eliteArchives" : "archives")) events.add(old);
        for (JsonNode event : events) {
            if (event.path("stage").asInt(-1) != stage) continue;
            JsonNode seats = event.path("seats");
            List<JsonNode> pairs = new ArrayList<>();
            JsonNode matches = event.path("matches");
            for (int i=0; i<matches.size(); i++) {
                int due = elite ? (i < 4 ? 3 : i < 6 ? 5 : 7) : (i < 12 ? 2+i/4 : i<14 ? 5 : 6);
                if (today >= event.path("week").asLong()+due-1+14) continue;
                JsonNode match = matches.get(i);
                String left = seats.get(match.path("left").asInt()).path("profile").path("uid").asText();
                String right = seats.get(match.path("right").asInt()).path("profile").path("uid").asText();
                if (a.equals(left) && b.equals(right) || b.equals(left) && a.equals(right)) pairs.add(match);
            }
            if (pairs.size() < idx) continue;
            JsonNode match = pairs.get(idx-1);
            JsonNode left = seats.get(match.path("left").asInt());
            JsonNode right = seats.get(match.path("right").asInt());
            JsonNode steps = match.has("report") ? mapper.load(roleId,match.path("report").asText()).path("steps") : json.createArrayNode();
            int pages = Math.max(1,(steps.size()+PAGE_SIZE-1)/PAGE_SIZE);
            if (page>pages) return out;
            String leftName = left.path("profile").path("name").asText();
            String rightName = right.path("profile").path("name").asText();
            String winner = match.path("winner").asInt() == match.path("left").asInt() ? leftName : rightName;
            StringBuilder report = new StringBuilder().append(leftName).append("（左） 对 ").append(rightName)
                    .append("（右）\n第").append(idx).append("场，共").append(pairs.size()).append("场。")
                    .append(winner).append("胜。左、右方击倒数分别为").append(match.path("rightLoss").asInt())
                    .append("、").append(match.path("leftLoss").asInt()).append("。\n");
            for (int i=(page-1)*PAGE_SIZE; i<Math.min(page*PAGE_SIZE,steps.size()); i++) {
                JsonNode step = steps.get(i);
                report.append("\n").append(i+1).append(". 第").append(step.get(0).asInt()).append("回合 ")
                        .append(actor(left,right,step.get(1).asInt()));
                int skill = step.get(2).asInt();
                if (skill == 0) report.append("待机");
                else report.append("使用").append(combat.leagueNames.path("skills").path(Integer.toString(skill)).asText("技能"+skill));
                if (!step.get(3).isEmpty()) {
                    report.append(" → ");
                    String sep="";
                    for (JsonNode target : step.get(3)) {
                        report.append(sep).append(actor(left,right,target.asInt()));
                        sep="、";
                    }
                }
                JsonNode delta = step.get(4);
                for (int j=0; j<delta.size(); j+=2) {
                    int n = delta.get(j+1).asInt();
                    report.append("；").append(actor(left,right,delta.get(j).asInt())).append("生命")
                            .append(n>0 ? "+" : "").append(n);
                }
            }
            if (page<pages || idx<pairs.size()) {
                out.put("nextidx",page<pages ? idx : idx+1);
                out.put("nextpage",page<pages ? page+1 : 1);
                report.append("\n\n确定继续查看，取消关闭。");
            }
            out.put("errno",0);
            out.put("idx",idx);
            out.put("total",pairs.size());
            out.put("seed",match.path("seed").asInt());
            out.put("weather",0);
            out.set("left",wireTeam(left));
            out.set("right",wireTeam(right));
            out.put("report",report.toString());
            return out;
        }
        return out;
    }

    /**
     * 复制冻结阵容并移除仅供本地回合计算的字段。
     * @param seat 已参赛席位
     * @return 符合 formation_t 的完整阵容
     */
    private ObjectNode wireTeam(JsonNode seat) {
        ObjectNode team = ((ObjectNode)seat.path("team")).deepCopy();
        for (JsonNode member : team.path("pokemon")) {
            ((ObjectNode)member).remove(Arrays.asList("goldCarry", "homeStatues","baseDamage","targetLimit","physical"));
        }
        return team;
    }

    /**
     * 使用场上位置定位冻结阵容中的实际精灵。
     * @param left 左方签位快照
     * @param right 右方签位快照
     * @param position 带正负阵营标记的位置
     * @return 可区别同种精灵的阵位及名称
     */
    private String actor(JsonNode left,JsonNode right,int position) {
        int slot=Math.abs(position);
        String label=(position>0 ? "左" : "右")+slot;
        for (JsonNode member : (position>0 ? left : right).path("team").path("pokemon")) {
            if (member.path("position").asInt()==slot) {
                String sid=member.path("sid").asText();
                return label+combat.leagueNames.path("species").path(sid).asText("精灵"+sid);
            }
        }
        return label;
    }
}
