package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 按北京时间凌晨四点的矿洞日界累计开采和领取游标。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:37
 */
final class MineClock {
    private static final int SEGMENT = 1800;
    private static final int LIMIT = 16;

    /** 工具类无需实例。 */
    private MineClock() {}

    /**
     * 求当前矿洞营业日起始秒数。
     * @param now 当前时间戳
     * @return 北京时间当日四点，四点前取前一日
     */
    static long day(long now) {
        return Math.floorDiv(now+14400,86400)*86400-14400;
    }

    /**
     * 补记经过的采矿时段并在日界复位领取及掠夺限额。
     * @param state 角色矿洞存档
     * @param now 本次同步的时间戳
     */
    static void advance(ObjectNode state,long now) {
        long today = day(now);
        long previous = state.path("day").asLong(today);
        long cursor = state.path("cursor").asLong(now);
        if (now<cursor || today<previous) return;
        if (today>previous) {
            credit(state,previous+86400-cursor);
            if (today-previous>86400) {
                state.put("worked",0);
                credit(state,86400);
            }
            state.put("worked",0);
            state.put("paid",0);
            state.remove("targets");
            state.remove("vacant");
            state.remove("captures");
            if (state.has("lease")) {
                ObjectNode lease = (ObjectNode)state.get("lease");
                state.with("vacant").put(lease.path("scene").asInt()+":"+lease.path("pos").asInt(),true);
            }
            cursor=today;
        }
        credit(state,now-cursor);
        state.put("day",today);
        state.put("cursor",Math.max(cursor,now));
    }

    /**
     * 将冻结矿率下的完整半小时段加入未领账户。
     * @param state 矿洞账户
     * @param elapsed 同一营业日内经过的秒数
     */
    private static void credit(ObjectNode state,long elapsed) {
        if (elapsed<=0 || !state.has("lease")) return;
        int used = state.path("worked").asInt();
        int seconds = (int)Math.min(elapsed, Math.max(0,LIMIT*SEGMENT-used));
        if (seconds==0) return;
        state.put("worked",used+seconds);
        ObjectNode lease=(ObjectNode)state.get("lease");
        ArrayNode pending=state.withArray("pending");
        int total=lease.path("partial").asInt()+seconds;
        int blocks=Math.min(total/SEGMENT,LIMIT-pending.size());
        for (int i=0; i<blocks; i++) pending.add(lease.path("rate").asInt());
        lease.put("partial",pending.size()==LIMIT ? 0 : total%SEGMENT);
    }

    /**
     * 结算当日可支付的完整时段，超出当日额度的部分继续留存。
     * @param state 已推进的矿洞账户
     * @return 本次应入账金币
     */
    static int collect(ObjectNode state) {
        ArrayNode rows=state.withArray("pending");
        int paid=state.path("paid").asInt();
        int coins=0;
        while (rows.size()>0 && paid+coins<40000) {
            int balance=rows.get(0).asInt();
            int grant=Math.min(balance,40000-paid-coins);
            coins+=grant;
            if (grant==balance) rows.remove(0);
            else rows.set(0,rows.numberNode(balance-grant));
        }
        state.put("paid",paid+coins);
        return coins;
    }
}
