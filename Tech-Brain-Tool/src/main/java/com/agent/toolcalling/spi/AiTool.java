package com.agent.toolcalling.spi; // Tool Calling公共SPI包。

import com.fasterxml.jackson.databind.JsonNode; // 模型返回的工具参数统一用JsonNode承载。
import com.fasterxml.jackson.databind.node.ObjectNode; // 工具参数JSON Schema统一用ObjectNode描述。

/**
 * AI工具公共接口。
 *
 * <p>该接口定义一个可被大模型调用的后端工具需要暴露的最小契约，包括工具名、工具描述、参数Schema和真正执行逻辑。</p>
 * <p>调用链规划为：ToolRegistry收集所有AiTool实现 -> Tool Calling服务把parametersSchema转换为DeepSeek tools JSON -> 模型返回tool_call -> 业务侧按name找到AiTool并调用execute(arguments)。</p>
 * <p>本接口只描述通用Tool Calling能力，不依赖Tech-Brain-Agent、Milvus、AgentService或任何具体业务工具。</p>
 */
public interface AiTool { // 所有公共Tool Calling工具实现都应实现该接口。

    String name(); // 返回工具名称，例如ragSearch、crawlWebsite、runTavernSimulation。

    String description(); // 返回给大模型看的工具描述，用于帮助模型判断何时调用该工具。

    ObjectNode parametersSchema(); // 返回工具参数JSON Schema，最终会写入DeepSeek tools.function.parameters。

    String execute(JsonNode arguments); // 执行工具逻辑，arguments来自模型tool_call.function.arguments解析结果。
}
