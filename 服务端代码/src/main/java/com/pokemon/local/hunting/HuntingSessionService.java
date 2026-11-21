package com.pokemon.local.hunting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 狩猎场出场、换目标及投球结算 Service。
 * @author tongruiyuan
 * @since 2026-11-19 17:32
 */
@Service
public class HuntingSessionService {
    /** 原客户端 errno.lua 中表示已经消耗精灵球且捕捉失败的回执。 */
    public static final int CATCH_FAILED = 250205;
    private final HuntingService pools;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ElfService elves;
    private final GamePaymentService payments;
    private final ObjectMapper json;
    private final JsonNode assess;

    /**
     * 绑定角色资产与狩猎场配置。
     * @param pools 当日两场候选
     * @param roles 角色锁
     * @param jdbc 本机存档
     * @param inventory 道具及满包邮件
     * @param elves 精灵实例
     * @param payments VIP 资格
     * @param json 配置及存档编码器
     * @throws IOException 评价配置读取失败
     */
    HuntingSessionService(HuntingService pools, RoleService roles, JdbcTemplate jdbc, InventoryService inventory,
                          ElfService elves, GamePaymentService payments, ObjectMapper json) throws IOException {
        this.pools = pools;
        this.roles = roles;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.elves = elves;
        this.payments = payments;
        this.json = json;
        try (InputStream in = new ClassPathResource("hunting/assess.json").getInputStream()) {
            assess = json.readTree(in);
        }
    }

    /** 读取当前业务日的场次。
     * @param id 角色编号
     * @return 已付费的当前场次及计数
     */
    public ObjectNode session(String id) {
        return read("SELECT state_json FROM local_hunting_session WHERE role_id=? AND day_number=?", id, HuntingService.day());
    }

    /** 读取角色存档或交易回执。
     * @param sql 模块内的查询
     * @param params 查询入参
     * @return 存档对象
     */
    private ObjectNode read(String sql, Object... params) {
        List<String> rows = jdbc.queryForList(sql, String.class, params);
        try {
            return rows.isEmpty() ? json.createObjectNode() : (ObjectNode) json.readTree(rows.get(0));
        } catch (IOException invalid) {
            throw new IllegalStateException("狩猎场存档解析失败", invalid);
        }
    }

    /** 锁定角色后校验并一次结算本次动作。
     * @param id 角色编号
     * @param type 原协议编号
     * @param arg 场别或球道具编号
     * @param guide 引导标志
     * @param key 本连接的原请求编号
     * @return 原协议应答
     */
    @Transactional
    public ObjectNode act(String id, int type, long arg, long guide, String key) {
        roles.lockRole(id);
        ObjectNode reply = read("SELECT reply_json FROM local_hunting_receipt WHERE role_id=? AND receipt=?", id, key);
        if (reply.has("errno")) {
            return reply;
        }
        long day = HuntingService.day();
        ObjectNode state = read("SELECT state_json FROM local_hunting_session WHERE role_id=? AND day_number=?", id, day);
        int field = state.path("field").asInt();
        int error;
        if (type == 3103) {
            state.put("field", 0).put("cur_elf", 0).put("grid", 0).put("lucky", 0);
            error = 0;
        } else if (type == 3104) {
            error = throwBall(id, arg, state, reply);
        } else if (type == 3102 && (guide != 0 || arg < 1 || arg > 3)) {
            error = 3;
        } else if (type == 3102 && arg == 3) {
            error = 250211;
        } else if (type == 3102 && arg == 2 && payments.vip(id).level < 3) {
            error = 250209;
        } else if (type == 3102 && field != 0) {
            error = field == arg ? 0 : 250201;
        } else if (type == 3105 && field == 0) {
            error = 250208;
        } else {
            error = target(id, type, type == 3102 ? (int) arg : field, state, day);
        }
        reply.put("errno", error);
        if (jdbc.update("UPDATE local_hunting_session SET state_json=?,day_number=? WHERE role_id=?", state.toString(), day, id) == 0) {
            jdbc.update("INSERT INTO local_hunting_session(role_id,day_number,state_json) VALUES(?,?,?)", id, day, state.toString());
        }
        jdbc.update("INSERT INTO local_hunting_receipt(role_id,receipt,reply_json) VALUES(?,?,?)", id, key, reply.toString());
        return reply;
    }

    /** 付费并抽取下一目标。
     * @param id 角色编号
     * @param type 操作编号
     * @param field 普通或高级场
     * @param state 场次存档
     * @param day 动作开始时的业务日
     * @return 结算错误码
     */
    private int target(String id, int type, int field, ObjectNode state, long day) {
        int ticket = 21035 + field;
        boolean paid = inventory.count(id, ticket) > 0 ? inventory.takeOne(id, ticket)
                : type == 3105 && inventory.spendMaterials(id, Collections.emptyMap(), 0, 300);
        if (!paid) {
            return type == 3102 ? 250212 : 100006;
        }
        String countKey = "count" + field;
        long count = state.path(countKey).asLong();
        int cycle = field == 1 ? 15 : 20;
        int k = (int) (count % cycle + 1);
        int[] weights = field == 1 ? new int[]{1,40,2,30,3,30,4,30,5,30,6,25}
                : new int[]{1,75,2,50,3,50,4,40,5,40,6,25};
        int grid = k == cycle ? 6 : pools.draw(k <= cycle - 5 ? Arrays.copyOf(weights, 10) : weights);
        int sid = pools.candidatesForDay(id, day).get((field - 1) * 6 + grid - 1);
        state.put("field", field).put("grid", grid).put("cur_elf", sid).put("lucky", 0).put(countKey, count + 1);
        return 0;
    }

    /** 扣球并结算捕获和幸运。
     * @param id 角色编号
     * @param ball 球道具编号
     * @param state 当日场次
     * @param reply 原协议应答
     * @return 结算结果
     */
    private int throwBall(String id, long ball, ObjectNode state, ObjectNode reply) {
        int sid = state.path("cur_elf").asInt();
        if (sid == 0) {
            return 250210;
        }
        if (ball < 21014 || ball > 21016) {
            return 250203;
        }
        if (!inventory.takeOne(id, (int) ball)) {
            return 250204;
        }
        int rank = assess.path(Integer.toString(sid)).asInt();
        int[] thresholds = {50,100,200,300,600,1000,1600,3000,6000,10000};
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int gain = ball == 21014 && rank <= 5 ? random.nextInt(6,11)
                : ball == 21015 && rank <= 8 ? random.nextInt(40,61) : 0;
        int lucky = state.path("lucky").asInt() + gain;
        if (ball != 21016 && lucky < thresholds[rank - 1] && random.nextInt(100) >= (ball == 21014 ? 2 : 12)) {
            state.put("lucky", lucky);
            reply.put("lucky", lucky);
            return CATCH_FAILED;
        }
        if (elves.list(id).size() < inventory.elfCapacity(id)) {
            elves.grant(id, sid);
        } else {
            long now = Instant.now().getEpochSecond();
            inventory.createRewardMail(id, UUID.randomUUID().toString(), "狩猎场捕获", "精灵背包已满，请领取捕获的精灵。",
                    now, now + 30L * 86400, Arrays.asList(2, sid, 1));
        }
        reply.put("elfid", sid).put("gridid", state.path("grid").asInt());
        state.put("cur_elf", 0).put("grid", 0).put("lucky", 0);
        return 0;
    }
}
