package com.pokemon.local.payment;

import com.pokemon.local.common.ApiException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 从固定底包 chongzhi_proto 提取的本地游戏商品目录
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:40
 */
final class GamePaymentCatalog {
    private static final Map<Integer, Product> PRODUCTS = new LinkedHashMap<>();

    static {
        // 福利卡的激活赠送与每日领取分开记账，成长基金按等级领取。
        PRODUCTS.put(1, new Product("周卡", 18, 14000, 0));
        PRODUCTS.put(2, new Product("月卡", 68, 50000, 0));
        PRODUCTS.put(3, new Product("终身卡", 168, 0, 0, 31022, 5, 21062, 20, 34563, 1));
        PRODUCTS.put(4, new Product("成长基金", 98, 0, 0));
        // 价格为底包 chargeValue，钻石折合底包 gemValue 和首次 extragemValue。
        int[] prices = {6, 30, 60, 128, 198, 328, 648, 3280, 6480, 10000};
        for (int index = 0; index < prices.length; index++) {
            int diamonds = prices[index] * 1000;
            PRODUCTS.put(index + 5, new Product("钻石", prices[index], diamonds, diamonds));
        }
        // 这些固定礼包完整的道具和钻石均来自其对应档位的 rew 字段。
        PRODUCTS.put(101, new Product("礼包 A", 18, 50000, 0, 21064, 20, 34135, 1));
        PRODUCTS.put(103, new Product("礼包 B", 30, 100000, 0, 21064, 30, 34136, 1));
        PRODUCTS.put(104, new Product("礼包 C", 60, 0, 0, 21024, 2000, 21064, 200, 21026, 500));
        PRODUCTS.put(106, new Product("礼包 D", 128, 300000, 0, 21064, 500, 34137, 1));
        PRODUCTS.put(301, new Product("每周直购礼包", 6, 0, 0));
        PRODUCTS.put(302, new Product("连续充值每日礼包", 30, 0, 0));
        PRODUCTS.put(303, new Product("限时商城礼包 3", 60, 0, 0));
        PRODUCTS.put(304, new Product("限时商城礼包 4", 128, 0, 0));
        PRODUCTS.put(305, new Product("限时商城礼包 5", 168, 0, 0));
        PRODUCTS.put(306, new Product("限时商城礼包 6", 328, 0, 0));
        // 超值礼包的家园币与精灵碎片必须按各自资产类型发放。
        PRODUCTS.put(401, new Product("精灵盲盒×1", 20, 0, 0));
        PRODUCTS.put(402, new Product("精灵盲盒×10", 200, 0, 0));
        PRODUCTS.put(407, new Product("精灵盲盒×50", 1000, 0, 0));
        PRODUCTS.put(403, new Product("精灵盲盒×100", 2000, 0, 0));
        PRODUCTS.put(404, new Product("时装碎片盲盒×1", 20, 0, 0));
        PRODUCTS.put(405, new Product("时装碎片盲盒×10", 200, 0, 0));
        PRODUCTS.put(501, new Product("超值礼包 501", 128, 150000, 0, 5000,
                Collections.emptyMap(), 34405, 8));
        PRODUCTS.put(503, new Product("超值礼包 503", 328, 700000, 0, 0,
                Collections.singletonMap(62001, 25), 34406, 6));
        PRODUCTS.put(504, new Product("超值礼包 504", 648, 2000000, 0, 15000,
                Collections.singletonMap(64174, 50)));
        PRODUCTS.put(2001, new Product("每日代金券 3000", 600, 0, 0));
        PRODUCTS.put(2002, new Product("每日代金券 15800", 9800, 0, 0));
    }

    /**
     * 固定目录无需构造实例
     */
    private GamePaymentCatalog() {
    }

    /**
     * 获取当前已支持的完整商品
     *
     * @param paysid 原客户端商品档位
     * @return 商品内容、价格和发放规则
     */
    static Product require(int paysid) {
        Product product = PRODUCTS.get(paysid);
        if (product == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GAME_PRODUCT_NOT_FOUND", "该游戏商品尚未配置本地发放");
        }
        return product;
    }

    /**
     * 游戏模拟充值目录中的一个完整商品
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:40
     */
    static final class Product {
        final String name;
        final long amount;
        final long diamonds;
        final long firstBonus;
        final long homeCoins;
        final Map<Integer, Integer> items;
        final Map<Integer, Integer> chips;

        /**
         * 保存原版价格与完整发放内容
         *
         * @param name 本地显示名称
         * @param price 原版价格，单位元
         * @param diamonds 基础或礼包直接奖励钻石
         * @param firstBonus 普通钻石档位首次购买额外赠送
         * @param itemPairs 按物品编号、数量排列的数组
         */
        Product(String name, int price, long diamonds, long firstBonus, int... itemPairs) {
            this(name, price, diamonds, firstBonus, 0, Collections.emptyMap(), itemPairs);
        }

        /**
         * 保存含家园币和精灵碎片的底包礼包。
         *
         * @param name 本地商品名称
         * @param price 原版商品价格，单位元
         * @param diamonds 角色钻石奖励
         * @param firstBonus 首购额外钻石
         * @param homeCoins 家园币奖励
         * @param chips 精灵模板和碎片数量
         * @param itemPairs 按道具模板和数量排列的数组
         */
        Product(String name, int price, long diamonds, long firstBonus, long homeCoins,
                Map<Integer, Integer> chips, int... itemPairs) {
            this.name = name;
            this.amount = price * 100L;
            this.diamonds = diamonds;
            this.firstBonus = firstBonus;
            this.homeCoins = homeCoins;
            this.chips = chips;
            Map<Integer, Integer> items = new LinkedHashMap<>();
            for (int index = 0; index < itemPairs.length; index += 2) {
                items.put(itemPairs[index], itemPairs[index + 1]);
            }
            this.items = Collections.unmodifiableMap(items);
        }
    }
}
