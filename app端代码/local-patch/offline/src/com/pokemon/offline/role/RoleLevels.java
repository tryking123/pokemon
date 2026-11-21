package com.pokemon.offline.role;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Component;

/**
 * 固定客户端版本的角色经验曲线
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:15
 */
@Component
final class RoleLevels {

    private final List<Long> thresholds = new ArrayList<>();

    /**
     * 读取底包等级表，核对等级连续性、正阈值和末级标志
     *
     * @throws IOException 固定配置读取失败
     */
    RoleLevels() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("role/level-progression.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\\s+");
                int level = Integer.parseInt(columns[0]);
                long exp = Long.parseLong(columns[1]);
                if (level != thresholds.size() + 1 || exp < 0
                        || (!thresholds.isEmpty() && thresholds.get(thresholds.size() - 1) == 0)) {
                    throw new IOException("角色等级配置错误：" + line);
                }
                thresholds.add(exp);
            }
        }
        if (thresholds.isEmpty() || thresholds.get(thresholds.size() - 1) != 0) {
            throw new IOException("角色等级配置缺少满级标志");
        }
    }

    /** 获取当前训练师阶段下升至下一级所需的经验。
     * @param stage 训练师阶段
     * @param level 当前等级
     * @return 升级所需经验，满级或无配置时为零
     */
    long threshold(int stage, int level) {
        return supports(level) && level < maximum(stage) ? thresholds.get(level - 1) : 0L;
    }

    /**
     * 读取原 variable_proto 1007 至 1009 的人物等级上限。
     * @param stage 训练师阶段
     * @return 本阶段允许达到的最高等级
     */
    private int maximum(int stage) {
        return stage <= 0 ? 99 : stage == 1 ? 100 : thresholds.size();
    }

    /**
     * 验证初始等级是否有底包配置。
     *
     * @param level 要设置的角色等级
     * @return 等级是否存在
     */
    boolean supports(int level) {
        return level >= 1 && level <= thresholds.size();
    }

    /**
     * 依次扣除本级阈值并保留下一等级尚未消耗的经验
     *
     * @param stage 当前训练师阶段
     * @param currentLevel 存档等级
     * @param currentExp 现有经验，兼容旧版未扣除阈值的经验
     * @param addition 本次实际获得经验
     * @return 基于真实阈值的等级及级内经验
     */
    Progression advance(int stage, int currentLevel, long currentExp, long addition) {
        int level = currentLevel;
        int max = maximum(stage);
        if (level >= max) {
            // 保留旧档超过阶段上限的等级及已有级内经验，达到全局满级时清空经验。
            return new Progression(level, level >= thresholds.size() ? 0 : currentExp);
        }
        if (level < 1 || currentExp < 0 || addition < 0) {
            throw new IllegalArgumentException("角色经验存档无效");
        }

        long remaining = addition > Long.MAX_VALUE - currentExp ? Long.MAX_VALUE : currentExp + addition;
        while (level < max && remaining >= thresholds.get(level - 1)) {
            remaining -= thresholds.get(level - 1);
            level++;
        }
        return new Progression(level, level == max ? 0 : remaining);
    }

    /**
     * 本级剩余经验和已达到的等级
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:15
     */
    static final class Progression {
        final int level;
        final long exp;

        /**
         * 建立增长后的角色状态
         *
         * @param level 已达到的等级
         * @param exp 本级经验
         */
        private Progression(int level, long exp) {
            this.level = level;
            this.exp = exp;
        }
    }
}
