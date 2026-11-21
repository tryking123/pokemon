package com.pokemon.local.quest;

import com.pokemon.local.quest.api.entity.QuestTask;
import java.util.List;

/**
 * 入场及领奖时同步的任务状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
public final class QuestSnapshot {
    public final int chapter;
    public final List<QuestTask> tasks;

    /**
     * 构造任务快照
     *
     * @param chapter 已领取章节索引
     * @param tasks 持久化的当前任务
     */
    QuestSnapshot(int chapter, List<QuestTask> tasks) {
        this.chapter = chapter;
        this.tasks = tasks;
    }
}
