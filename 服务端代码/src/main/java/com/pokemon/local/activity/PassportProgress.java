package com.pokemon.local.activity;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通行证不同玩法共用的北京时间日计数。
 * @author tongruiyuan
 * @since 2026-11-20 12:17
 */
public final class PassportProgress {
    private PassportProgress() { }

    /**
     * 在角色已经锁定的成功事务中增加某项原任务计数。
     * @param jdbc 与原玩法共用的事务数据库
     * @param roleId 角色编号
     * @param kind 原任务完成条件编号
     * @param quantity 实际增加次数、金币或精灵数量
     */
    public static void record(JdbcTemplate jdbc,String roleId,int kind,long quantity) {
        if(quantity<=0)return;
        long day=Math.floorDiv(Instant.now().getEpochSecond()+28800L,86400L)*86400L-28800L;
        if(jdbc.update("UPDATE local_passport_metric SET quantity=quantity+? WHERE role_id=? AND event_day=? AND kind=?",quantity,roleId,day,kind)==0) {
            jdbc.update("INSERT INTO local_passport_metric(role_id,event_day,kind,quantity) VALUES(?,?,?,?)",roleId,day,kind,quantity);
        }
    }
}
