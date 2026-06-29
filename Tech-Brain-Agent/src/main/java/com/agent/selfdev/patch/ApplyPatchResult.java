package com.agent.selfdev.patch;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * P10 应用 patch 的结构化结果。
 *
 * <p>适用场景：PatchApplyService 返回给 ApplyPatchTool，并最终作为 tool_call_log.result_json
 * 和聊天回答依据。结果只保存摘要，不保存 patch 全文。</p>
 *
 * <p>调用链：PatchApplyService 构造 ApplyPatchResult -> ApplyPatchTool 序列化 JSON ->
 * ToolCallingChatServiceImpl 写入 tool_call_log。</p>
 */
@Data
public class ApplyPatchResult {

    private String type = "patch_apply"; // 固定结果类型。
    private boolean success; // 是否成功。
    private String workspaceId; // workspace ID。
    private boolean dryRun; // 是否 dryRun。
    private boolean safetyPassed; // 安全校验是否通过。
    private String backupId; // 备份 ID，dryRun 或安全拒绝时为空。
    private boolean rollbackExecuted; // 失败时是否执行了回滚。
    private boolean rollbackSuccess; // 回滚是否成功。
    private List<String> changedFiles = new ArrayList<>(); // 全量变更文件。
    private List<String> addedFiles = new ArrayList<>(); // 新增文件。
    private List<String> modifiedFiles = new ArrayList<>(); // 修改文件。
    private List<String> deletedFiles = new ArrayList<>(); // 删除文件。
    private List<String> renamedFiles = new ArrayList<>(); // 重命名文件。
    private List<PatchRejectedFile> rejectedFiles = new ArrayList<>(); // 安全拒绝文件。
    private Long devLogId; // 对应 dev_action_log.id。
    private String message; // 面向调用方的简短说明。
    private String errorMsg; // 失败原因摘要。
}
