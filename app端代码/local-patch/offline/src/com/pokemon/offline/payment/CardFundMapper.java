package com.pokemon.offline.payment;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 福利卡与成长基金领取记录 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:49
 */
@Repository
@DependsOnDatabaseInitialization
class CardFundMapper {
    private final JdbcTemplate jdbc;

    /**
     * 注入存档访问模板。
     *
     * @param jdbc 数据库访问模板
     */
    CardFundMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 初始化福利卡和基金的领取记录。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("payment/card-fund.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 查询指定充值档位最近一次确认时间。
     *
     * @param roleId 角色编号
     * @param paysid 原充值档位
     * @return 确认时间毫秒，未生效时为零
     */
    long lastPayment(String roleId, int paysid) {
        Long time = jdbc.queryForObject("SELECT MAX(confirmed_at) FROM local_game_payment_order "
                + "WHERE role_id=? AND paysid=?", Long.class, roleId, paysid);
        return time == null ? 0 : time;
    }

    /**
     * 查询指定业务日已领取的福利卡。
     *
     * @param roleId 角色编号
     * @param day 北京时间业务日期
     * @return 当天领取过的福利卡档位
     */
    Set<Integer> cardClaims(String roleId, LocalDate day) {
        return new HashSet<>(jdbc.query("SELECT paysid FROM local_card_daily_claim WHERE role_id=? AND business_day=?",
                (row, index) -> row.getInt(1), roleId, day.toString()));
    }

    /**
     * 查询成长基金已领档位。
     *
     * @param roleId 角色编号
     * @return 已领取档位
     */
    Set<Integer> fundClaims(String roleId) {
        return new HashSet<>(jdbc.query("SELECT tier FROM local_fund_claim WHERE role_id=?",
                (row, index) -> row.getInt(1), roleId));
    }

    /**
     * 保存福利卡每日领取记录。
     *
     * @param roleId 角色编号
     * @param paysid 原充值档位
     * @param day 北京时间业务日期
     * @return 保存记录行数
     */
    int insertCardClaim(String roleId, int paysid, LocalDate day) {
        return jdbc.update("INSERT INTO local_card_daily_claim (role_id,paysid,business_day) VALUES (?,?,?)",
                roleId, paysid, day.toString());
    }

    /**
     * 保存成长基金档位领取记录。
     *
     * @param roleId 角色编号
     * @param tier 原基金档位
     * @return 保存记录行数
     */
    int insertFundClaim(String roleId, int tier) {
        return jdbc.update("INSERT INTO local_fund_claim (role_id,tier) VALUES (?,?)", roleId, tier);
    }
}
