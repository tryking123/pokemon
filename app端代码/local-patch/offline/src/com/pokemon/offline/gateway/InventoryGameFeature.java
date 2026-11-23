package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.inventory.api.entity.SystemMail;
import com.pokemon.offline.inventory.api.entity.SystemMailReward;
import com.pokemon.offline.inventory.api.vo.MailClaimResult;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.beans.factory.ObjectProvider;
import com.pokemon.offline.platform.context.annotation.Lazy;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原游戏背包、普通商城与系统邮件适配器
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
@Service
final class InventoryGameFeature implements GameFeature {
    private static final int DISCOUNT_ACTIVITY = 10007;
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    private final InventoryService inventory;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final BagSnapshot bags;
    private final ActivityService activities;
    private final GamePaymentService payments;
    private final ObjectProvider<CosmeticGameFeature> cosmetics;
    private final ObjectProvider<com.pokemon.offline.activity.PassportService> passport;
    private final ObjectProvider<com.pokemon.offline.guild.PersonalGuildService> guildInventory;
    private final Map<GameReply, String> mailConnections = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 连接游戏协议与库存、角色业务
     *
     * @param inventory 库存和邮件服务
     * @param roles 角色服务
     * @param battle 精灵背包推送
     * @param bags 普通物品及训练家装备快照
     * @param activities 限时商城活动周期
     * @param payments 携带商城的角色 VIP 限制
     * @param cosmetics 角色衣柜快照
     * @param passport 时装币余额
     * @param guildInventory 超古代石盘持有状态
     */
    InventoryGameFeature(InventoryService inventory, RoleService roles, @Lazy BattleGameFeature battle,
                         BagSnapshot bags, ActivityService activities, GamePaymentService payments, ObjectProvider<CosmeticGameFeature> cosmetics,
                         ObjectProvider<com.pokemon.offline.activity.PassportService> passport,
                         ObjectProvider<com.pokemon.offline.guild.PersonalGuildService> guildInventory) {
        this.inventory = inventory;
        this.roles = roles;
        this.battle = battle;
        this.bags = bags;
        this.activities = activities;
        this.payments = payments;
        this.cosmetics = cosmetics;
        this.passport = passport;
        this.guildInventory = guildInventory;
    }

    /**
     * 处理普通商城和系统邮件客户端消息
     *
     * @param role 已认证角色
     * @param request 游戏请求
     * @param output 协议应答及推送
     * @return 是否处理了该消息
     * @throws IOException 应答或推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 138:
                ActivityPeriod period = activities.cycle(DISCOUNT_ACTIVITY);
                boolean open = Instant.now().getEpochSecond() >= period.start;
                output.send(request, data("errno", open ? 0 : 100013, "info",
                        open ? inventory.discountShop(role.id, period.start) : Collections.emptyList()));
                return true;
            case 139:
                buyDiscount(role, request, output);
                return true;
            case 233:
                sell(role, request, output);
                return true;
            case 311:
                composeBook(role, request, output);
                return true;
            case 343:
                buyHome(role, request, output);
                return true;
            case 1000:
                mailList(role, request, output);
                return true;
            case 1001:
                claimMail(role, request, output);
                return true;
            case 1004:
                boolean opened = inventory.openMail(role.id, (String) request.body.get("mailid"));
                output.send(request, data("errno", opened ? 0 : 1));
                pushMailCount(role, output);
                return true;
            case 2037:
                buyCarry(role, request, output);
                return true;
            case 2301:
                buy(role, request, output);
                return true;
            case 3501:
                buyQuick(role, request, output);
                return true;
            default:
                return false;
        }
    }

    /**
     * 同步其他本机界面刚寄入的系统邮件。
     * @param role 当前角色
     * @param output 当前游戏连接
     * @throws IOException 角标推送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        bags.refreshMailCount(role.id, output);
    }

    /**
     * 接收原合成弹窗的目标技能书，刷新合成后的背包和钻石。
     * @param role 已认证角色
     * @param request 合成请求
     * @param output 应答及推送通道
     * @throws IOException 写入客户端失败
     */
    private void composeBook(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long id = number(request.body, "sid", 0);
        String connection = mailConnections.computeIfAbsent(output, key -> UUID.randomUUID().toString());
        int error = inventory.composeBook(role.id, id, connection + ":" + request.session);
        if (error == 0) {
            pushBag(role, output);
            LocalRole updated = roles.findRole(role.accountId, role.realmId).get();
            pushWallet(updated.coins, updated.diamonds, output);
        }
        output.send(request, data("errno", error, "sid", id));
    }

    /**
     * 恢复角色背包、商店和邮件状态
     *
     * @param role 已认证角色
     * @param output 推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushBag(role, output);
        pushMailCount(role, output);
        output.push(702, data("refresh_time", 0, "buy_info", Collections.emptyList()));
        output.push(706, data("refresh_time", 0, "buy_info", Collections.emptyList()));
        pushQuick(role.id, LocalDate.now(GAME_ZONE).toEpochDay(), output);
        pushHomeWallet(role.id, output);
        pushHomeShop(role.id, output);
    }

    /**
     * 读取有效系统邮件或返回指定邮件
     *
     * @param role 已认证角色
     * @param request 邮件查询请求
     * @param output 应答通道
     * @throws IOException 发送失败
     */
    private void mailList(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Set<String> selected = requestedIds(request);
        List<Map<String, Object>> values = new ArrayList<>();
        for (SystemMail mail : inventory.mail(role.id)) {
            if (!selected.isEmpty() && !selected.contains(mail.id)) {
                continue;
            }
            Map<String, Object> entry = data("uid", mail.id, "id", mail.id, "is_new", !mail.opened,
                    "date", mail.createdAt, "expire", mail.expiresAt, "subject", mail.subject, "body", mail.body,
                    "channelid", 0);
            if (!mail.claimed) {
                entry.put("attach", attachment(mail));
            }
            values.add(entry);
        }
        output.send(request, data("errno", 0, "list", values));
    }

    /**
     * 领取系统邮件附件并刷新角色资产
     *
     * @param role 已认证角色
     * @param request 领取请求
     * @param output 应答通道
     * @throws IOException 发送失败
     */
    private void claimMail(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String connection = mailConnections.computeIfAbsent(output, key -> UUID.randomUUID().toString());
        MailClaimResult claim = inventory.claimMail(role.id, requestedIds(request), connection + ":" + request.session);
        if (!claim.completedIds.isEmpty()) {
            bags.acknowledgeMail(output, claim.completedIds.size());
        }
        if (claim.delivered) {
            pushBag(role, output);
            if (claim.dressChipDelivered) {
                cosmetics.getIfAvailable().pushDress(role.id, output);
            }
            if (claim.elfDelivered || claim.gearDelivered) {
                battle.pushElfs(role.id, output);
            }
            if (claim.tabletDelivered) {
                output.push(466, guildInventory.getIfAvailable().researchSnapshot(role.id, 466));
                output.push(465, guildInventory.getIfAvailable().antiquities(role.id));
            }
            Optional<LocalRole> updated = roles.findRole(role.accountId, role.realmId);
            if (updated.isPresent()) {
                pushWallet(updated.get().coins, updated.get().diamonds, output);
                output.push(3, data("dojo_point", updated.get().dojoPoints));
                pushHomeWallet(role.id, output);
            }
        }
        if (claim.delivered || !claim.completedIds.isEmpty()) {
            pushMailCount(role, output);
        }
        output.send(request, data("errno", 0, "ids", claim.completedIds));
    }

    /**
     * 购买普通商城商品并返回实际成交数据
     *
     * @param role 已认证角色
     * @param request 商城请求
     * @param output 应答通道
     * @throws IOException 发送失败
     */
    private void buy(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Optional<InventoryService.Purchase> result = inventory.purchase(role.id,
                number(request.body, "index", 0), number(request.body, "num", 0));
        if (!result.isPresent()) {
            output.send(request, data("errno", 1));
            return;
        }
        InventoryService.Purchase bought = result.get();
        output.send(request, data("errno", 0, "itemid", bought.item, "itemtype", bought.type,
                "itemnum", bought.quantity));
        pushBag(role, output);
        pushWallet(bought.coins, bought.diamonds, output);
    }

    /**
     * 确认当前活动限时商城商品，刷新钱包、背包和原商城成功反馈。
     *
     * @param role 已认证角色
     * @param request 限时商城购买请求
     * @param output 应答和推送通道
     * @throws IOException 消息发送失败
     */
    private void buyDiscount(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        ActivityPeriod period = activities.cycle(DISCOUNT_ACTIVITY);
        long index = number(request.body, "idx", 0);
        Optional<InventoryService.Purchase> result = Instant.now().getEpochSecond() >= period.start
                ? inventory.purchaseDiscount(role.id, period.start, index) : Optional.empty();
        output.send(request, data("errno", result.isPresent() ? 0 : 100021, "idx", index));
        if (result.isPresent()) {
            InventoryService.Purchase bought = result.get();
            output.push(14, data("flags", "limitshop.buy", "adds", data("items",
                    Collections.singletonList(data("id", bought.item, "n", bought.quantity))), "loses", data()));
            pushBag(role, output);
            pushWallet(bought.coins, bought.diamonds, output);
        }
    }

    /**
     * 按底包定义出售一类道具，立即刷新玩家余额和背包。
     *
     * @param role 已认证角色
     * @param request 道具出售请求
     * @param output 应答和推送通道
     * @throws IOException 消息发送失败
     */
    private void sell(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long item = number(request.body, "sid", 0);
        long amount = number(request.body, "num", 0);
        Optional<InventoryService.Purchase> result = inventory.sell(role.id, item, amount);
        output.send(request, data("errno", result.isPresent() ? 0 : 100021, "sid", item, "num", amount));
        if (result.isPresent()) {
            output.push(300, data("flags", "item.sell", "del", data("items",
                    Collections.singletonList(data("id", item, "n", amount)))));
            pushBag(role, output);
            pushWallet(result.get().coins, result.get().diamonds, output);
        }
    }

    /**
     * 结算精灵球快速购买并同步当天成交数。
     *
     * @param role 已认证角色
     * @param request 快速购买请求
     * @param output 应答和推送通道
     * @throws IOException 消息发送失败
     */
    private void buyQuick(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long day = LocalDate.now(GAME_ZONE).toEpochDay();
        Optional<InventoryService.Purchase> result = inventory.purchaseQuick(role.id, day,
                number(request.body, "id", 0), number(request.body, "num", 0));
        output.send(request, data("errno", result.isPresent() ? 0 : 100021));
        if (result.isPresent()) {
            pushQuick(role.id, day, output);
            pushBag(role, output);
            pushWallet(result.get().coins, result.get().diamonds, output);
        }
    }

    /**
     * 从携带商城购买后推送实际携带实例和钱包。
     *
     * @param role 已认证角色
     * @param request 携带商城购买请求
     * @param output 应答和推送通道
     * @throws IOException 消息发送失败
     */
    private void buyCarry(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Optional<InventoryService.Purchase> result = inventory.purchaseCarry(role.id, payments.vip(role.id).level,
                number(request.body, "index", 0), number(request.body, "num", 0));
        if (!result.isPresent()) {
            output.send(request, data("errno", 100021));
            return;
        }
        InventoryService.Purchase bought = result.get();
        output.send(request, data("errno", 0, "itemid", bought.item, "itemtype", bought.type,
                "itemnum", bought.quantity));
        battle.pushElfs(role.id, output);
        pushWallet(bought.coins, bought.diamonds, output);
    }

    /**
     * 处理家园商店购买并同步家园币、商店和背包。
     *
     * @param role 已认证角色
     * @param request 家园商城购买请求
     * @param output 应答和推送通道
     * @throws IOException 消息发送失败
     */
    private void buyHome(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long index = number(request.body, "idx", 0);
        long count = number(request.body, "count", 0);
        boolean bought = inventory.purchaseHome(role.id, homeShopWeek().toEpochDay(), index, count);
        output.send(request, data("errno", bought ? 0 : 100021, "idx", index, "count", count));
        if (bought) {
            pushHomeWallet(role.id, output);
            pushHomeShop(role.id, output);
            pushBag(role, output);
        }
    }

    /**
     * 以北京时间周一作为本地家园商店刷新日。
     *
     * @return 本周周一日期
     */
    private LocalDate homeShopWeek() {
        return LocalDate.now(GAME_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 推送家园商店已购买次数以及下一次刷新时间。
     *
     * @param roleId 当前角色编号
     * @param output 推送通道
     * @throws IOException 消息发送失败
     */
    private void pushHomeShop(String roleId, GameReply output) throws IOException {
        LocalDate week = homeShopWeek();
        List<Map<String, Object>> counts = new ArrayList<>();
        inventory.homePurchases(roleId, week.toEpochDay()).forEach((item, amount) -> counts.add(
                data("index", item, "buy_num", amount)));
        output.push(819, data("nexttime", week.plusWeeks(1).atStartOfDay(GAME_ZONE).toEpochSecond(), "record", counts));
    }

    /**
     * 推送角色当前家园币余额。
     *
     * @param roleId 当前角色编号
     * @param output 推送通道
     * @throws IOException 消息发送失败
     */
    void pushHomeWallet(String roleId, GameReply output) throws IOException {
        output.push(3, data("homecoin", inventory.homeCoins(roleId), "fashescoins", passport.getIfAvailable().fashionCoins(roleId)));
    }

    /**
     * 推送快速购买的已购数量列表。
     *
     * @param roleId 角色编号
     * @param day 北京时间自然日
     * @param output 推送通道
     * @throws IOException 消息发送失败
     */
    private void pushQuick(String roleId, long day, GameReply output) throws IOException {
        List<Map<String, Object>> counts = new ArrayList<>();
        inventory.quickPurchases(roleId, day).forEach((item, amount) -> counts.add(data("id", item, "n", amount)));
        output.push(1401, data("data", counts));
    }

    /**
     * 推送角色的当前背包状态
     *
     * @param role 已认证角色
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    void pushBag(LocalRole role, GameReply output) throws IOException {
        bags.push(role.id, output);
    }

    /**
     * 按旧客户端的增量语义提示未读或仍可领取的邮件。
     * 可领取的部分邮件继续计数，使原邮箱下次打开时读取本地列表。
     *
     * @param role 已认证角色
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    void pushMailCount(LocalRole role, GameReply output) throws IOException {
        bags.pushMailCount(role.id, output);
    }

    /**
     * 推送交易后的钱包数值
     *
     * @param coins 当前金币
     * @param diamonds 当前钻石
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    private void pushWallet(long coins, long diamonds, GameReply output) throws IOException {
        output.push(3, data("coins", coins, "diamonds", diamonds, "servertime", Instant.now().getEpochSecond()));
    }

    /**
     * 读取邮件请求指定的有效编号
     *
     * @param request 邮件请求
     * @return 去重后的编号集合
     */
    private Set<String> requestedIds(SprotoProtocol.Request request) {
        Set<String> ids = new HashSet<>();
        Object list = request.body.get("ids");
        if (list instanceof List<?>) {
            for (Object id : (List<?>) list) {
                if (id instanceof String && ((String) id).length() <= 64 && ids.size() < 200) {
                    ids.add((String) id);
                }
            }
        }
        return ids;
    }

    /**
     * 生成系统邮件当前尚未领取的附件
     *
     * @param mail 系统邮件
     * @return 金币、钻石、道具及精灵附件
     */
    private Map<String, Object> attachment(SystemMail mail) {
        Map<String, Object> result = data();
        if (mail.coins > 0) result.put("coins", mail.coins);
        if (mail.diamonds > 0) result.put("diamonds", mail.diamonds);
        if (mail.dojoPoints > 0) {
            result.put("dojo_point", mail.dojoPoints);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> elves = new ArrayList<>();
        List<Map<String, Object>> elfChips = new ArrayList<>();
        List<Map<String, Object>> dressChips = new ArrayList<>();
        List<Map<String, Object>> carried = new ArrayList<>();
        List<Map<String, Object>> zstones = new ArrayList<>();
        List<Map<String, Object>> powerstones = new ArrayList<>();
        List<Map<String, Object>> stickers = new ArrayList<>();
        List<Map<String, Object>> antiquities = new ArrayList<>();
        if (mail.quantity > 0) {
            items.add(data("id", mail.itemId, "n", mail.quantity));
        }
        for (SystemMailReward reward : mail.rewards) {
            if (reward.type == SystemMailReward.ITEM) {
                // 原 parse_reward 中，普通物品一旦带 type 字段便被按图鉴物品解析。
                items.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.ANTIQUITY) {
                antiquities.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.TABLET) {
                // 本地原解析器扩展只识别明确的石盘类型，保留其原始索引。
                items.add(data("id", reward.configId, "n", reward.quantity, "type", SystemMailReward.TABLET));
            } else if (reward.type == SystemMailReward.ELF) {
                elves.add(data("sid", reward.configId, "level", 1, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.ELF_CHIP) {
                elfChips.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.CARRY) {
                carried.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.ZSTONE) {
                zstones.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.POWERSTONE) {
                powerstones.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.DRESS_CHIP) {
                dressChips.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.type == SystemMailReward.STICKER) {
                stickers.add(data("id", reward.configId, "n", reward.quantity));
            } else if (reward.currencyField() != null) {
                String field = reward.currencyField();
                result.put(field, ((Number) result.getOrDefault(field, 0)).longValue() + reward.quantity);
            }
        }
        if (!items.isEmpty()) {
            result.put("items", items);
        }
        if (!elfChips.isEmpty()) result.put("elschips", elfChips);
        if (!dressChips.isEmpty()) result.put("fasheschips", dressChips);
        if (!carried.isEmpty()) result.put("carrys", carried);
        if (!zstones.isEmpty()) result.put("zstone", zstones);
        if (!powerstones.isEmpty()) result.put("pstone", powerstones);
        if (!stickers.isEmpty()) result.put("suit_bag", stickers);
        if (!antiquities.isEmpty()) result.put("antiquity", antiquities);
        if (!elves.isEmpty()) {
            result.put("elf", elves);
        }
        return result;
    }
}
