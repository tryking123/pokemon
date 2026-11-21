package com.pokemon.offline.quest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从客户端原任务或章节表加载的领取奖励
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
public final class QuestReward {
    public final long coins;
    public final long diamonds;
    public final long exp;
    public final Map<Integer, Long> items;
    /** 作为独立实例入库的训练家装备模板及数量。 */
    public final Map<Integer, Long> equipment;
    final boolean supported;

    /**
     * 构造原始奖励并解析合法物品、金币和钻石
     *
     * @param baseCoins 金币
     * @param baseDiamonds 钻石
     * @param exp 角色经验
     * @param encoded 配置中的奖励元组
     */
    QuestReward(long baseCoins, long baseDiamonds, long exp, String encoded) {
        long coinTotal = baseCoins;
        long diamondTotal = baseDiamonds;
        boolean valid = true;
        Map<Integer, Long> grouped = new LinkedHashMap<>();
        Map<Integer, Long> gear = new LinkedHashMap<>();
        if (!"-".equals(encoded)) {
            for (String entry : encoded.split(",")) {
                String[] tuple = entry.split(":");
                int type = Integer.parseInt(tuple[0]);
                int itemId = Integer.parseInt(tuple[1]);
                long amount = Long.parseLong(tuple[2]);
                if (type == 103 && amount > 0) {
                    coinTotal = Math.addExact(coinTotal, amount);
                } else if (type == 104 && amount > 0) {
                    diamondTotal = Math.addExact(diamondTotal, amount);
                } else if (type == 10 && itemId >= 10011 && itemId <= 10016 && amount > 0) {
                    gear.merge(itemId, amount, Math::addExact);
                } else if (type == 1 && itemId > 0 && amount > 0) {
                    grouped.merge(itemId, amount, Math::addExact);
                } else {
                    valid = false;
                }
            }
        }
        this.coins = coinTotal;
        this.diamonds = diamondTotal;
        this.exp = exp;
        this.items = Collections.unmodifiableMap(grouped);
        this.equipment = Collections.unmodifiableMap(gear);
        this.supported = valid;
    }
}
