package com.pokemon.offline.inventory.api.vo;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 个人信息页的物品邮件请求，用于指定本人角色、附件及发放数量。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:36
 */
public final class ProfileMailVO {
    /** 当前游戏角色编号。 */
    public String roleId;
    /** 本次点击的唯一编号，网络重试时沿用。 */
    public String requestId;
    /** 原附件大类编号。 */
    public int type;
    /** 底包资产编号。 */
    public int itemId;
    /** 发放件数。 */
    public long quantity;

    /**
     * 读取原始 JSON 整数，格式不符或超出附件容量时交由服务统一返回数量错误。
     * @param value 请求中的数量字段
     */
    @JsonSetter("quantity")
    public void setQuantity(JsonNode value) {
        quantity = value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : 0;
    }
}
