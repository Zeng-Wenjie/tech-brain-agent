package com.agent.service;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.baomidou.mybatisplus.extension.service.IService;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List; // Tool Calling 真实 RAG 检索返回多个知识片段。
/*
    负责AI回答
 */

public interface AgentService extends IService<Article> {

    List<String> searchRagContents(String query); // 只复用现有 Milvus RAG 检索，返回 tool result 需要的知识片段。

    List<TextSegment> searchRagSegments(String query); // 返回带metadata的Milvus命中片段，供RagSearchTool保存top1 focus。

    void saveAiNote(ArticleSaveDTO dto);

    String summarizeArticle(Long articleId);
}
