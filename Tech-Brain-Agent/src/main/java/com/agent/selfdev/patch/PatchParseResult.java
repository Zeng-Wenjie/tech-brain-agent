package com.agent.selfdev.patch;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * patch 解析结果。
 *
 * <p>适用场景：承载 PatchParser 从 unified diff 中提取出的 added/modified/deleted/renamed/allChangedFiles，
 * 以及解析失败原因。</p>
 *
 * <p>调用链：PatchApplyService -> PatchParser.parse -> PatchParseResult -> 后续安全校验和备份。</p>
 */
@Data
public class PatchParseResult {

    private boolean success; // 解析是否成功。
    private String message; // 解析说明或失败原因。
    private List<PatchFileChange> changes = new ArrayList<>(); // 文件级变更列表。
    private List<String> addedFiles = new ArrayList<>(); // 新增文件。
    private List<String> modifiedFiles = new ArrayList<>(); // 修改文件。
    private List<String> deletedFiles = new ArrayList<>(); // 删除文件。
    private List<String> renamedFiles = new ArrayList<>(); // 重命名文件。
    private List<String> allChangedFiles = new ArrayList<>(); // 所有涉及路径，重命名会包含 old/new。
}
