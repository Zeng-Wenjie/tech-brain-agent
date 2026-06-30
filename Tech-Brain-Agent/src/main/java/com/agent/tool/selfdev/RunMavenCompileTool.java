package com.agent.tool.selfdev;

import com.agent.selfdev.maven.MavenCompileRequest;
import com.agent.selfdev.maven.MavenCompileResult;
import com.agent.selfdev.maven.MavenCompileService;
import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.toolcalling.context.ToolCallingContextHolder;
import com.agent.toolcalling.context.ToolCallingRequestContext;
import com.agent.toolcalling.support.AbstractAiTool;
import com.agent.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P11 runMavenCompile AI Tool。
 *
 * <p>适用场景：当用户明确要求“编译验证 / Maven compile / 后端编译”，并提供 P9 workspaceId 与确认标记
 * RUN_COMPILE 时，由 Tool Calling 调用本工具，在 sandbox workspace 内执行 Maven compile。工具只负责验证编译，
 * 不调用 Claude Code、不修代码、不生成 patch、不应用 patch、不发布、不回滚。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl -> ToolRegistry -> RunMavenCompileTool.execute
 * -> SelfDevAccessGuard 校验 OWNER -> MavenCompileService.runCompile。
 * MavenCompileService 会再次通过 SandboxWorkspaceGuard 保证命令工作目录只能是 P9 sandbox workspace。</p>
 */
@Component
public class RunMavenCompileTool extends AbstractAiTool {

    private static final String TOOL_NAME = "runMavenCompile"; // 工具注册名称。

    private final MavenCompileService mavenCompileService; // P11 编译验证服务。
    private final SelfDevAccessGuard accessGuard; // OWNER 权限守卫。

    public RunMavenCompileTool(MavenCompileService mavenCompileService, SelfDevAccessGuard accessGuard) {
        this.mavenCompileService = mavenCompileService; // 注入 P11 编译验证服务。
        this.accessGuard = accessGuard; // 注入 OWNER 守卫。
    }

    @Override
    public String name() {
        return TOOL_NAME; // 返回工具名。
    }

    @Override
    public String description() {
        return "在 P9 sandbox workspace 内执行 Maven compile 编译验证。该工具只允许在 sandbox workspace 中运行，"
                + "不允许在 sourceRepoDir 或线上运行目录执行。工具会捕获编译输出、错误摘要、exitCode 和耗时，"
                + "并将结果保存到开发行为日志。"; // 工具说明。
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建 schema。
        addProperty(schema, "workspaceId", createStringProperty("必填，P9 创建的 sandbox workspace ID"), true); // workspaceId 必填。
        addProperty(schema, "workspacePath", createStringProperty("可选，sandbox workspace 路径。优先通过 workspaceId 解析"), false); // workspacePath 可选。
        addProperty(schema, "module", createStringProperty("可选，Maven 模块名，例如 Tech-Brain-Agent。为空时在 workspace 根目录执行"), false); // module 可选。
        addProperty(schema, "skipTests", createBooleanProperty("是否跳过测试，可选，默认 true"), false); // skipTests 可选。
        addProperty(schema, "profiles", createStringArrayProperty("可选，Maven profiles，例如 dev、local"), false); // profiles 可选。
        addProperty(schema, "extraArgs", createStringArrayProperty("可选，仅允许安全白名单内的 Maven 参数"), false); // extraArgs 可选。
        addProperty(schema, "timeoutSeconds", createIntegerProperty("编译超时时间，可选，默认 180，最大 600"), false); // timeoutSeconds 可选。
        addProperty(schema, "requireConfirm", createBooleanProperty("是否要求确认标记，可选，默认 true"), false); // requireConfirm 可选。
        addProperty(schema, "confirmToken", createStringProperty("确认标记。requireConfirm=true 时必须传 RUN_COMPILE"), false); // confirmToken 可选。
        return schema; // 返回 schema。
    }

    @Override
    public String execute(JsonNode arguments) {
        Long userId = resolveUserId(); // 读取当前用户 ID。
        String username = UserContext.getUsername(); // 读取当前用户名。
        accessGuard.assertOwner(userId, username); // P11 命令执行能力只允许 OWNER。
        MavenCompileRequest request = toRequest(arguments); // 解析工具参数。
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 读取 Tool Calling 上下文。
        request.setUserId(userId); // 写入用户 ID。
        request.setConversationId(context == null ? null : context.getConversationId()); // 写入会话 ID。
        request.setTraceId(context == null ? null : context.getTraceId()); // 写入 traceId。
        MavenCompileResult result = mavenCompileService.runCompile(request); // 执行 P11 编译验证。
        return toJson(result); // 返回结构化 JSON。
    }

    private MavenCompileRequest toRequest(JsonNode arguments) {
        MavenCompileRequest request = new MavenCompileRequest(); // 创建请求对象。
        request.setWorkspaceId(getOptionalText(arguments, "workspaceId", null)); // workspaceId。
        request.setWorkspacePath(getOptionalText(arguments, "workspacePath", null)); // workspacePath。
        request.setModule(getOptionalText(arguments, "module", null)); // module。
        request.setSkipTests(readOptionalBoolean(arguments, "skipTests")); // skipTests。
        request.setProfiles(readStringArray(arguments, "profiles")); // profiles。
        request.setExtraArgs(readStringArray(arguments, "extraArgs")); // extraArgs。
        request.setTimeoutSeconds(readOptionalInteger(arguments, "timeoutSeconds")); // timeoutSeconds。
        request.setRequireConfirm(readOptionalBoolean(arguments, "requireConfirm")); // requireConfirm。
        request.setConfirmToken(getOptionalText(arguments, "confirmToken", null)); // confirmToken。
        return request; // 返回请求。
    }

    private List<String> readStringArray(JsonNode arguments, String fieldName) {
        JsonNode node = arguments == null ? null : arguments.path(fieldName); // 读取数组节点。
        if (node == null || !node.isArray()) {
            return null; // 缺失或不是数组时为空。
        }
        List<String> values = new ArrayList<>(); // 收集字符串。
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText()); // 添加非空字符串。
            }
        }
        return values; // 返回列表。
    }

    private Boolean readOptionalBoolean(JsonNode arguments, String fieldName) {
        JsonNode node = arguments == null ? null : arguments.path(fieldName); // 读取布尔节点。
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null; // 未提供时返回 null。
        }
        return node.asBoolean(); // 返回布尔值。
    }

    private Integer readOptionalInteger(JsonNode arguments, String fieldName) {
        JsonNode node = arguments == null ? null : arguments.path(fieldName); // 读取整数节点。
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null; // 未提供时返回 null。
        }
        return node.asInt(); // 返回整数。
    }

    private ObjectNode createBooleanProperty(String description) {
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段 schema。
        property.put("type", "boolean"); // 设置 boolean 类型。
        property.put("description", description); // 设置说明。
        return property; // 返回字段 schema。
    }

    private ObjectNode createStringArrayProperty(String description) {
        ObjectNode property = objectMapper.createObjectNode(); // 创建字段 schema。
        property.put("type", "array"); // 设置数组类型。
        property.put("description", description); // 设置说明。
        property.set("items", createStringProperty("Maven 安全参数")); // 设置数组元素类型。
        return property; // 返回字段 schema。
    }

    private Long resolveUserId() {
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 优先读取 Tool 上下文。
        if (context != null && context.getUserId() != null) {
            return context.getUserId(); // 返回上下文用户。
        }
        return UserContext.getUserId(); // 兜底读取登录线程上下文。
    }

    private String toJson(MavenCompileResult result) {
        try {
            return objectMapper.writeValueAsString(result); // 序列化工具结果。
        } catch (Exception e) {
            return "{\"type\":\"maven_compile\",\"success\":false,\"errorMsg\":\"Maven compile 结果序列化失败\"}"; // 序列化失败兜底。
        }
    }
}
