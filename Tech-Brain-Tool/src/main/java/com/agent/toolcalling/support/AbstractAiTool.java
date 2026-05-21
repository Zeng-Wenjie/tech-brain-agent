package com.agent.toolcalling.support; // Tool Calling公共支持类包。

import com.agent.toolcalling.spi.AiTool; // 引入公共工具SPI。
import com.fasterxml.jackson.databind.JsonNode; // 工具参数读取基于JsonNode。
import com.fasterxml.jackson.databind.ObjectMapper; // 创建ObjectNode辅助构造JSON Schema。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造required数组等JSON数组结构。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造JSON Schema对象。

/**
 * AI工具抽象基类。
 *
 * <p>该类实现AiTool接口并提供通用参数读取与JSON Schema构造辅助方法，减少后续具体工具重复编写样板代码。</p>
 * <p>调用链规划为：具体业务Tool继承AbstractAiTool -> 使用getRequiredText等方法解析模型arguments -> 使用createObjectSchema等方法声明工具参数Schema -> ToolRegistry统一注册。</p>
 * <p>本类只提供工具实现辅助能力，不包含RAG、Milvus、AgentService、DeepSeek HTTP调用或任何具体业务逻辑。</p>
 */
public abstract class AbstractAiTool implements AiTool { // 后续具体工具可继承该类获得通用辅助方法。

    protected final ObjectMapper objectMapper = new ObjectMapper(); // 提供给子类构造JSON Schema和解析复杂参数时复用。

    protected String getRequiredText(JsonNode arguments, String fieldName) { // 读取必填文本参数。
        String value = getOptionalText(arguments, fieldName, null); // 先按可选文本读取，统一处理缺失和空白。
        if (value == null || value.isBlank()) { // 必填文本为空时直接拒绝执行工具。
            throw new IllegalArgumentException("工具参数 " + fieldName + " 不能为空"); // 给业务侧明确的参数错误信息。
        }
        return value; // 返回非空文本参数。
    }

    protected String getOptionalText(JsonNode arguments, String fieldName, String defaultValue) { // 读取可选文本参数。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments为空时无法读取字段。
            return defaultValue; // 返回默认值，避免空指针。
        }
        JsonNode valueNode = arguments.path(fieldName); // 从arguments中读取指定字段。
        if (valueNode.isMissingNode() || valueNode.isNull()) { // 字段不存在或显式为null。
            return defaultValue; // 返回默认值。
        }
        String value = valueNode.asText(); // 将字段转换为字符串。
        return value == null || value.isBlank() ? defaultValue : value; // 空白字符串按未提供处理。
    }

    protected int getOptionalInt(JsonNode arguments, String fieldName, int defaultValue) { // 读取可选整数参数。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments为空时无法读取字段。
            return defaultValue; // 返回默认整数值。
        }
        JsonNode valueNode = arguments.path(fieldName); // 从arguments中读取指定字段。
        if (valueNode.isMissingNode() || valueNode.isNull()) { // 字段不存在或显式为null。
            return defaultValue; // 返回默认整数值。
        }
        return valueNode.asInt(defaultValue); // 字段无法转为整数时使用默认值兜底。
    }

    protected ObjectNode createObjectSchema() { // 创建工具参数顶层object schema。
        ObjectNode schema = objectMapper.createObjectNode(); // 创建JSON对象。
        schema.put("type", "object"); // DeepSeek/OpenAI工具参数schema顶层通常是object。
        schema.set("properties", objectMapper.createObjectNode()); // 初始化properties，子类可继续追加字段。
        schema.set("required", objectMapper.createArrayNode()); // 初始化required，子类可继续追加必填字段。
        return schema; // 返回可继续编辑的schema对象。
    }

    protected ObjectNode createStringProperty(String description) { // 创建string类型参数schema。
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段schema对象。
        property.put("type", "string"); // 标记字段类型为string。
        property.put("description", description); // 写入给大模型看的字段说明。
        return property; // 返回字段schema。
    }

    protected ObjectNode createIntegerProperty(String description) { // 创建integer类型参数schema。
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段schema对象。
        property.put("type", "integer"); // 标记字段类型为integer。
        property.put("description", description); // 写入给大模型看的字段说明。
        return property; // 返回字段schema。
    }

    protected void addProperty(ObjectNode objectSchema, String fieldName, ObjectNode propertySchema, boolean required) { // 向object schema追加字段。
        JsonNode propertiesNode = objectSchema.get("properties"); // 先读取已有properties，避免重复覆盖子类已经追加的字段。
        ObjectNode properties = propertiesNode instanceof ObjectNode existingProperties
                ? existingProperties
                : objectMapper.createObjectNode(); // properties缺失时创建新对象，兼容子类手动传入的schema。
        objectSchema.set("properties", properties); // 确保顶层schema持有properties对象。
        properties.set(fieldName, propertySchema); // 添加字段schema。
        if (required) { // 需要标记必填时追加到required数组。
            JsonNode requiredNode = objectSchema.get("required"); // 先读取已有required，保留已登记的必填字段。
            ArrayNode requiredFields = requiredNode instanceof ArrayNode existingRequiredFields
                    ? existingRequiredFields
                    : objectMapper.createArrayNode(); // required缺失时创建数组，保证schema结构完整。
            objectSchema.set("required", requiredFields); // 确保顶层schema持有required数组。
            requiredFields.add(fieldName); // 添加必填字段名。
        }
    }
}
