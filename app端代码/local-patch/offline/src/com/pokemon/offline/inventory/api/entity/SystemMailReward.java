package com.pokemon.offline.inventory.api.entity;

/**
 * 系统邮件中的一条物品、精灵或碎片附件
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:42
 */
public final class SystemMailReward {
    public static final int ITEM = 1;
    public static final int ELF = 2;
    public static final int ELF_CHIP = 3;
    /** 精灵携带品独立实例。 */
    public static final int CARRY = 4;
    public static final int DRESS_CHIP = 7;
    /** 秘境产出的精灵装备独立实例。 */
    public static final int ZSTONE = 11;
    /** 训练家装备刻印使用的套装贴纸。 */
    public static final int STICKER = 12;
    /** 精灵 P 力石独立实例。 */
    public static final int POWERSTONE = 13;
    /** 原客户端超古代石盘碎片的独立资产类型。 */
    public static final int TABLET = 888;
    /** 公会研究使用的独立考古材料。 */
    public static final int ANTIQUITY = 20;
    public static final int VITALITY = 106;
    public static final int ARENA_POINTS = 111;
    public static final int HOME_COINS = 118;
    public static final int FASHION_COINS = 125;

    /**
     * 获取原邮箱展示此扩展货币附件使用的字段。
     * @return 货币字段，其他附件为空
     */
    public String currencyField() {
        switch (type) {
            case VITALITY: return "vitality";
            case ARENA_POINTS: return "pkpoint";
            case HOME_COINS: return "homecoin";
            case FASHION_COINS: return "fashescoins";
            default: return null;
        }
    }

    /** 原版奖励类型 */
    public final int type;
    /** 道具或精灵配置编号 */
    public final int configId;
    /** 当前尚未领取的数量，新建邮件时是奖励总数。 */
    public final int quantity;
    /** 邮件内的原始附件序号。 */
    public final int ordinal;

    /**
     * 创建一条邮件附件
     *
     * @param type 原版奖励类型
     * @param configId 配置编号
     * @param quantity 附件数量
     */
    public SystemMailReward(int type, int configId, int quantity) {
        this(type, configId, quantity, -1);
    }

    /**
     * 构造带有固定序号的未领附件。
     *
     * @param type 原版奖励类型
     * @param configId 配置编号
     * @param quantity 剩余数量
     * @param ordinal 邮件内的原始附件序号
     */
    public SystemMailReward(int type, int configId, int quantity, int ordinal) {
        this.type = type;
        this.configId = configId;
        this.quantity = quantity;
        this.ordinal = ordinal;
    }
}
