package com.agent.tool.rag; // Agent模块中的具体RAG工具包，承载依赖业务Service的工具实现。

import com.agent.service.AgentService; // 复用已有AgentService中的Milvus RAG检索方法。
import com.agent.toolcalling.support.AbstractAiTool; // 继承Tech-Brain-Tool提供的通用工具辅助基类。
import com.fasterxml.jackson.databind.JsonNode; // 接收模型返回的工具arguments。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造ragSearch工具参数JSON Schema。
import lombok.extern.slf4j.Slf4j; // 打印ToolChat验收所需的RAG检索日志。
import org.springframework.beans.factory.annotation.Value; // 读取Milvus collection名称用于日志核对。
import org.springframework.stereotype.Component; // 注册为Spring Bean，供ToolRegistry自动发现。

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

    @Value("${milvus.collection-name:article_vector}") // collection名称只用于日志，实际检索仍由AgentService内部配置决定。
    private String milvusCollectionName; // 当前文章向量所在Milvus collection，默认article_vector。

    public RagSearchTool(AgentService agentService) { // 构造器注入业务RAG服务。
        this.agentService = agentService; // 保存AgentService，execute时调用现有searchRagContents方法。
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
        List<String> contents = agentService.searchRagContents(query); // 复用已有Milvus检索逻辑获取知识片段。
        int resultCount = contents == null ? 0 : contents.size(); // 统计真实检索结果数量。
        String result = buildRagResult(contents); // 将知识片段拼接成模型可读的tool result。
        log.info("[ToolChat] real rag result count: {}", resultCount); // 打印真实RAG命中数量。
        log.info("[ToolChat] real rag result: {}", result); // 打印真实RAG结果，方便接口验收截图。
        return result; // 返回给AiToolChatServiceImpl，随后作为tool message发送给DeepSeek。
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
