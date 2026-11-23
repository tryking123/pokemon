package com.pokemon.offline.inventory;

import com.pokemon.offline.inventory.api.entity.InventoryItem;
import com.pokemon.offline.inventory.api.entity.SystemMail;
import com.pokemon.offline.inventory.api.entity.SystemMailReward;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.core.RowMapper;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 角色库存、钱包与系统邮件 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:37
 */
@Repository
class InventoryMapper {
    private static final RowMapper<InventoryItem> ITEM = (row, index) ->
            new InventoryItem(row.getInt("item_id"), row.getLong("quantity"), row.getInt("position"));
    /** 把道馆积分附件加入当前收件人钱包。
     * @param roleId 收件角色
     * @param points 实际领取积分
     * @return 更新行数
     */
    int addDojoPoints(String roleId, long points) {
        return jdbc.update("UPDATE local_game_role SET dojo_points=dojo_points+? WHERE id=?", points, roleId);
    }

    private final JdbcTemplate jdbc;
    private final DiamondSpendMapper diamondSpend;

    /**
     * 初始化库存数据库访问
     *
     * @param jdbc 项目的 JDBC 模板
     * @param diamondSpend 钻石消费流水
     */
    InventoryMapper(JdbcTemplate jdbc, DiamondSpendMapper diamondSpend) {
        this.jdbc = jdbc;
        this.diamondSpend = diamondSpend;
    }

    /**
     * 锁定角色资产和库存参与同一事务
     *
     * @param roleId 角色编号
     * @return 当前金币和钻石
     */
    long[] lockWallet(String roleId) {
        return jdbc.queryForObject("SELECT coins, diamonds FROM local_game_role WHERE id = ? FOR UPDATE",
                (row, index) -> new long[]{row.getLong("coins"), row.getLong("diamonds")}, roleId);
    }

    /**
     * 统计普通背包中的普通堆叠及尚未穿戴的训练家装备。
     *
     * @param roleId 角色编号
     * @return 占用格数
     */
    int ordinarySlots(String roleId) {
        Integer slots = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM local_inventory_item WHERE role_id=?)"
                        + " + (SELECT COUNT(*) FROM local_adventure_equipment_instance i WHERE i.role_id=?"
                        + " AND NOT EXISTS (SELECT 1 FROM local_trainer_equipped e WHERE e.equipment_id=i.id)) + (SELECT COUNT(*) FROM local_secret_stone WHERE role_id=? AND elf_id IS NULL) + (SELECT COUNT(*) FROM local_trainer_sticker WHERE role_id=?)",
                Integer.class, roleId, roleId, roleId, roleId);
        return slots == null ? 0 : slots;
    }

    /**
     * 按秘境掉落的存档格式创建一件未穿戴、零星的精灵装备。
     *
     * @param roleId 收件角色编号
     * @param instanceId 新装备的唯一编号
     * @param templateId 精灵装备模板编号
     * @return 写入的实例数
     */
    int insertZstone(String roleId, String instanceId, int templateId) {
        return jdbc.update("INSERT INTO local_secret_stone(sid,role_id,template_id) VALUES(?,?,?)",
                instanceId, roleId, templateId);
    }

    /**
     * 设置事务内已校验的钱包余额
     *
     * @param roleId 角色编号
     * @param coins 金币余额
     * @param diamonds 钻石余额
     */
    void updateWallet(String roleId, long coins, long diamonds) {
        long current = jdbc.queryForObject("SELECT coins FROM local_game_role WHERE id=?", Long.class, roleId);
        jdbc.update("UPDATE local_game_role SET coins = ?, diamonds = ? WHERE id = ?", coins, diamonds, roleId);
        if (current > coins) com.pokemon.offline.activity.PassportProgress.record(jdbc, roleId, 48, current - coins);
    }

    /**
     * 设置结算后的钱包余额并保留领取返还前的实际钻石支出。
     *
     * @param roleId 角色编号
     * @param coins 金币余额
     * @param diamonds 钻石余额
     * @param diamondCost 本次实际扣除的钻石数
     */
    void settleWallet(String roleId, long coins, long diamonds, long diamondCost) {
        updateWallet(roleId, coins, diamonds);
        diamondSpend.record(roleId, diamondCost);
    }

    /**
     * 查询角色在指定商城周期内的各商品购买次数。
     *
     * @param roleId 角色编号
     * @param shop 商城类型
     * @param period 周期起始时间
     * @return 商品档位及已购次数
     */
    Map<Integer, Long> purchases(String roleId, int shop, long period) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT product_id, quantity FROM local_shop_purchase WHERE role_id=? AND shop=? AND period_start=?",
                row -> { result.put(row.getInt("product_id"), row.getLong("quantity")); }, roleId, shop, period);
        return result;
    }

    /**
     * 保存单周期内的成交数量，调用方已持有角色锁。
     *
     * @param roleId 角色编号
     * @param shop 商城类型
     * @param period 周期起始时间
     * @param product 商品档位
     * @param previous 结算前的购买数
     * @param amount 本次购买数
     */
    void addPurchase(String roleId, int shop, long period, int product, long previous, long amount) {
        if (previous == 0) {
            jdbc.update("INSERT INTO local_shop_purchase(role_id,shop,period_start,product_id,quantity) VALUES (?,?,?,?,?)",
                    roleId, shop, period, product, amount);
        } else {
            jdbc.update("UPDATE local_shop_purchase SET quantity=? WHERE role_id=? AND shop=? AND period_start=? AND product_id=?",
                    previous + amount, roleId, shop, period, product);
        }
    }

    /**
     * 查询当前道具的库存行
     *
     * @param roleId 角色编号
     * @param itemId 物品编号
     * @return 对应库存记录
     */
    Optional<InventoryItem> item(String roleId, int itemId) {
        return jdbc.query("SELECT * FROM local_inventory_item WHERE role_id = ? AND item_id = ?",
                ITEM, roleId, itemId).stream().findFirst();
    }

    /**
     * 查询角色持有的精灵碎片。
     *
     * @param roleId 角色编号
     * @param configId 精灵配置编号
     * @return 当前碎片数
     */
    long chipCount(String roleId, int configId) {
        return jdbc.query("SELECT quantity FROM local_elf_chip WHERE role_id=? AND config_id=?",
                (row, index) -> row.getLong(1), roleId, configId).stream().findFirst().orElse(0L);
    }

    /**
     * 在角色锁内扣除一份合成材料，刚好用尽时移除碎片堆叠。
     * @param roleId 角色编号
     * @param configId 碎片模板编号
     * @param need 原表所需碎片数
     * @return 是否成功消耗足量材料
     */
    boolean consumeChips(String roleId, int configId, int need) {
        if (jdbc.update("DELETE FROM local_elf_chip WHERE role_id=? AND config_id=? AND quantity=?",
                roleId, configId, need) > 0) {
            return true;
        }
        return jdbc.update("UPDATE local_elf_chip SET quantity=quantity-? "
                + "WHERE role_id=? AND config_id=? AND quantity>?", need, roleId, configId, need) == 1;
    }

    /**
     * 增加已校验的精灵碎片数量。
     *
     * @param roleId 角色编号
     * @param configId 精灵配置编号
     * @param quantity 增加数量
     */
    void addChips(String roleId, int configId, long quantity) {
        if (jdbc.update("UPDATE local_elf_chip SET quantity=quantity+? WHERE role_id=? AND config_id=?",
                quantity, roleId, configId) == 0) {
            jdbc.update("INSERT INTO local_elf_chip(role_id,config_id,quantity) VALUES (?,?,?)",
                    roleId, configId, quantity);
        }
    }

    /**
     * 读取一种玩法货币的可用余额。
     *
     * @param roleId 角色编号
     * @param currency 底包货币类型
     * @return 当前可用余额
     */
    long currency(String roleId, int currency) {
        return jdbc.query("SELECT balance FROM local_inventory_currency WHERE role_id=? AND currency=?",
                (row, index) -> row.getLong(1), roleId, currency).stream().findFirst().orElse(0L);
    }

    /**
     * 将角色锁内计算完成的余额写入对应货币账本。
     *
     * @param roleId 角色编号
     * @param currency 底包货币类型
     * @param balance 更新后的余额
     */
    void saveCurrency(String roleId, int currency, long balance) {
        if (jdbc.update("UPDATE local_inventory_currency SET balance=? WHERE role_id=? AND currency=?",
                balance, roleId, currency) == 0) {
            jdbc.update("INSERT INTO local_inventory_currency(role_id,currency,balance) VALUES (?,?,?)",
                    roleId, currency, balance);
        }
    }

    /**
     * 查询角色的背包快照
     *
     * @param roleId 角色编号
     * @return 按格子排列的库存列表
     */
    List<InventoryItem> items(String roleId) {
        return jdbc.query("SELECT * FROM local_inventory_item WHERE role_id = ? ORDER BY position", ITEM, roleId);
    }

    /**
     * 新增一格库存
     *
     * @param roleId 角色编号
     * @param itemId 物品编号
     * @param quantity 数量
     * @param position 格子号
     */
    void insertItem(String roleId, int itemId, long quantity, int position) {
        jdbc.update("INSERT INTO local_inventory_item(role_id, item_id, quantity, position) VALUES (?, ?, ?, ?)",
                roleId, itemId, quantity, position);
    }

    /**
     * 保存库存行或删除耗尽的堆叠
     *
     * @param roleId 角色编号
     * @param itemId 物品编号
     * @param quantity 更新后数量
     */
    void updateItem(String roleId, int itemId, long quantity) {
        if (quantity == 0) {
            jdbc.update("DELETE FROM local_inventory_item WHERE role_id = ? AND item_id = ?", roleId, itemId);
        } else {
            jdbc.update("UPDATE local_inventory_item SET quantity = ? WHERE role_id = ? AND item_id = ?",
                    quantity, roleId, itemId);
        }
    }

    /**
     * 查询角色未过期的系统邮件
     *
     * @param roleId 角色编号
     * @param now 当前 Unix 秒
     * @return 系统邮件列表
     */
    List<SystemMail> mail(String roleId, long now) {
        Map<String, SystemMail> mails = new LinkedHashMap<>();
        jdbc.query("SELECT m.*, a.ordinal, COALESCE(a.asset_type, a.reward_type) AS reward_type, a.config_id, "
                        + "a.quantity - a.claimed_quantity AS reward_remaining FROM local_system_mail m "
                        + "LEFT JOIN local_system_mail_reward a ON m.id = a.mail_id "
                        + "WHERE m.role_id = ? AND m.expires_at > ? ORDER BY m.created_at DESC, m.id, a.ordinal",
                row -> {
                    String id = row.getString("id");
                    SystemMail mail = mails.get(id);
                    if (mail == null) {
                        boolean walletClaimed = row.getBoolean("wallet_claimed");
                        mail = new SystemMail(id, row.getString("subject"), row.getString("body"),
                                row.getLong("created_at"), row.getLong("expires_at"), row.getBoolean("opened"),
                                row.getBoolean("claimed"), walletClaimed ? 0 : row.getLong("coins"),
                                walletClaimed ? 0 : row.getLong("diamonds"), walletClaimed ? 0 : row.getLong("dojo_points"),
                                row.getInt("item_id"),
                                row.getLong("quantity") - row.getLong("claimed_quantity"), new ArrayList<>());
                        mails.put(id, mail);
                    }
                    int configId = row.getInt("config_id");
                    int remaining = row.getInt("reward_remaining");
                    if (configId > 0 && remaining > 0) {
                        mail.rewards.add(new SystemMailReward(row.getInt("reward_type"), configId,
                                remaining, row.getInt("ordinal")));
                    }
                }, roleId, now);
        return new ArrayList<>(mails.values());
    }

    /**
     * 写入包含多个附件的系统邮件
     *
     * @param roleId 角色编号
     * @param id 确定的邮件编号
     * @param subject 邮件标题
     * @param body 邮件正文
     * @param createdAt 创建时刻 Unix 秒
     * @param expiresAt 到期时刻 Unix 秒
     * @param rewards 按客户端展示顺序排列的附件
     */
    void createMail(String roleId, String id, String subject, String body, long createdAt, long expiresAt,
                    List<SystemMailReward> rewards) {
        jdbc.update("INSERT INTO local_system_mail(id, role_id, subject, body, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, roleId, subject, body, createdAt, expiresAt);
        for (int index = 0; index < rewards.size(); index++) {
            SystemMailReward reward = rewards.get(index);
            // 旧存档的 reward_type 仅允许 1/2；碎片及货币沿用堆叠槽位，另存其原资产类型。
            boolean extended = reward.type != SystemMailReward.ITEM && reward.type != SystemMailReward.ELF;
            jdbc.update("INSERT INTO local_system_mail_reward(mail_id, ordinal, reward_type, config_id, quantity, asset_type) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", id, index, extended ? SystemMailReward.ITEM : reward.type,
                    reward.configId, reward.quantity, extended ? reward.type : null);
        }
    }

    /**
     * 查询需显示角标的有效邮件总数。
     * @param roleId 收件角色
     * @param now 当前 Unix 秒
     * @return 未读或仍有附件的邮件数量
     */
    long pendingMailCount(String roleId, long now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_system_mail WHERE role_id=? AND expires_at>? "
                + "AND (opened=FALSE OR claimed=FALSE)", Long.class, roleId, now);
    }

    /**
     * 读取特定物品发放回执，包括已经领取的邮件。
     * @param roleId 收件角色
     * @param id 确认请求对应的邮件编号
     * @return 已寄送邮件的正文
     */
    List<String> grantMailBody(String roleId, String id) {
        return jdbc.queryForList("SELECT body FROM local_system_mail WHERE role_id=? AND id=?", String.class, roleId, id);
    }

    /**
     * 将货币附件写入原邮箱使用的钱包栏。
     * @param roleId 收件角色
     * @param id 已创建的邮件编号
     * @param type 金币、钻石或道馆点类型
     * @param quantity 发送数量
     */
    void setGrantWallet(String roleId, String id, int type, long quantity) {
        jdbc.update("UPDATE local_system_mail SET coins=?, diamonds=?, dojo_points=? WHERE role_id=? AND id=?",
                type == 103 ? quantity : 0L, type == 104 ? quantity : 0L, type == 105 ? quantity : 0L, roleId, id);
    }

    /**
     * 标记角色的邮件为已读
     *
     * @param roleId 角色编号
     * @param mailId 邮件编号
     * @param now 当前 Unix 秒
     * @return 是否存在且未过期
     */
    boolean openMail(String roleId, String mailId, long now) {
        return jdbc.update("UPDATE local_system_mail SET opened = TRUE WHERE role_id = ? AND id = ? AND expires_at > ?",
                roleId, mailId, now) == 1;
    }

    /**
     * 记录一封邮件的领取进度及完成状态。
     *
     * @param roleId 角色编号
     * @param mailId 邮件编号
     * @param now 当前 Unix 秒
     * @param quantity 本次领取的旧版道具数量
     * @param complete 本次是否领尽附件
     */
    void saveMailClaim(String roleId, String mailId, long now, long quantity, boolean complete) {
        if (jdbc.update("UPDATE local_system_mail SET claimed = ?, opened = TRUE, wallet_claimed = TRUE, "
                        + "claimed_quantity = claimed_quantity + ? WHERE role_id = ? AND id = ? "
                        + "AND expires_at > ? AND claimed = FALSE AND quantity - claimed_quantity >= ?",
                complete, quantity, roleId, mailId, now, quantity) != 1) {
            throw new IllegalStateException("邮件领取进度写入失败");
        }
    }

    /**
     * 记录一个附件的已领取数量。
     *
     * @param mailId 邮件编号
     * @param ordinal 原始附件序号
     * @param quantity 本次领取数量
     */
    void addAttachmentClaim(String mailId, int ordinal, long quantity) {
        if (jdbc.update("UPDATE local_system_mail_reward SET claimed_quantity = claimed_quantity + ? "
                + "WHERE mail_id = ? AND ordinal = ? AND quantity - claimed_quantity >= ?",
                quantity, mailId, ordinal, quantity) != 1) {
            throw new IllegalStateException("邮件附件领取进度写入失败");
        }
    }

    /**
     * 找到同一连接的已处理领取请求。
     *
     * @param roleId 角色编号
     * @param requestKey 连接与协议请求标识
     * @return 已处理请求中的完结邮件编号序列
     */
    Optional<String> mailClaimReceipt(String roleId, String requestKey) {
        return jdbc.queryForList("SELECT completed_ids FROM local_mail_claim_receipt WHERE role_id = ? AND request_key = ?",
                String.class, roleId, requestKey).stream().findFirst();
    }

    /**
     * 写入邮件领取请求回执。
     *
     * @param roleId 角色编号
     * @param requestKey 连接与协议请求标识
     * @param completedIds 已领尽邮件的编号序列
     */
    void recordMailClaim(String roleId, String requestKey, String completedIds) {
        jdbc.update("INSERT INTO local_mail_claim_receipt(role_id, request_key, completed_ids) VALUES(?, ?, ?)",
                roleId, requestKey, completedIds);
    }

    /**
     * 查询业务奖励是否已直接入库或发往系统邮件。
     *
     * @param roleId 角色编号
     * @param sourceKey 业务来源唯一键
     * @return 业务奖励已结算时为真
     */
    boolean grantedReward(String roleId, String sourceKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_inventory_grant_receipt "
                + "WHERE role_id = ? AND source_key = ?", Integer.class, roleId, sourceKey) > 0;
    }

    /**
     * 与实际入账及系统邮件共同写入结算回执。
     *
     * @param roleId 角色编号
     * @param sourceKey 业务来源唯一键
     */
    void recordRewardGrant(String roleId, String sourceKey) {
        jdbc.update("INSERT INTO local_inventory_grant_receipt(role_id, source_key) VALUES(?, ?)", roleId, sourceKey);
    }
}
