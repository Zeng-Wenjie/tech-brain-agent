package com.agent.listener;

import com.agent.event.ArticleVectorSyncEvent;
import com.agent.service.ArticleVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
/*
   双写同步事件监听
 */
@Slf4j
@Component
public class ArticleVectorSyncListener {

    @Autowired
    private ArticleVectorService articleVectorService; // 事件监听器只负责编排，具体 Milvus 增删改交给向量服务。

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) // MySQL事务提交成功后再同步Redis，避免回滚后留下脏向量。
    public void onArticleVectorSyncEvent(ArticleVectorSyncEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        try {
            // 按笔记变更类型分发到对应的向量新增、更新或删除逻辑。
            switch (event.getType()) { // AFTER_COMMIT 后分发，保证只有 MySQL 主事务成功才同步 Milvus。
                case UPSERT -> articleVectorService.syncArticleVector(event.getArticle()); // 新增或修改文章时重建该文章向量。
                case DELETE -> {
                    if (event.getArticleIds() != null && !event.getArticleIds().isEmpty()) {
                        articleVectorService.deleteArticleVector(event.getArticleIds().get(0)); // 单篇删除时移除对应 article:{id} 向量。
                    }
                }
                case BATCH_DELETE -> articleVectorService.deleteArticleVectorBatch(event.getArticleIds()); // 批量删除时逐个清理 Milvus 索引。
            }
        } catch (Exception e) {
            log.error("文章向量同步事件处理异常，event={}", event, e);
        }
    }
}
