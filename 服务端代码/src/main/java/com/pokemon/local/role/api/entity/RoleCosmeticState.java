package com.pokemon.local.role.api.entity;

/**
 * 本地角色外观存档，保存称号佩戴、称号展示和当前时装。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:34
 */
public final class RoleCosmeticState {
    /** 称号界面当前佩戴编号，零表示未佩戴。 */
    public final int equippedTitle;
    /** 角色对外展示的称号编号，零表示隐藏。 */
    public final int displayedTitle;
    /** 角色当前穿戴的时装编号，零表示默认外观。 */
    public final int wornDress;

    /**
     * 构造同一角色的三个外观选择。
     *
     * @param equippedTitle 当前佩戴的称号
     * @param displayedTitle 当前展示的称号
     * @param wornDress 当前穿戴的时装
     */
    public RoleCosmeticState(int equippedTitle, int displayedTitle, int wornDress) {
        this.equippedTitle = equippedTitle;
        this.displayedTitle = displayedTitle;
        this.wornDress = wornDress;
    }
}
