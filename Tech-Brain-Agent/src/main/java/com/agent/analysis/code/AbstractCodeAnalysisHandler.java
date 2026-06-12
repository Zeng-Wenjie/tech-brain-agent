package com.agent.analysis.code; // 项目代码内部分析器包。

import com.fasterxml.jackson.databind.JsonNode; // 工具参数读取基于 JsonNode。
import com.fasterxml.jackson.databind.ObjectMapper; // 创建内部分析器 JSON Schema 和结果对象。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造 required 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造 object schema。

/**
 * analyzeCode 内部分析器抽象基类。
 *
 * <p>适用场景：旧 P5 专项 Tool 改造成内部 Analyzer 后，仍需要复用原 AbstractAiTool 里的参数读取、
 * JSON Schema 构造和 ObjectMapper 辅助能力，但不能再继承 AbstractAiTool 或实现 AiTool，否则会继续被
 * ToolRegistry 暴露为外部工具。本类提供同款辅助方法，同时只实现 CodeAnalysisHandler。</p>
 *
 * <p>调用链：AnalyzeCodeTool 注入多个 CodeAnalysisHandler -> 具体 Analyzer 继承本类复用 getOptionalText、
 * createObjectSchema、addProperty 等方法 -> 具体 Analyzer 的 execute(arguments) 保留原静态分析核心逻辑
 * -> analyze(arguments) 委托 execute(arguments) 返回原内部 result。</p>
 *
 * <p>边界说明：本类不是 AI Tool，不提供 ToolRegistry 注册契约；它只服务 analyzeCode 内部分发，不负责
 * tool_call_log、DeepSeek 调用、数据库访问或前端交互。</p>
 */
public abstract class AbstractCodeAnalysisHandler implements CodeAnalysisHandler { // 内部 Analyzer 公共基类。

    protected final ObjectMapper objectMapper = new ObjectMapper(); // 提供给子类构造 JSON 和读取复杂参数时复用。

    @Override
    public String analyze(JsonNode arguments) { // CodeAnalysisHandler 标准入口。
        return execute(arguments); // 复用旧专项 Tool 保留下来的 execute 核心逻辑。
    }

    public String name() { // 内部分析器名称，兼容旧方法签名但不作为 Tool 名暴露。
        return analysisType().name(); // 默认返回 analysisType。
    }

    public String description() { // 内部分析器描述，兼容旧方法签名。
        return "analyzeCode 内部分析器：" + analysisType().name(); // 默认描述。
    }

    public ObjectNode parametersSchema() { // 内部参数 schema，兼容旧方法签名。
        return createObjectSchema(); // 默认空 object schema。
    }

    public abstract String execute(JsonNode arguments); // 具体分析器保留原 execute 分析逻辑。

    protected String getRequiredText(JsonNode arguments, String fieldName) { // 读取必填文本参数。
        String value = getOptionalText(arguments, fieldName, null); // 先按可选文本读取。
        if (value == null || value.isBlank()) { // 缺失或空白视为非法。
            throw new IllegalArgumentException("工具参数 " + fieldName + " 不能为空"); // 复用原工具参数错误文案。
        }
        return value; // 返回非空文本。
    }

    protected String getOptionalText(JsonNode arguments, String fieldName, String defaultValue) { // 读取可选文本参数。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments 为空时返回默认值。
            return defaultValue; // 兜底。
        }
        JsonNode valueNode = arguments.path(fieldName); // 读取字段。
        if (valueNode.isMissingNode() || valueNode.isNull()) { // 字段不存在或为 null。
            return defaultValue; // 返回默认值。
        }
        String value = valueNode.asText(); // 转为字符串。
        return value == null || value.isBlank() ? defaultValue : value; // 空白按未提供处理。
    }

    protected int getOptionalInt(JsonNode arguments, String fieldName, int defaultValue) { // 读取可选整数参数。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments 为空。
            return defaultValue; // 返回默认值。
        }
        JsonNode valueNode = arguments.path(fieldName); // 读取字段。
        if (valueNode.isMissingNode() || valueNode.isNull()) { // 字段不存在或为 null。
            return defaultValue; // 返回默认值。
        }
        return valueNode.asInt(defaultValue); // 不能转整数时使用默认值。
    }

    protected ObjectNode createObjectSchema() { // 创建 object schema。
        ObjectNode schema = objectMapper.createObjectNode(); // 创建 JSON 对象。
        schema.put("type", "object"); // 顶层类型固定 object。
        schema.set("properties", objectMapper.createObjectNode()); // 初始化 properties。
        schema.set("required", objectMapper.createArrayNode()); // 初始化 required。
        return schema; // 返回 schema。
    }

    protected ObjectNode createStringProperty(String description) { // 创建 string 字段 schema。
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段对象。
        property.put("type", "string"); // 字段类型。
        property.put("description", description); // 字段说明。
        return property; // 返回字段 schema。
    }

    protected ObjectNode createIntegerProperty(String description) { // 创建 integer 字段 schema。
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段对象。
        property.put("type", "integer"); // 字段类型。
        property.put("description", description); // 字段说明。
        return property; // 返回字段 schema。
    }

    protected void addProperty(ObjectNode objectSchema, String fieldName, ObjectNode propertySchema, boolean required) { // 向 schema 添加字段。
        JsonNode propertiesNode = objectSchema.get("properties"); // 读取 properties。
        ObjectNode properties = propertiesNode instanceof ObjectNode existingProperties
                ? existingProperties
                : objectMapper.createObjectNode(); // properties 缺失时创建。
        objectSchema.set("properties", properties); // 确保 schema 持有 properties。
        properties.set(fieldName, propertySchema); // 添加字段 schema。
        if (required) { // 必填字段才写入 required。
            JsonNode requiredNode = objectSchema.get("required"); // 读取 required。
            ArrayNode requiredFields = requiredNode instanceof ArrayNode existingRequiredFields
                    ? existingRequiredFields
                    : objectMapper.createArrayNode(); // required 缺失时创建数组。
            objectSchema.set("required", requiredFields); // 确保 schema 持有 required。
            requiredFields.add(fieldName); // 追加必填字段。
        }
    }
}
