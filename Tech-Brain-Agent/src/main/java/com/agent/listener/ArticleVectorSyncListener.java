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
    private ArticleVectorService articleVectorService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) // MySQL事务提交成功后再同步Redis，避免回滚后留下脏向量。
    public void onArticleVectorSyncEvent(ArticleVectorSyncEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        try {
            // 按笔记变更类型分发到对应的向量新增、更新或删除逻辑。
            switch (event.getType()) {
                case UPSERT -> articleVectorService.syncArticleVector(event.getArticle());
                case DELETE -> {
                    if (event.getArticleIds() != null && !event.getArticleIds().isEmpty()) {
                        articleVectorService.deleteArticleVector(event.getArticleIds().get(0));
                    }
                }
                case BATCH_DELETE -> articleVectorService.deleteArticleVectorBatch(event.getArticleIds());
            }
        } catch (Exception e) {
            log.error("文章向量同步事件处理异常，event={}", event, e);
        }
    }
}
