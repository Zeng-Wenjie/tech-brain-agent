package com.agent.tool.selfdev;

import com.agent.selfdev.patch.ApplyPatchRequest;
import com.agent.selfdev.patch.ApplyPatchResult;
import com.agent.selfdev.patch.PatchApplyService;
import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.toolcalling.context.ToolCallingContextHolder;
import com.agent.toolcalling.context.ToolCallingRequestContext;
import com.agent.toolcalling.support.AbstractAiTool;
import com.agent.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P10 applyPatch AI Tool。
 *
 * <p>适用场景：当用户明确要求“应用 patch / 确认应用 patch”，并提供 workspaceId、patchContent 或 patchFilePath、
 * 以及确认标记 APPLY_PATCH 时，由 Tool Calling 调用本工具，把 patch 应用到 P9 sandbox workspace。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl -> ToolRegistry -> ApplyPatchTool.execute
 * -> SelfDevAccessGuard 校验 OWNER -> PatchApplyService.applyPatch。工具只负责应用 patch，不执行编译、不发布。</p>
 */
@Component
public class ApplyPatchTool extends AbstractAiTool {

    private static final String TOOL_NAME = "applyPatch"; // 工具注册名称。

    private final PatchApplyService patchApplyService; // P10 patch 应用服务。
    private final SelfDevAccessGuard accessGuard; // OWNER 权限守卫。

    public ApplyPatchTool(PatchApplyService patchApplyService, SelfDevAccessGuard accessGuard) {
        this.patchApplyService = patchApplyService; // 注入 patch 应用服务。
        this.accessGuard = accessGuard; // 注入 OWNER 守卫。
    }

    @Override
    public String name() {
        return TOOL_NAME; // 返回工具名。
    }

    @Override
    public String description() {
        return "在 P9 沙箱 workspace 或受控目标目录中应用 patch。应用前会校验白名单目录、禁止核心框架目录和生产配置文件，自动备份被修改文件，应用失败自动回滚。该工具只负责应用 patch，不执行编译、不发布。"; // 工具说明。
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 schema。
        addProperty(schema, "workspaceId", createStringProperty("必填，P9 创建的 sandbox workspace ID"), true); // workspaceId 必填。
        addProperty(schema, "workspacePath", createStringProperty("可选，sandbox workspace 相对路径或已校验路径。优先使用 workspaceId 解析"), false); // workspacePath 可选。
        addProperty(schema, "patchContent", createStringProperty("可选，patch/diff 内容。patchContent 和 patchFilePath 至少一个必填"), false); // patchContent 可选。
        addProperty(schema, "patchFilePath", createStringProperty("可选，patch 文件路径，必须在 sandbox workspace 内"), false); // patchFilePath 可选。
        addProperty(schema, "allowedDirectories", createStringArrayProperty("可选，本次允许修改的目录白名单。为空时使用系统默认白名单"), false); // allowedDirectories 可选。
        addProperty(schema, "dryRun", createBooleanProperty("是否只校验不真正应用，可选，默认 false"), false); // dryRun 可选。
        addProperty(schema, "backupEnabled", createBooleanProperty("是否应用前备份，可选，默认 true；第一版不允许真实应用时关闭"), false); // backupEnabled 可选。
        addProperty(schema, "rollbackOnFailure", createBooleanProperty("应用失败是否自动回滚，可选，默认 true；第一版不允许真实应用时关闭"), false); // rollbackOnFailure 可选。
        addProperty(schema, "maxChangedFiles", createIntegerProperty("最多允许变更文件数，可选，默认 50"), false); // maxChangedFiles 可选。
        addProperty(schema, "requireConfirm", createBooleanProperty("是否要求确认标记，可选，默认 true"), false); // requireConfirm 可选。
        addProperty(schema, "confirmToken", createStringProperty("确认标记。requireConfirm=true 时必须传 APPLY_PATCH"), false); // confirmToken 可选。
        return schema; // 返回 schema。
    }

    @Override
    public String execute(JsonNode arguments) {
        Long userId = resolveUserId(); // 获取当前用户 ID。
        String username = UserContext.getUsername(); // 获取当前用户名。
        accessGuard.assertOwner(userId, username); // P10 高风险写操作仅 OWNER 可触发。
        ApplyPatchRequest request = toRequest(arguments); // 解析工具参数。
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 读取 Tool Calling 上下文。
        request.setUserId(userId); // 写入用户 ID。
        request.setConversationId(context == null ? null : context.getConversationId()); // 写入会话 ID。
        request.setTraceId(context == null ? null : context.getTraceId()); // 写入 traceId。
        ApplyPatchResult result = patchApplyService.applyPatch(request); // 执行 P10 主流程。
        return toJson(result); // 返回结构化 JSON。
    }

    private ApplyPatchRequest toRequest(JsonNode arguments) {
        ApplyPatchRequest request = new ApplyPatchRequest(); // 创建请求对象。
        request.setWorkspaceId(getOptionalText(arguments, "workspaceId", null)); // workspaceId。
        request.setWorkspacePath(getOptionalText(arguments, "workspacePath", null)); // workspacePath。
        request.setPatchContent(getOptionalText(arguments, "patchContent", null)); // patchContent。
        request.setPatchFilePath(getOptionalText(arguments, "patchFilePath", null)); // patchFilePath。
        request.setAllowedDirectories(readStringArray(arguments, "allowedDirectories")); // allowedDirectories。
        request.setDryRun(readOptionalBoolean(arguments, "dryRun")); // dryRun。
        request.setBackupEnabled(readOptionalBoolean(arguments, "backupEnabled")); // backupEnabled。
        request.setRollbackOnFailure(readOptionalBoolean(arguments, "rollbackOnFailure")); // rollbackOnFailure。
        request.setMaxChangedFiles(readOptionalInteger(arguments, "maxChangedFiles")); // maxChangedFiles。
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
        property.set("items", createStringProperty("workspace 相对目录")); // 设置数组元素类型。
        return property; // 返回字段 schema。
    }

    private Long resolveUserId() {
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 优先读取 Tool 上下文。
        if (context != null && context.getUserId() != null) {
            return context.getUserId(); // 返回上下文用户。
        }
        return UserContext.getUserId(); // 兜底读取登录线程上下文。
    }

    private String toJson(ApplyPatchResult result) {
        try {
            return objectMapper.writeValueAsString(result); // 序列化工具结果。
        } catch (Exception e) {
            return "{\"type\":\"patch_apply\",\"success\":false,\"errorMsg\":\"Patch 结果序列化失败\"}"; // 序列化失败兜底。
        }
    }
}
