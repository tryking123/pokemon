package com.pokemon.local.quest.api.entity;

/**
 * 本地任务的可同步状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
public final class QuestTask {
    public final int id;
    public final int state;
    public final int progress;

    /**
     * 转换服务器记录为原游戏任务状态
     *
     * @param id 任务编号
     * @param complete 任务是否完成
     * @param progress 持久化进度
     */
    public QuestTask(int id, boolean complete, int progress) {
        this.id = id;
        this.state = complete ? 2 : 1;
        this.progress = progress;
    }
}
