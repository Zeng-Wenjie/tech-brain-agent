package com.agent.tool.rag; // Agent模块中的具体RAG工具包，承载依赖业务Service的工具实现。

import com.agent.service.AgentService; // 复用已有AgentService中的Milvus RAG检索方法。
import com.agent.toolcalling.context.ConversationFocusService; // 保存当前会话最近一次RAG命中文档焦点。
import com.agent.toolcalling.context.ToolCallingContextHolder; // 读取Tool Calling执行期间的userId/conversationId上下文。
import com.agent.toolcalling.context.ToolCallingRequestContext; // Tool Calling请求上下文DTO。
import com.agent.toolcalling.support.AbstractAiTool; // 继承Tech-Brain-Tool提供的通用工具辅助基类。
import com.fasterxml.jackson.databind.JsonNode; // 接收模型返回的工具arguments。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造ragSearch工具参数JSON Schema。
import dev.langchain4j.data.document.Metadata; // 读取Milvus命中TextSegment中的metadata。
import dev.langchain4j.data.segment.TextSegment; // Milvus命中片段，metadata中包含articleId和title。
import lombok.extern.slf4j.Slf4j; // 打印ToolChat验收所需的RAG检索日志。
import org.springframework.beans.factory.annotation.Value; // 读取Milvus collection名称用于日志核对。
import org.springframework.stereotype.Component; // 注册为Spring Bean，供ToolRegistry自动发现。

import java.util.ArrayList; // 构造RAG返回片段列表。
import java.util.List; // AgentService.searchRagContents返回多个知识片段。

/**
 * ragSearch业务工具实现。
 *
 * <p>该类位于Tech-Brain-Agent模块，因为它依赖AgentService和项目已有Milvus RAG检索能力，不能下沉到只承载通用框架的Tech-Brain-Tool模块。</p>
 * <p>调用链为：AiToolChatServiceImpl解析模型tool_call -> ToolRegistry根据工具名找到RagSearchTool -> execute(arguments)解析query -> AgentService.searchRagContents(query)检索Milvus -> 拼接tool result返回给第二次DeepSeek调用。</p>
 * <p>本类只负责把ragSearch工具落到现有知识库检索链路上，不重写Milvus底层搜索、不修改article_vector collection，也不影响原有/chat和RAG接口。</p>
 */
@Slf4j // 生成日志对象，保持ToolChat相关日志统一输出。
@Component // 让ToolRegistry能够自动注册ragSearch工具。
public class RagSearchTool extends AbstractAiTool { // 具体业务工具继承公共抽象类，复用参数读取和Schema构造方法。

    private static final String TOOL_NAME = "ragSearch"; // 工具名必须和DeepSeek tool_call中的function.name一致。

    private static final String TOOL_DESCRIPTION = "Search the user's private knowledge base and return relevant notes."; // 给大模型看的工具描述，保持用户指定文本。

    private final AgentService agentService; // 真实RAG检索仍复用已有AgentService，不在工具里重写Milvus搜索。

    private final ConversationFocusService conversationFocusService; // RAG命中后保存当前会话最近命中文档焦点。

    @Value("${milvus.collection-name:article_vector}") // collection名称只用于日志，实际检索仍由AgentService内部配置决定。
    private String milvusCollectionName; // 当前文章向量所在Milvus collection，默认article_vector。

    public RagSearchTool(AgentService agentService,
                         ConversationFocusService conversationFocusService) { // 构造器注入业务RAG服务和会话焦点服务。
        this.agentService = agentService; // 保存AgentService，execute时调用现有searchRagContents方法。
        this.conversationFocusService = conversationFocusService; // 保存会话焦点服务，命中top1时写入Redis。
    }

    @Override // 实现AiTool工具名。
    public String name() { // 返回工具注册名。
        return TOOL_NAME; // 固定返回ragSearch，供ToolRegistry建立名称索引。
    }

    @Override // 实现AiTool工具描述。
    public String description() { // 返回给大模型看的工具说明。
        return TOOL_DESCRIPTION; // 描述工具用于搜索用户私有知识库。
    }

    @Override // 实现AiTool参数Schema。
    public ObjectNode parametersSchema() { // 构造DeepSeek tools中的function.parameters。
        ObjectNode schema = createObjectSchema(); // 创建顶层object schema。
        addProperty(schema, "query", createStringProperty("The search query extracted from the user's question."), true); // query是必填搜索词。
        return schema; // 返回完整参数Schema。
    }

    @Override // 实现AiTool执行逻辑。
    public String execute(JsonNode arguments) { // 执行模型请求的ragSearch工具。
        String query = getRequiredText(arguments, "query"); // query为空时抛出“工具参数 query 不能为空”。
        log.info("[ToolChat] real rag query: {}", query); // 打印真实RAG查询词。
        log.info("[ToolChat] vector store: Milvus"); // 明确当前向量库是Milvus，不是Redis。
        log.info("[ToolChat] milvus collection: {}", milvusCollectionName); // 打印collection名称，便于确认命中article_vector。
        List<TextSegment> segments = agentService.searchRagSegments(query); // 复用已有Milvus检索逻辑，并保留metadata供保存focus。
        saveTopHitFocus(query, segments); // 命中top1时保存会话最近命中文档上下文，失败不影响RAG回答。
        List<String> contents = buildRagContents(segments); // 将命中片段转成原有tool result文本片段。
        int resultCount = contents == null ? 0 : contents.size(); // 统计真实检索结果数量。
        String result = buildRagResult(contents); // 将知识片段拼接成模型可读的tool result。
        log.info("[ToolChat] real rag result count: {}", resultCount); // 打印真实RAG命中数量。
        log.info("[ToolChat] real rag result: {}", result); // 打印真实RAG结果，方便接口验收截图。
        return result; // 返回给AiToolChatServiceImpl，随后作为tool message发送给DeepSeek。
    }

    private void saveTopHitFocus(String query, List<TextSegment> segments) { // 保存Milvus top1命中文档到Redis focus。
        if (segments == null || segments.isEmpty()) { // 无命中不保存focus，避免覆盖上一次有效焦点。
            return; // RAG未命中时直接返回。
        }
        try {
            ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 从ThreadLocal读取当前Tool Calling上下文。
            if (context == null) { // 旧入口或非聊天链路没有上下文。
                log.warn("[RagSearchTool] skip save focus because ToolCallingRequestContext is null"); // 只警告，不影响RAG回答。
                return; // 没有上下文无法构造userId + conversationId key。
            }
            Long userId = context.getUserId(); // 获取当前用户ID。
            Long conversationId = context.getConversationId(); // 获取当前会话ID。
            if (userId == null || conversationId == null) { // 上下文缺少关键归属字段。
                log.warn("[RagSearchTool] skip save focus because userId or conversationId is null, userId: {}, conversationId: {}",
                        userId, conversationId); // 打印缺失字段，便于排查。
                return; // 不保存focus。
            }

            TextSegment topSegment = segments.get(0); // 取Milvus top1命中片段。
            Metadata metadata = topSegment == null ? null : topSegment.metadata(); // 读取top1 metadata。
            Long articleId = readArticleId(metadata); // 从metadata兼容解析articleId。
            if (articleId == null) { // 没有有效articleId时不能保存focus。
                log.warn("[RagSearchTool] skip save focus because articleId is missing in top1 metadata"); // 不打印正文。
                return; // 避免把vectorId误当articleId。
            }

            String title = readMetadataText(metadata, "title"); // 从metadata读取标题。
            conversationFocusService.saveLastHitArticle(userId, conversationId, articleId, title, TOOL_NAME, query); // 保存Redis focus。
        } catch (Exception e) {
            log.warn("[RagSearchTool] save focus failed, error: {}", e.getMessage(), e); // 保存focus失败不能影响RAG正常回答。
        }
    }

    private List<String> buildRagContents(List<TextSegment> segments) { // 将带metadata的TextSegment转换为原有RAG上下文文本。
        if (segments == null || segments.isEmpty()) { // 无命中时返回空集合。
            return List.of(); // 由buildRagResult生成固定未命中文案。
        }
        List<String> contents = new ArrayList<>(); // 保存模型可读的知识片段。
        for (TextSegment segment : segments) { // 按Milvus返回顺序拼接topK。
            String context = buildContext(segment); // 使用原有title + content格式。
            if (context != null && !context.isBlank()) { // 过滤空片段。
                contents.add(context); // 加入最终RAG内容列表。
            }
        }
        return contents; // 返回转换后的内容片段。
    }

    private String buildContext(TextSegment segment) { // 从TextSegment还原原有RAG内容格式。
        if (segment == null) { // 空片段直接跳过。
            return ""; // 返回空字符串。
        }
        Metadata metadata = segment.metadata(); // 读取metadata。
        String title = readMetadataText(metadata, "title"); // 优先使用同步向量时写入的标题。
        String content = readMetadataText(metadata, "content"); // content来自文章正文，是RAG主要知识内容。
        if (content != null && !content.isBlank()) { // metadata中有正文时按原格式返回。
            return ((title != null && !title.isBlank()) ? title + "\n" : "") + content; // 标题和正文保持与AgentServiceImpl原逻辑一致。
        }
        return segment.text(); // content缺失时回退TextSegment.text()。
    }

    private Long readArticleId(Metadata metadata) { // 兼容解析metadata中的文章ID。
        Long articleId = readLongMetadata(metadata, "articleId"); // 优先使用当前真实写入字段articleId。
        if (articleId != null) { // 命中真实字段。
            return articleId; // 返回文章ID。
        }
        articleId = readLongMetadata(metadata, "article_id"); // 兼容蛇形字段。
        if (articleId != null) { // 命中兼容字段。
            return articleId; // 返回文章ID。
        }
        return readLongMetadata(metadata, "id"); // 最后兼容纯数字id，不读取vectorId避免误判。
    }

    private Long readLongMetadata(Metadata metadata, String key) { // 从metadata读取Long字段。
        if (metadata == null || key == null || !metadata.containsKey(key)) { // metadata或字段不存在。
            return null; // 返回null表示未解析到。
        }
        Object value = metadata.toMap().get(key); // 使用toMap读取原始值，兼容Long/String。
        if (value == null) { // 字段值为空。
            return null; // 返回null。
        }
        if (value instanceof Number number) { // metadata保留为数字类型时直接转换。
            return number.longValue(); // 返回long值。
        }
        String text = String.valueOf(value).trim(); // 兼容字符串数字。
        if (text.isEmpty()) { // 空字符串不是有效ID。
            return null; // 返回null。
        }
        try {
            return Long.parseLong(text); // 解析字符串ID。
        } catch (NumberFormatException e) {
            return null; // 非数字字段不作为articleId。
        }
    }

    private String readMetadataText(Metadata metadata, String key) { // 从metadata读取字符串字段。
        if (metadata == null || key == null || !metadata.containsKey(key)) { // metadata或字段不存在。
            return ""; // 字段缺失时返回空字符串。
        }
        Object value = metadata.toMap().get(key); // 使用toMap读取原始值。
        return value == null ? "" : String.valueOf(value); // 转成字符串返回。
    }

    private String buildRagResult(List<String> contents) { // 将多个RAG片段拼接为工具返回文本。
        if (contents == null || contents.isEmpty()) { // 无命中时不能返回null，避免模型误以为空上下文可编造。
            return "知识库中没有检索到与该问题相关的内容。"; // 固定无结果文案，system prompt会要求模型据此回答。
        }
        StringBuilder builder = new StringBuilder("以下是从知识库检索到的相关内容：\n\n"); // 明确告诉模型这些内容来自知识库。
        for (int i = 0; i < contents.size(); i++) { // 按顺序拼接Milvus返回的topK片段。
            builder.append("片段").append(i + 1).append("：\n"); // 给片段编号，便于模型引用和区分。
            builder.append(contents.get(i)).append("\n\n"); // 写入具体知识片段内容。
        }
        return builder.toString().trim(); // 去掉末尾空行，减少第二次模型调用的无效token。
    }
}
