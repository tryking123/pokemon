package com.pokemon.offline.home.api.vo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 当前宠物定制请求，用于本地面板调整已有宠物的培养配置。
 * @author tongruiyuan
 * @since 2026-11-22 03:53
 */
public final class PetCustomizeVO {
    /** 当前账号的角色编号。 */
    public String roleId;
    /** 当前选中的宠物实例编号。 */
    public String petId;
    /** 本次保存的唯一编号，重试时保持不变。 */
    public String requestId;
    /** 宠物等级，保留原始类型供业务校验整数。 */
    public JsonNode level;
    /** 资质档位。 */
    public JsonNode aptitudes;
    /** 第一天赋编号。 */
    public JsonNode talent;
    /** 第二天赋编号，零表示无。 */
    public JsonNode talent2;
    /** 光环预设编号数组，未解锁槽位仍按原等级限制展示。 */
    public JsonNode aureoles;
}
