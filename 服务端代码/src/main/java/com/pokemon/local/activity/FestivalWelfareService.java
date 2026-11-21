package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 节日登录福利 Service，按北京时间周一轮换原七种奖励。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:58
 */
@Service
public class FestivalWelfareService {
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfService elves;
    private final int[][] rewards;

    /**
     * 读取原节日奖励并准备角色领取记录。
     * @param jdbc 本地存档
     * @param roles 角色与钱包
     * @param inventory 背包与邮件
     * @param elves 精灵持有与图鉴
     * @param json 配置解析器
     * @throws IOException 原奖励配置读取失败
     */
    public FestivalWelfareService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory, ElfService elves,
                                  ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.elves = elves;
        try (InputStream in = new ClassPathResource("activity/festival-welfare.json").getInputStream()) {
            rewards = json.readValue(in, int[][].class);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_festival_welfare(role_id VARCHAR(100) NOT NULL,"
                + " week_no BIGINT NOT NULL, PRIMARY KEY(role_id,week_no))");
    }

    /**
     * 计算北京时间周一零点切换的轮次。
     * @return 当前周序号
     */
    public long week() {
        return Math.floorDiv(Instant.now().getEpochSecond() + 288000L, 604800L);
    }

    /**
     * 查询本轮在原弹窗中的奖励索引。
     * @param week 周序号
     * @return 从一开始的原表行号
     */
    public int index(long week) {
        return (int) Math.floorMod(week, (long) rewards.length) + 1;
    }

    /**
     * 查询本周领取记录。
     * @param id 角色编号
     * @param week 本地周序号
     * @return 是否已领取
     */
    public boolean claimed(String id, long week) {
        return !jdbc.queryForList("SELECT week_no FROM local_festival_welfare WHERE role_id=? AND week_no=?",
                Long.class, id, week).isEmpty();
    }

    /**
     * 领取角色本周已预览的节日福利，容量不足的物品和精灵分别转入邮件。
     * @param id 角色编号
     * @param previewWeek 此连接上次看到的周序号
     * @return 实际是否发放
     */
    @Transactional
    public boolean claim(String id, long previewWeek) {
        if (roles.lockRole(id).level < 40 || week() != previewWeek || claimed(id, previewWeek)) {
            return false;
        }
        String receipt = "festival:" + previewWeek;
        Map<Integer, Long> items = new LinkedHashMap<>();
        int[] row = rewards[index(previewWeek) - 1];
        for (int i = 0; i < row.length; i += 3) {
            int type = row[i], config = row[i + 1], count = row[i + 2];
            switch (type) {
                case 1:
                    items.merge(config, (long) count, Long::sum);
                    break;
                case 2:
                    if (elves.list(id).size() < inventory.elfCapacity(id)) {
                        elves.grant(id, config);
                    } else {
                        String mail = UUID.nameUUIDFromBytes((id + ":" + receipt + ":elf")
                                .getBytes(StandardCharsets.UTF_8)).toString();
                        long now = Instant.now().getEpochSecond();
                        inventory.createRewardMail(id, mail, "节日登录福利", "腾出精灵背包位置后可领取。",
                                now, now + 30L * 86400, Arrays.asList(type, config, count));
                    }
                    break;
                case 104:
                    roles.addCurrency(id, 0, count, 0);
                    break;
                default:
                    throw new IllegalStateException("未知节日登录奖励类型 " + type);
            }
        }
        inventory.grantItemsOrMail(id, receipt, items);
        jdbc.update("INSERT INTO local_festival_welfare(role_id,week_no) VALUES(?,?)", id, previewWeek);
        return true;
    }
}
