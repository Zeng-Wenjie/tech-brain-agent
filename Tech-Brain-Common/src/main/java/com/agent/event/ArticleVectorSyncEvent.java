package com.agent.event;

import com.agent.entity.Article;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ArticleVectorSyncEvent {

    private EventType type;

    private Article article; // 新增或更新时携带完整笔记数据，便于提交后直接重建向量。

    private List<Long> articleIds; // 删除时只需要笔记ID集合，避免传递已删除的完整数据。

    public enum EventType {
        UPSERT, // 新增或更新笔记后重建向量，保证索引内容与MySQL最新数据一致。
        DELETE, // 删除单篇笔记后删除向量，避免RAG检索到已删除内容。
        BATCH_DELETE // 批量删除笔记后批量删除向量，减少事件类型分散处理。
    }
}
