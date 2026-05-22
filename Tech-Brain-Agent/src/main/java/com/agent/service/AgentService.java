package com.agent.service;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List; // Tool Calling 真实 RAG 检索返回多个知识片段。
/*
    负责AI回答
 */

public interface AgentService extends IService<Article> {

//    String chat(String msg);

    String buildFinalPrompt(String msg);

    List<String> searchRagContents(String query); // 只复用现有 Milvus RAG 检索，返回 tool result 需要的知识片段。

    void saveAiNote(ArticleSaveDTO dto);

    String summarizeArticle(Long articleId);
}
