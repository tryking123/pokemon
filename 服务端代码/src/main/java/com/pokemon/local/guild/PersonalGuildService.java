package com.pokemon.local.guild;

import com.pokemon.local.activitydaily.DailyVitalityEvents;
import com.pokemon.local.guild.api.entity.LocalGuild;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人公会、会长管理、捐献与贡献商店 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:08
 */
@Service
public class PersonalGuildService {
    public static final int OK = 0;
    public static final int UNAVAILABLE = 3;
    private static final int INSUFFICIENT = 4;
    private static final long CREATE_COINS = 100_000;
    private static final int[] LEVEL_XP = {0, 1_000, 3_000, 7_000, 15_000};
    private static final int SHOP_MIN_LEVEL = 20;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int ORDINARY_ITEM = 1;
    private static final int ELF_CHIP = 3;
    // 商品索引、类型、数量与限购对应原客户端的 contributeshop_proto。
    private static final Offer[] OFFERS = {null,
            new Offer(34029, 1, 300, 50), new Offer(21025, 1, 1_000, 3), new Offer(21061, 1, 150, 20),
            new Offer(21001, 10, 300, 10), new Offer(32001, 1, 5_000, 10), new Offer(32002, 1, 20_000, 3),
            new Offer(ELF_CHIP, 64084, 10, 15_000, 1), new Offer(ELF_CHIP, 64085, 10, 15_000, 10),
            new Offer(ELF_CHIP, 54015, 10, 20_000, 1), new Offer(ELF_CHIP, 54016, 10, 20_000, 1),
            new Offer(31010, 1, 3_500, 1), new Offer(31011, 1, 3_500, 1), new Offer(31012, 1, 3_500, 1),
            new Offer(20, 201, 1, 10_000_000, 20), new Offer(20, 202, 1, 2_000, 20),
            new Offer(20, 203, 1, 20_000, 20)};

    /** 在角色结算事务内增加专属考古库存。
     * @param state 角色考古存档
     * @param id 考古模板
     * @param count 到账数量
     */
    static void addAntiquity(ObjectNode state, int id, long count) {
        ObjectNode bag = state.with("bag");
        String key = String.valueOf(id);
        bag.put(key, Math.addExact(bag.path(key).asLong(), count));
    }

    /** 构建原客户端专属考古背包。
     * @param roleId 角色编号
     * @return S465 快照
     */
    public Map<String, Object> antiquities(String roleId) {
        ObjectNode state = archaeology.read(roleId, "archaeology");
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> bag = new ArrayList<>();
        state.path("bag").fields().forEachRemaining(entry -> {
            if (entry.getValue().asLong() > 0) {
                Map<String, Object> stack = new LinkedHashMap<>();
                stack.put("sid", Integer.valueOf(entry.getKey()));
                stack.put("num", entry.getValue().asLong());
                bag.add(stack);
            }
        });
        result.put("bag", bag);
        if (state.path("map").asInt() != 0) {
            result.put("map", 10018);
        }
        return result;
    }

    /**
     * 领取原石板邮件附件，只增加尚未持有的石板，不代替原激活与研究操作。
     * @param roleId 已认证的邮件收件角色
     * @param index 原slatebase石板编号，一至八
     * @param quantity 邮件附件数量，必须为正数
     */
    @Transactional
    public void claimTabletSupply(String roleId, int index, long quantity) {
        roles.lockRole(roleId);
        if (index < 1 || index > 8 || quantity <= 0) {
            throw new IllegalArgumentException("石板邮件附件参数不正确");
        }
        ObjectNode state = archaeology.read(roleId, "archaeology");
        String key = Integer.toString(index);
        if (!state.path("pieces").has(key)) {
            state.with("pieces").put(key, 0);
            archaeology.write(roleId, "archaeology", state);
        }
    }

    /**
     * 为当前角色终身寄送一次八种原石板，领取邮件后仍需执行原激活操作。
     * @param roleId 已认证角色
     * @param action 一表示申请研究员材料邮件
     * @return 邮件寄送状态与原界面提示
     */
    @Transactional
    public ObjectNode supplyTabletMail(String roleId, int action) {
        LocalRole role = roles.lockRole(roleId);
        ObjectNode result = new ObjectMapper().createObjectNode();
        if (action != 1) return result.put("errno", 100021).put("message", "石盘材料申请参数不正确");
        if (guilds.active(roleId) == null) return result.put("errno", 251300).put("message", "请先加入公会");
        if (role.level < 50 || role.sceneId != 10018)
            return result.put("errno", 100021).put("message", "请达到50级后在公会研究员处领取");
        String key = "tablet-supply:v1";
        ObjectNode receipt = archaeology.read(roleId, key);
        String mailId = "guild-tablets:" + roleId;
        if (!receipt.has("mailId")) {
            JsonNode rewards = personalRules.path("tabletSupply").path("rewards");
            if (rewards.size() != 8) throw new IllegalStateException("石盘供给必须包含八种原石板");
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (JsonNode reward : rewards) {
                int index = reward.path("id").asInt();
                if (reward.path("type").asInt() != 888 || index < 1 || index > 8
                        || reward.path("quantity").asInt() != 1 || !ids.add(index))
                    throw new IllegalStateException("石盘供给附件配置不正确");
            }
            if (!tabletMail.exists(roleId, mailId)) tabletMail.create(roleId, mailId, Instant.now().getEpochSecond(), rewards);
            archaeology.write(roleId, key, receipt.put("mailId", mailId));
        }
        return result.put("errno", 0).put("message", "已发送，请到邮箱领取后解锁超古代石盘");
    }

    /**
     * 使用原版藏宝图或探测器，并按角色事务保存地图、消耗和完整结算回执。
     * @param roleId 当前角色
     * @param mapRequest 是否使用藏宝图
     * @param item 考古背包模板
     * @param receipt 当前原协议回执编号
     * @return 包含提示距离、奖励与状态码的结算
     */
    @Transactional
    public ObjectNode explore(String roleId, boolean mapRequest, long item, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        String key = "arch:" + receipt;
        ObjectNode reply = archaeology.read(roleId, key);
        if (reply.has("errno")) {
            if (reply.path("itemid").asLong() != item) {
                return reply.removeAll().put("errno", UNAVAILABLE);
            }
            return reply.put("replayed", true);
        }
        reply.put("errno", UNAVAILABLE).put("itemid", item);
        if (guilds.active(roleId) == null || (mapRequest ? item < 101 || item > 102 : item < 201 || item > 203)) {
            return reply;
        }
        ObjectNode state = archaeology.read(roleId, "archaeology");
        if (state.path("bag").path(String.valueOf(item)).asLong() <= 0) {
            return reply;
        }
        int map = state.path("map").asInt();
        if (mapRequest) {
            if (map != 0) {
                return reply;
            }
            boolean entrance = ThreadLocalRandom.current().nextBoolean();
            state.put("map", item).put("tileX", entrance ? 21 : 9).put("tileY", entrance ? 8 : 10);
            addAntiquity(state, (int) item, -1);
            reply.put("errno", OK).put("mapid", 10018);
        } else {
            if ((map != 101 && map != 102) || role.sceneId != 10018) {
                return reply;
            }
            int dx = Math.abs(role.x / 32 - state.path("tileX").asInt());
            int dy = Math.abs(role.y / 32 - state.path("tileY").asInt());
            if (Math.max(dx, dy) > 1) {
                reply.put("errno", 192).put("length", dx + dy);
                archaeology.write(roleId, key, reply);
                return reply;
            }
            state.remove("map");
            JsonNode tool = mining.path(String.valueOf(item));
            int roll = ThreadLocalRandom.current().nextInt(1000);
            if (roll < tool.get(0).asInt()) {
                reply.put("errno", 193);
            } else {
                int quantity = roll < tool.get(0).asInt() + tool.get(1).asInt() ? 2 : 1;
                int reward = drawTreasure(map);
                addAntiquity(state, (int) item, -1);
                addAntiquity(state, reward, quantity);
                reply.put("errno", OK).put("reward", reward).put("quantity", quantity);
            }
        }
        archaeology.write(roleId, "archaeology", state);
        archaeology.write(roleId, key, reply);
        return reply;
    }

    /** 按当前地图的原表权重抽取一个考古模板。
     * @param map 藏宝图编号
     * @return 抽中的物品模板
     */
    private int drawTreasure(int map) {
        JsonNode pool = mining.path(String.valueOf(map));
        int total = 0;
        for (JsonNode drop : pool) {
            total += drop.get(1).asInt();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (JsonNode drop : pool) {
            roll -= drop.get(1).asInt();
            if (roll < 0) {
                return drop.get(0).asInt();
            }
        }
        throw new IllegalStateException("考古掉落权重异常");
    }

    /** 返回自行车梦境徽章六个部件的共同等级。
     * @param roleId 当前角色
     * @return 已达到的套装等级
     */
    public int raceSuitLevel(String roleId) {
        JsonNode badge=archaeology.read(roleId,"archaeology").path("badge");
        int level=Integer.MAX_VALUE;
        for(int i=59;i<=64;i++) level=Math.min(level,badge.path(String.valueOf(i)).asInt());
        return level;
    }

    /** 原徽章套装 7001 的最低部件等级。
     * @param roleId 角色编号
     * @return 四枚部件当前共同达到的等级
     */
    public int stoneLeagueSuitLevel(String roleId) {
        JsonNode badge = archaeology.read(roleId,"archaeology").path("badge");
        int level = Integer.MAX_VALUE;
        for (int index = 55; index <= 58; index++) {
            level = Math.min(level, badge.path(String.valueOf(index)).asInt());
        }
        return level;
    }

    /**
     * 汇总研究石板、徽章或化石的上线信息。
     * @param roleId 角色编号
     * @param protocol 原推送编号
     * @return 对应原协议字段
     */
    public Map<String, Object> researchSnapshot(String roleId, int protocol) {
        ObjectNode state = archaeology.read(roleId, "archaeology");
        Map<String, Object> body = new LinkedHashMap<>();
        if (protocol == 466) {
            body.put("list", levels(state.path("pieces"), "flag"));
            body.put("flag", completeTablet(state) ? 1 : 0);
            body.put("level", state.path("tabletLevel").asInt());
            body.put("tag", state.path("guide").asInt());
        } else if (protocol == 468) {
            body.put("list", levels(state.path("badge"), "level"));
        } else {
            body.put("list", levels(state.path("fossil"), "level"));
            body.put("suit", levels(state.path("fossilsuit"), "level"));
        }
        return body;
    }

    /** 将持久化模板等级还原成原客户端索引表。
     * @param entries 原记录
     * @param valueName 等级或激活标记字段
     * @return 已持有的记录
     */
    private List<Map<String, Object>> levels(JsonNode entries, String valueName) {
        List<Map<String, Object>> result = new ArrayList<>();
        entries.fields().forEachRemaining(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", Integer.parseInt(entry.getKey()));
            row.put(valueName, entry.getValue().asInt());
            result.add(row);
        });
        return result;
    }

    /** 核对原石板的八个位置已全部点亮。
     * @param state 考古存档
     * @return 全部激活时为真
     */
    private boolean completeTablet(ObjectNode state) {
        for (int i = 1; i <= 8; i++) {
            if (state.path("pieces").path(String.valueOf(i)).asInt() != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 处理已获得石板碎片的激活和公会考古研究升级。
     * @param roleId 当前角色编号
     * @param command 原请求编号
     * @param index 目标石板、徽章、化石或套装索引
     * @param receipt 原请求回执
     * @return 原客户端错误码
     */
    @Transactional
    public int study(String roleId, int command, long index, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        ObjectNode prior = archaeology.read(roleId, "study:" + receipt);
        if (prior.has("index")) {
            return prior.path("index").asLong() == index ? OK : UNAVAILABLE;
        }
        if (role.level < 50 || guilds.active(roleId) == null || index < 0 || index > 64) {
            return UNAVAILABLE;
        }
        ObjectNode state = archaeology.read(roleId, "archaeology");
        String key = String.valueOf(index);
        switch (command) {
            case 404:
                if (state.path("pieces").path(key).asInt(-1) != 0) {
                    return UNAVAILABLE;
                }
                state.with("pieces").put(key, 1);
                state.put("guide", 1);
                break;
            case 405:
                int level = state.path("tabletLevel").asInt() + 1;
                long cost = mining.path("slate").path(String.valueOf(level)).asLong();
                if (cost <= 0 || !completeTablet(state) || contribution(roleId) < cost) {
                    return UNAVAILABLE;
                }
                state.put("tabletLevel", level).put("researchSpent", state.path("researchSpent").asLong() + cost);
                break;
            case 406:
            case 409:
                String kind = command == 406 ? "badge" : "fossil";
                if (command == 406 && !completeTablet(state)) {
                    return UNAVAILABLE;
                }
                int next = state.path(kind).path(key).asInt() + 1;
                JsonNode material = mining.path(kind).path(String.valueOf(index * 100 + next));
                if (!material.isArray()) {
                    return UNAVAILABLE;
                }
                int id = material.get(0).asInt();
                int quantity = material.get(1).asInt();
                if (state.path("bag").path(String.valueOf(id)).asLong() < quantity
                        || !inventory.spendMaterials(roleId, Collections.emptyMap(), material.get(2).asLong(), 0)) {
                    return UNAVAILABLE;
                }
                addAntiquity(state, id, -quantity);
                state.with(kind).put(key, next);
                break;
            case 410:
                int nextSuit = state.path("fossilsuit").path(key).asInt() + 1;
                JsonNode conditions = mining.path("fossilsuit").path(String.valueOf(index * 10000 + nextSuit));
                if (!conditions.isArray() || conditions.size() == 0) {
                    return UNAVAILABLE;
                }
                for (JsonNode row : conditions) {
                    int encoded = row.asInt();
                    if (state.path("fossil").path(String.valueOf(encoded / 100)).asInt() < encoded % 100) {
                        return UNAVAILABLE;
                    }
                }
                state.with("fossilsuit").put(key, nextSuit);
                break;
            default:
                return UNAVAILABLE;
        }
        archaeology.write(roleId, "archaeology", state);
        archaeology.write(roleId, "study:" + receipt, prior.put("index", index));
        return OK;
    }

    /**
     * 判断道具是否是原配置的考古自选礼包。
     * @param item 礼包编号
     * @return 是否含有完整的原考古选项
     */
    public boolean antiquityGift(long item) {
        return mining.path("gifts").has(String.valueOf(item));
    }

    /**
     * 消耗背包中的自选礼包并将所选实物写入考古专属背包。
     * @param roleId 当前角色
     * @param item 礼包编号
     * @param option 原奖励编号
     * @param quantity 使用数
     * @param receipt 原协议回执
     * @return 实际发放结果
     */
    @Transactional
    public ObjectNode openAntiquityGift(String roleId, long item, long option, long quantity, String receipt) {
        roles.lockRole(roleId);
        String key = "archgift:" + receipt;
        ObjectNode result = archaeology.read(roleId, key);
        if (result.has("errno")) {
            if (result.path("item").asLong() != item || result.path("option").asLong() != option
                    || result.path("count").asLong() != quantity) {
                return result.removeAll().put("errno", 100021);
            }
            return result.put("replayed", true);
        }
        JsonNode selection = mining.path("gifts").path(String.valueOf(item)).path(String.valueOf(option));
        if (!selection.isArray() || quantity < 1 || quantity > Integer.MAX_VALUE) {
            return result.put("errno", 100021);
        }
        if (!inventory.spendMaterials(roleId, (int) item, quantity, 0, 0)) {
            return result.put("errno", 100051);
        }
        int reward = selection.get(0).asInt();
        long total = quantity * selection.get(1).asLong();
        ObjectNode state = archaeology.read(roleId, "archaeology");
        addAntiquity(state, reward, total);
        archaeology.write(roleId, "archaeology", state);
        result.put("errno", OK).put("reward", reward).put("quantity", total).put("item", item)
                .put("option", option).put("count", quantity);
        archaeology.write(roleId, key, result);
        return result;
    }

    /**
     * 从原徽章套装的材料中按组抽取每日首领奖励。
     * @return 原材料编号
     */
    int drawBadgeMaterial() {
        JsonNode groups = mining.path("badgeGroups");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        JsonNode members = groups.get(random.nextInt(groups.size()));
        return members.get(random.nextInt(members.size())).asInt();
    }

    private final PersonalGuildMapper guilds;
    private final RoleService roles;
    private final GuildEncounterMapper archaeology;
    private final JsonNode mining;
    private final JsonNode personalRules;
    private final GuildTabletMailMapper tabletMail;
    private final InventoryService inventory;
    private final DailyVitalityEvents vitality;

    /**
     * 注入公会存档、货币和活跃事件。
     *
     * @param guilds 公会存档
     * @param roles 当前角色存档及角色事务锁
     * @param inventory 角色金币与奖励背包
     * @param vitality 日活任务事件
     * @param archaeology 考古存档
     * @param tabletMail 石板补给邮件存档
     */
    public PersonalGuildService(PersonalGuildMapper guilds, RoleService roles, InventoryService inventory,
                                DailyVitalityEvents vitality, GuildEncounterMapper archaeology,
                                GuildTabletMailMapper tabletMail) {
        this.tabletMail = tabletMail;
        this.archaeology = archaeology;
        try (InputStream source = new ClassPathResource("guild/archaeology.json").getInputStream()) {
            this.mining = new ObjectMapper().readTree(source);
        } catch (IOException error) {
            throw new IllegalStateException("读取考古配置失败", error);
        }
        try (InputStream source = new ClassPathResource("guild/personal-rules.json").getInputStream()) {
            this.personalRules = new ObjectMapper().readTree(source);
        } catch (IOException error) {
            throw new IllegalStateException("读取公会建设与成长配置失败", error);
        }
        this.guilds = guilds;
        this.roles = roles;
        this.inventory = inventory;
        this.vitality = vitality;
    }

    /**
     * 查询当前角色在本地的有效公会。
     *
     * @param roleId 角色主键
     * @return 活动公会，无公会时为空
     */
    public LocalGuild active(String roleId) {
        return guilds.active(roleId);
    }

    /**
     * 扣除创建费用并保存唯一有效个人公会。
     *
     * @param roleId 角色主键
     * @param name 经过协议解析的公会名称
     * @param intro 经过协议解析的公会介绍
     * @param receipt 连接内的请求回执
     * @return 原版协议错误码
     */
    @Transactional
    public int create(String roleId, String name, String intro, String receipt) {
        roles.lockRole(roleId);
        if (guilds.hasAction(roleId, receipt)) {
            return OK;
        }
        if (!valid(name, 2, 16) || !valid(intro, 0, 80) || guilds.active(roleId) != null) {
            return UNAVAILABLE;
        }
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), CREATE_COINS, 0)) {
            return INSUFFICIENT;
        }
        long now = Instant.now().getEpochSecond();
        String id = UUID.randomUUID().toString();
        guilds.create(id, roleId, name.trim(), intro.trim(), now);
        LocalGuild created = guilds.active(roleId);
        vitalityState(created);
        levelState(created);
        guilds.action(roleId, receipt, id, "CREATE", now);
        return OK;
    }

    /**
     * 离开个人公会并结束这一期存档的会长任期。
     *
     * @param roleId 角色主键
     * @param receipt 连接内的请求回执
     * @return 原版协议错误码
     */
    @Transactional
    public int leave(String roleId, String receipt) {
        roles.lockRole(roleId);
        if (guilds.hasAction(roleId, receipt)) {
            return OK;
        }
        LocalGuild guild = guilds.active(roleId);
        if (guild == null) {
            return UNAVAILABLE;
        }
        long now = Instant.now().getEpochSecond();
        guilds.close(guild.id, now);
        guilds.action(roleId, receipt, guild.id, "LEAVE", now);
        return OK;
    }

    /**
     * 让会长保存自己的公会说明。
     *
     * @param roleId 角色主键
     * @param content 新公告或简介
     * @param intro 是否修改简介
     * @param receipt 连接内的请求回执
     * @return 原版协议错误码
     */
    @Transactional
    public int changeText(String roleId, String content, boolean intro, String receipt) {
        roles.lockRole(roleId);
        if (guilds.hasAction(roleId, receipt)) {
            return OK;
        }
        LocalGuild guild = guilds.active(roleId);
        if (guild == null || !valid(content, 0, 80)) {
            return UNAVAILABLE;
        }
        guilds.updateText(guild.id, content.trim(), intro);
        guilds.action(roleId, receipt, guild.id, intro ? "INTRO" : "NOTICE", Instant.now().getEpochSecond());
        return OK;
    }

    /**
     * 保存会长选择的审批偏好。
     *
     * @param roleId 角色主键
     * @param automatic 是否自动审批
     * @param receipt 连接内的请求回执
     * @return 原版协议错误码
     */
    @Transactional
    public int changeAudit(String roleId, boolean automatic, String receipt) {
        roles.lockRole(roleId);
        if (guilds.hasAction(roleId, receipt)) {
            return OK;
        }
        LocalGuild guild = guilds.active(roleId);
        if (guild == null) {
            return UNAVAILABLE;
        }
        guilds.audit(guild.id, automatic);
        guilds.action(roleId, receipt, guild.id, "AUDIT", Instant.now().getEpochSecond());
        return OK;
    }

    /**
     * 按初级或高级建设费用增加经验、真实公会活跃和贡献，不限制建设次数。
     *
     * @param roleId 角色主键
     * @param currency 原客户端选择的捐献货币
     * @param receipt 连接内的请求回执
     * @return 原版协议错误码
     */
    @Transactional
    public int donate(String roleId, String currency, String receipt) {
        roles.lockRole(roleId);
        if (guilds.hasAction(roleId, receipt)) {
            return OK;
        }
        LocalGuild guild = guilds.active(roleId);
        Instant now = Instant.now();
        long day = now.atZone(BUSINESS_ZONE).toLocalDate().toEpochDay();
        if (guild == null) return 251300;
        JsonNode rule = personalRules.path("construction").path(currency == null ? "" : currency);
        if (rule.isMissingNode() || !("coins".equals(currency) || "diamonds".equals(currency))) return 100021;
        vitalityState(guild);
        levelState(guild);
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), rule.path("costCoins").asLong(),
                rule.path("costDiamonds").asLong())) {
            return "coins".equals(currency) ? 100005 : 100006;
        }
        guilds.donate(roleId, guild.id, day, receipt, now.getEpochSecond(), rule.path("xp").asInt(),
                rule.path("contribution").asInt(), currency, rule.path("vitality").asInt());
        guilds.action(roleId, receipt, guild.id, "DONATE", now.getEpochSecond());
        vitality.record(roleId, 6, receipt, 1);
        return OK;
    }

    /**
     * 查询角色当天已捐献的次数。
     *
     * @param roleId 角色主键
     * @return 按北京时间计算的当天捐献次数
     */
    public int donationsToday(String roleId) {
        return guilds.donations(roleId, LocalDate.now(BUSINESS_ZONE).toEpochDay());
    }

    /**
     * 查询当天指定建设货币的实际操作数，仅供历史与原协议状态展示。
     * @param roleId 角色编号
     * @param currency 建设货币类型
     * @return 当天真实建设次数
     */
    public int donationsToday(String roleId, String currency) {
        return guilds.donations(roleId, LocalDate.now(BUSINESS_ZONE).toEpochDay(), currency);
    }

    /**
     * 获取下一次公会捐献次数重置时间。
     *
     * @return 北京时间次日午夜对应的 UTC 秒
     */
    public long donationReset() {
        return LocalDate.now(BUSINESS_ZONE).plusDays(1).atStartOfDay(BUSINESS_ZONE).toEpochSecond();
    }

    /**
     * 汇总角色获得且仍未使用的贡献。
     *
     * @param roleId 角色主键
     * @return 当前可用公会贡献
     */
    public long contribution(String roleId) {
        return guilds.balance(roleId) - archaeology.read(roleId, "archaeology").path("researchSpent").asLong();
    }

    /**
     * 汇总角色历次获得的公会贡献。
     *
     * @param roleId 角色主键
     * @return 历史公会贡献
     */
    public long totalContribution(String roleId) {
        return guilds.earned(roleId);
    }

    /**
     * 按既有累计公会活跃来源减去已结算副本开启费用，保留建设经验和等级。
     * @param guild 当前有效公会
     * @return 尚可用于开启副本的活跃余额
     */
    @Transactional
    public long guildVitality(LocalGuild guild) {
        roles.lockRole(guild.roleId);
        return availableVitality(guild, vitalityState(guild));
    }

    /**
     * 在角色事务内结算原公会活跃费用，相同副本日回执只结算一次。
     * @param roleId 会长角色编号
     * @param guild 当前公会
     * @param amount 原配置的活跃消耗
     * @param receipt 副本和日期组成的业务回执
     * @return 活跃足够并已完成本次结算时为真
     */
    @Transactional
    public boolean spendGuildVitality(String roleId, LocalGuild guild, int amount, String receipt) {
        roles.lockRole(roleId);
        LocalGuild current = guilds.active(roleId);
        if (current == null || !current.id.equals(guild.id) || amount <= 0) return false;
        String key = "guild-vitality:" + guild.id;
        ObjectNode state = vitalityState(current);
        if (state.path("receipts").has(receipt)) return true;
        if (availableVitality(current, state) < amount) return false;
        state.put("spent", Math.addExact(state.path("spent").asLong(), amount));
        state.with("receipts").put(receipt, amount);
        archaeology.write(roleId, key, state);
        return true;
    }

    /**
     * 一次保留升级前可见活跃余额，此后的建设仅按真实活跃产出累计。
     * @param guild 当前公会
     * @return 已冻结旧余额与新建设基线的账本
     */
    private ObjectNode vitalityState(LocalGuild guild) {
        String key = "guild-vitality:" + guild.id;
        ObjectNode state = archaeology.read(guild.roleId, key);
        if (!state.has("legacyEarned")) {
            state.put("legacyEarned", guild.xp).put("donationBaseline", guilds.donatedVitality(guild.id));
            archaeology.write(guild.roleId, key, state);
        }
        return state;
    }

    /**
     * 计算旧余额与实际新建设产出扣除副本费用后的活跃。
     * @param guild 当前公会
     * @param state 已迁移的活跃账本
     * @return 非负公会活跃余额
     */
    private long availableVitality(LocalGuild guild, ObjectNode state) {
        return Math.max(0, state.path("legacyEarned").asLong()
                + guilds.donatedVitality(guild.id) - state.path("donationBaseline").asLong()
                - state.path("spent").asLong());
    }

    /**
     * 一次将旧五级曲线的等级及已赚进度映射到原十五级曲线。
     * @param guild 当前公会
     * @return 仅用于显示等级的经验偏移账本
     */
    private ObjectNode levelState(LocalGuild guild) {
        String key = "guild-level:" + guild.id;
        ObjectNode state = archaeology.read(guild.roleId, key);
        if (!state.has("offset")) {
            int legacy = LEVEL_XP.length;
            while (legacy > 1 && guild.xp < LEVEL_XP[legacy - 1]) legacy--;
            long base = personalRules.path("guildGrowth").path(Integer.toString(legacy)).path("cumulativeBase").asLong();
            state.put("offset", base - LEVEL_XP[legacy - 1]);
            archaeology.write(guild.roleId, key, state);
        }
        return state;
    }

    /**
     * 查询当前公会的最近操作。
     *
     * @param guild 当前角色的公会
     * @return 操作流水和时间
     */
    public List<Map<String, Object>> history(LocalGuild guild) {
        return guilds.history(guild.id);
    }

    /**
     * 根据累计经验计算公会等级。
     *
     * @param guild 当前公会
     * @return 由一级起的当前等级
     */
    public int level(LocalGuild guild) {
        long experience = guild.xp + levelState(guild).path("offset").asLong();
        int level = personalRules.path("guildGrowth").size();
        while (level > 1 && experience < personalRules.path("guildGrowth").path(Integer.toString(level))
                .path("cumulativeBase").asLong()) level--;
        return level;
    }

    /**
     * 将累计经验换算为当前级已获建设经验。
     *
     * @param guild 当前公会
     * @return 当前级的建设经验
     */
    public long levelProgress(LocalGuild guild) {
        int level = level(guild);
        return level == personalRules.path("guildGrowth").size() ? 0
                : guild.xp + levelState(guild).path("offset").asLong()
                    - personalRules.path("guildGrowth").path(Integer.toString(level)).path("cumulativeBase").asLong();
    }

    /**
     * 核验交易资格并按原版价签扣除实际公会贡献。
     *
     * @param roleId 当前角色主键
     * @param offerId 原客户端给出的货架索引
     * @param quantity 请求购买的份数
     * @param receipt 连接内的请求回执
     * @return 原客户端需要的商品和结算结果
     */
    @Transactional
    public Map<String, Object> buy(String roleId, long offerId, long quantity, String receipt) {
        if (offerId <= 0 || offerId >= OFFERS.length || quantity <= 0 || quantity > Integer.MAX_VALUE) {
            return Collections.singletonMap("errno", 100021);
        }
        int index = (int) offerId;
        Offer offer = OFFERS[index];
        if (offer == null) {
            return Collections.singletonMap("errno", 100021);
        }
        LocalRole role = roles.lockRole(roleId);
        int[] recorded = guilds.purchaseReceipt(roleId, receipt);
        if (recorded != null) {
            return recorded[0] == index && recorded[1] == quantity
                    ? offer.response(recorded[1]) : Collections.singletonMap("errno", 100021);
        }
        if (role.level < SHOP_MIN_LEVEL) {
            return Collections.singletonMap("errno", 100048);
        }
        if (guilds.active(roleId) == null) {
            return Collections.singletonMap("errno", 251300);
        }
        long period = shopWeek().toEpochDay();
        int bought = guilds.bought(roleId, period).stream().filter(row -> (int) row.get("index") == index)
                .mapToInt(row -> (int) row.get("buy_num")).findFirst().orElse(0);
        if (quantity > offer.limit - bought) {
            return Collections.singletonMap("errno", 250006);
        }
        long price = quantity * offer.price;
        long contributionPrice = index == 14 || index == 16 ? 0 : price;
        if (contribution(roleId) < contributionPrice) {
            return Collections.singletonMap("errno", 250008);
        }
        long rewardQuantity = quantity * offer.count;
        boolean elfChip = offer.itemType == ELF_CHIP;
        if (elfChip && !inventory.canGrantChip(roleId, offer.item, rewardQuantity)) {
            return Collections.singletonMap("errno", 100015);
        }
        if (contributionPrice == 0 && !inventory.spendMaterials(roleId, Collections.emptyMap(),
                index == 14 ? price : 0, index == 16 ? price : 0)) {
            return Collections.singletonMap("errno", index == 14 ? 100005 : 100006);
        }
        guilds.purchase(roleId, receipt, period, index, (int) quantity, contributionPrice);
        if (offer.itemType == 20) {
            ObjectNode state = archaeology.read(roleId, "archaeology");
            addAntiquity(state, offer.item, rewardQuantity);
            archaeology.write(roleId, "archaeology", state);
            return offer.response((int) quantity);
        }
        if (elfChip) {
            inventory.grantChip(roleId, offer.item, rewardQuantity);
        } else {
            inventory.grantItemsOrMail(roleId, "guild-shop:" + receipt,
                    Collections.singletonMap(offer.item, rewardQuantity));
        }
        return offer.response((int) quantity);
    }

    /**
     * 查询原版商店使用的限购与刷新快照。
     *
     * @param roleId 当前角色主键
     * @return 下次重置的 UTC 秒和当前批次已购数量
     */
    public Map<String, Object> shop(String roleId) {
        LocalDate week = shopWeek();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refresh_time", week.plusWeeks(1).atStartOfDay(BUSINESS_ZONE).toEpochSecond());
        result.put("buy_info", guilds.bought(roleId, week.toEpochDay()));
        return result;
    }

    /**
     * 找到当前北京时间所在的周起点。
     *
     * @return 周一日期
     */
    private LocalDate shopWeek() {
        return LocalDate.now(BUSINESS_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 校验原客户端提交的用户文本。
     *
     * @param text 待保存的文本
     * @param min 最少字符数
     * @param max 最多字符数
     * @return 可作为单行公会文本保存时为真
     */
    private boolean valid(String text, int min, int max) {
        if (text == null || text.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        String trimmed = text.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= min && length <= max;
    }

    /** 原客户端固定货架的商品类型、数量、贡献价格与限购。 */
    private static final class Offer {
        private final int itemType;
        private final int item;
        private final int count;
        private final int price;
        private final int limit;

        /**
         * 保存已核验的原版普通道具商品信息。
         *
         * @param item 商品道具模板编号
         * @param count 每份道具数量
         * @param price 每份支付的公会贡献
         * @param limit 每周期的限购份数
         */
        private Offer(int item, int count, int price, int limit) {
            this(ORDINARY_ITEM, item, count, price, limit);
        }

        /**
         * 保存已核验的原版商品及其奖励类型。
         *
         * @param itemType 原客户端的商品类型
         * @param item 商品道具或精灵模板编号
         * @param count 每份奖励数量
         * @param price 每份支付的公会贡献
         * @param limit 每周期的限购份数
         */
        private Offer(int itemType, int item, int count, int price, int limit) {
            this.itemType = itemType;
            this.item = item;
            this.count = count;
            this.price = price;
            this.limit = limit;
        }

        /**
         * 构建符合原客户端商店的成交结果。
         *
         * @param bundles 本次成交份数
         * @return 公会商店协议响应体
         */
        private Map<String, Object> response(int bundles) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errno", OK);
            result.put("itemid", item);
            result.put("itemtype", itemType);
            result.put("itemnum", (long) count * bundles);
            return result;
        }
    }
}
