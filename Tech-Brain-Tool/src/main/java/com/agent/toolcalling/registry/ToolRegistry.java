package com.agent.toolcalling.registry; // Tool Calling工具注册中心包。

import com.agent.toolcalling.spi.AiTool; // 引入公共工具SPI。
import com.fasterxml.jackson.databind.ObjectMapper; // 用于构造DeepSeek tools JSON。
import com.fasterxml.jackson.databind.node.ArrayNode; // DeepSeek tools顶层数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // DeepSeek tools中的function对象。
import lombok.extern.slf4j.Slf4j; // 打印工具注册日志。
import org.springframework.stereotype.Component; // 注册为Spring Bean供后续Tool Calling服务注入。

import java.util.ArrayList; // 创建不可变前的工具列表副本。
import java.util.Collections; // 返回只读工具列表。
import java.util.LinkedHashMap; // 保持工具注册顺序，便于日志和调试稳定。
import java.util.List; // 接收Spring自动注入的所有AiTool Bean。
import java.util.Map; // 按工具名索引AiTool。

/**
 * AI工具注册中心。
 *
 * <p>该类由Spring自动收集所有AiTool Bean，并建立按工具名称查询的注册表。</p>
 * <p>调用链规划为：具体AiTool实现注册为Spring Bean -> ToolRegistry构造时收集并校验重复名称 -> Tool Calling服务调用buildToolsJson生成DeepSeek tools参数 -> 模型返回tool_call后按name获取工具执行。</p>
 * <p>当前步骤只提供通用注册能力，不接入RagSearchTool，不改变/api/ai/tool-chat现有流程，也不依赖Milvus、AgentService或数据库。</p>
 */
@Slf4j // 生成日志对象，用于输出注册结果。
@Component // 让Spring容器管理该注册中心。
public class ToolRegistry { // Tool Calling公共工具注册中心。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 公共模块自带JSON工具，避免依赖业务模块额外声明ObjectMapper Bean。

    private final List<AiTool> tools; // 保存所有已注册工具的只读列表。

    private final Map<String, AiTool> toolMap; // 按工具名快速查找工具。

    public ToolRegistry(List<AiTool> tools) { // Spring只需要自动注入所有AiTool Bean，当前没有工具时也能给出空列表。
        List<AiTool> safeTools = tools == null ? List.of() : new ArrayList<>(tools); // 没有任何AiTool时使用空列表，避免启动失败。
        this.tools = Collections.unmodifiableList(safeTools); // 对外只暴露只读列表，避免运行时被修改。
        this.toolMap = buildToolMap(safeTools); // 构建工具名到工具实例的索引。
        logRegisteredTools(); // 启动时打印注册结果，方便确认当前有哪些工具。
    }

    public List<AiTool> getAllTools() { // 获取所有已注册工具。
        return tools; // 返回只读工具列表。
    }

    public AiTool getTool(String name) { // 按工具名获取工具。
        return toolMap.get(name); // 找不到时返回null，由调用方决定如何处理未知工具。
    }

    public ArrayNode buildToolsJson() { // 构造DeepSeek chat/completions需要的tools数组。
        ArrayNode toolsJson = objectMapper.createArrayNode(); // 创建tools顶层数组。
        for (AiTool tool : tools) { // 遍历所有已注册工具。
            ObjectNode toolNode = objectMapper.createObjectNode(); // 创建单个tool对象。
            toolNode.put("type", "function"); // DeepSeek/OpenAI工具类型固定为function。
            ObjectNode functionNode = objectMapper.createObjectNode(); // 创建function描述对象。
            functionNode.put("name", tool.name()); // 写入工具名。
            functionNode.put("description", tool.description()); // 写入给大模型看的工具描述。
            functionNode.set("parameters", tool.parametersSchema()); // 写入工具参数JSON Schema。
            toolNode.set("function", functionNode); // 将function挂到tool节点。
            toolsJson.add(toolNode); // 加入tools数组。
        }
        return toolsJson; // 返回可直接写入DeepSeek请求体的tools JSON。
    }

    private Map<String, AiTool> buildToolMap(List<AiTool> tools) { // 构建工具名索引并校验重复名称。
        Map<String, AiTool> map = new LinkedHashMap<>(); // 使用LinkedHashMap保持注册顺序。
        for (AiTool tool : tools) { // 遍历Spring注入的工具列表。
            String toolName = tool.name(); // 读取工具名。
            if (map.containsKey(toolName)) { // 重复工具名会导致模型tool_call无法唯一分发。
                throw new IllegalStateException("Duplicate AI tool name: " + toolName); // 直接启动失败，避免静默覆盖。
            }
            map.put(toolName, tool); // 注册工具到索引中。
        }
        return Collections.unmodifiableMap(map); // 返回只读索引，避免运行时被改写。
    }

    private void logRegisteredTools() { // 打印工具注册情况。
        if (tools.isEmpty()) { // 当前步骤还没有接入具体AiTool时会走这里。
            log.info("[ToolRegistry] no tools registered"); // 明确说明没有工具，不影响启动。
            return; // 无工具时结束日志打印。
        }
        for (AiTool tool : tools) { // 遍历已注册工具。
            log.info("[ToolRegistry] registered tool: {}", tool.name()); // 打印每个工具名。
        }
    }
}
