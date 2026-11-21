package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 训练中心的努力值、路线、可用次数与限时伤情 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:03
 */
@Service
public class StriveBoardService {
    private static final JsonNode RULES = ElfTrainingCatalog.load("elf/strive-board.json");
    private static final String[] ATTRS = {
        "",
        "hp_max",
        "physical_attack",
        "special_attack",
        "physical_defense",
        "special_defense",
        "speed"
    };
    private static final int VISIBLE = 7;
    private final JdbcTemplate jdbc;
    private final ElfMapper elves;
    private final RoleService roles;
    private final InventoryService bag;
    private final ObjectMapper json;

    /**
     * 建立设备或 PC 的角色训练存档。
     *
     * @param jdbc 本地数据源
     * @param elves 精灵培养资料
     * @param roles 角色锁和开放条件
     * @param bag 真实钱包
     * @param json 存档编解码器
     */
    public StriveBoardService(
            JdbcTemplate jdbc,
            ElfMapper elves,
            RoleService roles,
            InventoryService bag,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.elves = elves;
        this.roles = roles;
        this.bag = bag;
        this.json = json;
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS local_strive_board(role_id VARCHAR(64) PRIMARY"
                    + " KEY,payload TEXT NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS local_strive_action(role_id VARCHAR(64) NOT"
                    + " NULL,receipt VARCHAR(96) NOT NULL,PRIMARY KEY(role_id,receipt))");
    }

    /**
     * 读取原配置的数值。
     *
     * @param key 配置字段名
     * @return 原始数值
     */
    private static int rule(String key) {
        return RULES.get(key).intValue();
    }

    /**
     * 返回该角色是否满足随包训练中心开放条件。
     *
     * @param role 当前角色
     * @return 是否可进入训练中心
     */
    public boolean open(LocalRole role) {
        return role.level >= rule("minLevel");
    }

    /**
     * 创建训练中心的原错误应答。
     *
     * @param errno 原错误码
     * @return 协议结果
     */
    private ObjectNode result(int errno) {
        return json.createObjectNode().put("errno", errno);
    }

    /**
     * 从首个可见格起保持七个可见事件；转角最多连续走两个纵格。
     *
     * @param board 当前训练场存档
     * @param name 原事件表名称
     */
    private void appendGrid(ObjectNode board, String name) {
        ArrayNode line = board.withArray("line");
        int lane = board.path("tailLane").asInt(2);
        int direction = board.path("direction").asInt(3);
        if (direction == 1) lane--;
        if (direction == 2) lane++;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int onward = 3;
        if (direction == 3 && random.nextInt(3) == 0) {
            onward = lane == 1 ? 2 : lane == 3 ? 1 : random.nextBoolean() ? 1 : 2;
        }
        line.addObject()
                .put("next_toward", direction)
                .put("last_toward", direction)
                .put("grid_weight", lane)
                .put("event", 1 + random.nextInt(RULES.get(name).size()))
                .put("client_toward", onward);
        board.put("tailLane", lane).put("direction", onward);
    }

    /**
     * 创建某一训练场的初始路线。
     *
     * @param name 训练场名称
     * @param remaining 初始可用次数
     * @return 当前训练场存档
     */
    private ObjectNode newBoard(String name, int remaining) {
        ObjectNode board = json.createObjectNode().put("num", remaining).put("buy_num", 0);
        for (int i = 0; i < VISIBLE; i++) appendGrid(board, name);
        return board;
    }

    /**
     * 按北京时间恢复高级训练的两个独立计数、初级训练的恢复计时和过期伤情。
     *
     * @param role 已加锁的角色编号
     * @param now 当前秒级时间戳
     * @return 已结算时间的存档
     */
    private ObjectNode load(String role, long now) {
        List<String> records =
                jdbc.queryForList(
                        "SELECT payload FROM local_strive_board WHERE role_id=?",
                        String.class,
                        role);
        ObjectNode state;
        if (records.isEmpty()) {
            state = json.createObjectNode();
            state.set("primary", newBoard("primary", rule("normalLimit")));
            state.set("senior", newBoard("senior", rule("seniorNormalLimit")));
            state.putArray("treatment");
        } else {
            try {
                state = (ObjectNode) json.readTree(records.get(0));
            } catch (IOException e) {
                throw new IllegalStateException("训练存档损坏", e);
            }
        }
        long day = Math.floorDiv(now + 28800, 86400);
        if (state.path("day").asLong(-1) != day) {
            state.put("day", day);
            state.with("senior")
                    .put("num", rule("seniorNormalLimit"))
                    .put("special", rule("seniorSpecialLimit"))
                    .put("buy_num", 0);
        }
        ObjectNode basic = state.with("primary");
        if (basic.has("begin_recover_time")) {
            long begin = basic.get("begin_recover_time").asLong();
            long recovered = Math.max(0, (now - begin) / rule("recoverySeconds"));
            int num = (int) Math.min(rule("normalLimit"), basic.path("num").asLong() + recovered);
            basic.put("num", num);
            if (num == rule("normalLimit")) basic.remove("begin_recover_time");
            else basic.put("begin_recover_time", begin + recovered * rule("recoverySeconds"));
        }
        ArrayNode injured = state.withArray("treatment");
        for (int i = injured.size() - 1; i >= 0; i--) {
            String uid = injured.get(i).path("uid").asText();
            if (injured.get(i).path("finish").asLong() <= now
                    || !elves.findOwned(role, uid).isPresent()) injured.remove(i);
        }
        for (String name : new String[] {"primary", "senior"}) {
            ObjectNode board = state.with(name);
            if (board.has("elf_uid") && !eligible(role, board.get("elf_uid").asText()))
                board.remove("elf_uid");
        }
        return state;
    }

    /**
     * 原面板允许选择出阵队伍或助阵中的真实精灵。
     *
     * @param role 角色编号
     * @param uid 精灵实例编号
     * @return 是否可供原面板选择
     */
    private boolean eligible(String role, String uid) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM local_elf e WHERE e.role_id=? AND e.id=? AND"
                            + " ((e.slot>0 AND e.team BETWEEN 1 AND 3) OR EXISTS(SELECT 1 FROM"
                            + " local_elf_aid a WHERE a.role_id=e.role_id AND a.elf_id=e.id))",
                        Integer.class,
                        role,
                        uid)
                > 0;
    }

    /**
     * 保存本次变更后的完整路线与伤情。
     *
     * @param role 已加锁的角色编号
     * @param state 当前训练存档
     */
    private void save(String role, ObjectNode state) {
        String payload = state.toString();
        if (jdbc.update("UPDATE local_strive_board SET payload=? WHERE role_id=?", payload, role)
                == 0)
            jdbc.update(
                    "INSERT INTO local_strive_board(role_id,payload) VALUES(?,?)", role, payload);
    }

    /**
     * 只导出原面板可读取的字段。
     *
     * @param state 当前训练存档
     * @param name 仅需推送的训练场，空值为首次全量
     * @return 原训练中心推送内容
     */
    private ObjectNode panel(ObjectNode state, String name) {
        ObjectNode data = json.createObjectNode();
        for (String kind : new String[] {"primary", "senior"})
            if (name == null || kind.equals(name))
                data.set(
                        kind,
                        state.with(kind)
                                .deepCopy()
                                .retain(
                                        "num",
                                        "elf_uid",
                                        "buy_num",
                                        "special",
                                        "begin_recover_time",
                                        "line"));
        data.set("treatment", state.path("treatment").deepCopy());
        return data;
    }

    /**
     * 恢复并返回完整棋盘。
     *
     * @param role 角色编号
     * @return 两张棋盘和仍需治疗的精灵
     */
    @Transactional
    public ObjectNode snapshot(String role) {
        LocalRole player = roles.lockRole(role);
        if (!open(player)) return result(2002);
        ObjectNode state = load(role, Instant.now().getEpochSecond());
        save(role, state);
        return panel(state, null);
    }

    /**
     * 以单一角色事务完成训练或批量治疗，并防止同一请求再次结算。
     *
     * @param role 角色编号
     * @param type 请求协议编号
     * @param request 原面板参数
     * @param receipt 当前连接的请求回执
     * @return 协议应答、移动或钱包变更
     */
    @Transactional
    public ObjectNode act(String role, int type, JsonNode request, String receipt) {
        LocalRole player = roles.lockRole(role);
        if (!open(player)
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM local_strive_action WHERE role_id=? AND"
                                    + " receipt=?",
                                Integer.class,
                                role,
                                receipt)
                        > 0) return result(2002);
        long now = Instant.now().getEpochSecond();
        ObjectNode state = load(role, now);
        ObjectNode out =
                type == 5099
                        ? cure(player, state, request.path("uid_tab"))
                        : train(player, state, request, now);
        if (out.path("errno").asInt() != 0) return out;
        save(role, state);
        jdbc.update("INSERT INTO local_strive_action(role_id,receipt) VALUES(?,?)", role, receipt);
        return out;
    }

    /**
     * 收取原治疗面板的每只单价；已恢复、越权或重复的编号使整单无效。
     *
     * @param player 已加锁角色
     * @param state 当前存档
     * @param requested 原治疗列表勾选的实例
     * @return 剩余伤员或错误码
     */
    private ObjectNode cure(LocalRole player, ObjectNode state, JsonNode requested) {
        if (!requested.isArray() || requested.size() == 0) return result(2002);
        Set<String> wanted = new HashSet<>();
        for (JsonNode uid : requested)
            if (!uid.isTextual() || !wanted.add(uid.asText())) return result(2002);
        ArrayNode injured = state.withArray("treatment");
        ArrayNode remaining = json.createArrayNode();
        for (JsonNode injury : injured)
            if (!wanted.remove(injury.path("uid").asText())) remaining.add(injury);
        if (!wanted.isEmpty()) return result(2002);
        long cost = (long) requested.size() * rule("healCost");
        if (!bag.spendMaterials(player.id, 0, 0, 0, cost)) return result(2009);
        state.set("treatment", remaining);
        ObjectNode out = result(0).put("wallet", true);
        out.set("treatment", remaining);
        return out;
    }

    /**
     * 校验阵位、骰子和钱包，再根据已显示的落点结算。
     *
     * @param player 已加锁角色
     * @param state 当前存档
     * @param req 掷骰请求
     * @param now 当前秒级时间戳
     * @return 原事件结果和新的可见路线
     */
    private ObjectNode train(LocalRole player, ObjectNode state, JsonNode req, long now) {
        int mode = req.path("itype").asInt(), special = req.path("special").asInt();
        String uid = req.path("uid").asText("");
        if (!req.path("itype").canConvertToInt()
                || (req.has("special") && !req.path("special").canConvertToInt())
                || mode < 1
                || mode > 2
                || special < 0
                || special > 6
                || (mode == 2 && player.stage < rule("minSeniorStage"))
                || !eligible(player.id, uid)) return result(2002);
        for (JsonNode hurt : state.withArray("treatment"))
            if (uid.equals(hurt.path("uid").asText())) return result(2002);
        LocalElf elf = elves.findOwned(player.id, uid).orElse(null);
        if (elf == null) return result(2002);
        String name = mode == 1 ? "primary" : "senior";
        ObjectNode board = state.with(name);
        int num = board.path("num").asInt(), bought = board.path("buy_num").asInt();
        int cost = special == 0 ? 0 : RULES.get("specialCosts").get(mode - 1).asInt();
        if (mode == 1 && num < 1) return result(251605);
        if (mode == 2 && special > 0 && board.path("special").asInt() < 1) return result(251610);
        if (mode == 2 && special == 0 && num == 0) {
            JsonNode prices = RULES.get("seniorPrices");
            cost = prices.get(Math.min(bought, prices.size() - 1)).asInt();
        }
        if (cost > 0 && !bag.spendMaterials(player.id, 0, 0, 0, cost)) return result(2009);
        if (mode == 2 && special > 0) board.put("special", board.path("special").asInt() - 1);
        else if (num > 0) board.put("num", num - 1);
        else board.put("buy_num", bought + 1);
        if (mode == 1 && !board.has("begin_recover_time")) board.put("begin_recover_time", now);
        int dice = special == 0 ? ThreadLocalRandom.current().nextInt(1, 7) : special;
        ArrayNode line = board.withArray("line");
        int event = line.get(dice - 1).path("event").asInt();
        ObjectNode out = result(0).put("wallet", cost > 0).put("diceKind", special == 0 ? 1 : 2);
        ObjectNode info =
                out.putObject("info")
                        .put("event_id", event)
                        .put("elfuid", uid)
                        .put("dice", dice)
                        .put("itype", mode);
        JsonNode row = RULES.get(name).get(Integer.toString(event));
        switch (row.get(0).asInt()) {
            case 1:
            case 2:
                grow(player, elf, row, info);
                break;
            case 3:
                if (ThreadLocalRandom.current().nextInt(10000) < row.get(1).asInt()) {
                    int minutes = row.get(2).asInt();
                    long finish = now + minutes * 60L;
                    state.withArray("treatment")
                            .addObject()
                            .put("uid", uid)
                            .put("finish", finish)
                            .put("itype", mode)
                            .put("interval", minutes);
                    info.put("finish", finish);
                }
                break;
            case 4:
                board.put("num", board.path("num").asInt() + row.get(1).asInt());
                break;
            default:
                throw new IllegalStateException("未知训练事件");
        }
        board.put("elf_uid", uid);
        for (int i = 0; i < dice; i++) {
            line.remove(0);
            appendGrid(board, name);
        }
        out.set("board", panel(state, name));
        return out;
    }

    /**
     * 把属性增量或对应品质允许的上限增量写回战斗和旅行共享的培养字段。
     *
     * @param player 已加锁角色
     * @param elf 正在训练的精灵
     * @param row 原事件类别、属性和增量
     * @param info 填入实际增量的事件结果
     */
    private void grow(LocalRole player, LocalElf elf, JsonNode row, ObjectNode info) {
        boolean extra = row.get(0).asInt() == 2;
        String attr = ATTRS[row.get(1).asInt()];
        ObjectNode train = elf.training;
        ObjectNode stats = train.with(extra ? "effort_monopoly_max" : "effort");
        int quality = ElfTrainingCatalog.row("species", elf.configId).path("quality").asInt();
        int cap =
                extra
                        ? RULES.get("qualityExtra").path(Integer.toString(quality)).asInt()
                        : ElfTrainingCatalog.row("variable", 1019 + train.path("star").asInt(1))
                                        .path("data2")
                                        .asInt()
                                + ElfTrainingCatalog.row("break", train.path("veinlevel").asInt())
                                        .path("strive")
                                        .asInt()
                                + train.path("effort_monopoly_max").path(attr).asInt();
        int value = stats.path(attr).asInt();
        int delta = Math.min(row.get(2).asInt(), Math.max(0, cap - value));
        info.putObject(extra ? "efforts_max" : "efforts").put(attr, delta);
        if (delta == 0) return;
        stats.put(attr, value + delta);
        if (elves.saveTraining(player.id, elf.id, elf.configId, train) != 1)
            throw new IllegalStateException("训练精灵丢失");
    }
}
