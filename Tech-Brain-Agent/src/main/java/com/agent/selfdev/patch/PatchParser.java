package com.agent.selfdev.patch;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * P10 unified diff 解析器。
 *
 * <p>适用场景：在真正应用 patch 前，先从 patchContent 中提取新增、修改、删除、重命名和所有涉及文件，
 * 为后续白名单、安全黑名单、备份和回滚提供稳定输入。</p>
 *
 * <p>调用链：PatchApplyService.applyPatch -> PatchParser.parse -> PatchParseResult
 * -> PatchSafetyGuard / PatchBackupService。解析器不读写磁盘，不执行 git 命令。</p>
 */
@Component
public class PatchParser {

    private static final String TYPE_ADDED = "ADDED"; // 新增文件类型。
    private static final String TYPE_MODIFIED = "MODIFIED"; // 修改文件类型。
    private static final String TYPE_DELETED = "DELETED"; // 删除文件类型。
    private static final String TYPE_RENAMED = "RENAMED"; // 重命名文件类型。

    public PatchParseResult parse(String patchContent) {
        PatchParseResult result = new PatchParseResult(); // 创建解析结果。
        if (patchContent == null || patchContent.trim().isEmpty()) {
            result.setSuccess(false); // 空 patch 解析失败。
            result.setMessage("patch 内容不能为空。"); // 设置失败原因。
            return result; // 返回失败。
        }
        try {
            parseInternal(patchContent, result); // 执行逐行解析。
            if (result.getChanges().isEmpty() || result.getAllChangedFiles().isEmpty()) {
                result.setSuccess(false); // 没有识别到文件时失败。
                result.setMessage("无法解析 patch 涉及文件。"); // 设置失败原因。
                return result; // 返回失败。
            }
            result.setSuccess(true); // 标记成功。
            result.setMessage("patch 文件清单解析成功。"); // 设置成功说明。
            return result; // 返回成功。
        } catch (Exception e) {
            result.setSuccess(false); // 异常时解析失败。
            result.setMessage("无法解析 patch 涉及文件: " + e.getMessage()); // 返回安全错误摘要。
            return result; // 返回失败。
        }
    }

    private void parseInternal(String patchContent, PatchParseResult result) {
        String[] lines = patchContent.split("\\R"); // 按任意换行符切分。
        PatchFileChange current = null; // 当前 diff --git 块。
        Set<String> allFiles = new LinkedHashSet<>(); // 去重且保持出现顺序。
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                current = finishCurrent(result, current, allFiles); // 保存上一个文件块。
                current = parseDiffGitLine(line); // 解析新的 diff 文件块。
                continue; // 继续下一行。
            }
            if (current == null && (line.startsWith("--- ") || line.startsWith("+++ "))) {
                current = new PatchFileChange(); // 支持缺少 diff --git 的简单 unified diff。
                current.setChangeType(TYPE_MODIFIED); // 先按修改处理，后续根据 /dev/null 修正。
            }
            if (current == null) {
                continue; // 未进入文件块时忽略其它行。
            }
            if (line.startsWith("new file mode")) {
                current.setChangeType(TYPE_ADDED); // 标记新增文件。
                continue; // 继续下一行。
            }
            if (line.startsWith("deleted file mode")) {
                current.setChangeType(TYPE_DELETED); // 标记删除文件。
                continue; // 继续下一行。
            }
            if (line.startsWith("rename from ")) {
                current.setOldPath(cleanPatchPath(line.substring("rename from ".length()))); // 记录重命名前路径。
                current.setChangeType(TYPE_RENAMED); // 标记重命名。
                continue; // 继续下一行。
            }
            if (line.startsWith("rename to ")) {
                current.setNewPath(cleanPatchPath(line.substring("rename to ".length()))); // 记录重命名后路径。
                current.setChangeType(TYPE_RENAMED); // 标记重命名。
                continue; // 继续下一行。
            }
            if (line.startsWith("--- ")) {
                String path = line.substring(4).trim(); // 读取旧路径。
                if ("/dev/null".equals(path)) {
                    current.setOldPath(null); // /dev/null 表示新增文件。
                    current.setChangeType(TYPE_ADDED); // 标记新增。
                } else {
                    current.setOldPath(cleanPatchPath(path)); // 清洗旧路径。
                }
                continue; // 继续下一行。
            }
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim(); // 读取新路径。
                if ("/dev/null".equals(path)) {
                    current.setNewPath(null); // /dev/null 表示删除文件。
                    current.setChangeType(TYPE_DELETED); // 标记删除。
                } else {
                    current.setNewPath(cleanPatchPath(path)); // 清洗新路径。
                }
            }
        }
        finishCurrent(result, current, allFiles); // 保存最后一个文件块。
        result.getAllChangedFiles().addAll(allFiles); // 回填全量文件列表。
    }

    private PatchFileChange finishCurrent(PatchParseResult result,
                                          PatchFileChange current,
                                          Set<String> allFiles) {
        if (current == null) {
            return null; // 没有当前块时无需处理。
        }
        normalizeChange(current); // 补全路径和类型。
        if (current.getOldPath() == null && current.getNewPath() == null) {
            return null; // 没有有效路径时丢弃。
        }
        result.getChanges().add(current); // 添加文件变更。
        addByType(result, current); // 添加到 added/modified/deleted/renamed。
        addIfPresent(allFiles, current.getOldPath()); // 旧路径也要参与安全校验和备份。
        addIfPresent(allFiles, current.getNewPath()); // 新路径也要参与安全校验和备份。
        return null; // 上一个块已结束。
    }

    private PatchFileChange parseDiffGitLine(String line) {
        String[] parts = line.trim().split("\\s+"); // diff --git a/x b/y 按空白切分。
        if (parts.length < 4) {
            throw new IllegalArgumentException("diff --git 行格式不正确。"); // 格式不对直接失败。
        }
        PatchFileChange change = new PatchFileChange(); // 创建变更对象。
        change.setOldPath(cleanPatchPath(parts[2])); // 读取 a/path。
        change.setNewPath(cleanPatchPath(parts[3])); // 读取 b/path。
        change.setChangeType(TYPE_MODIFIED); // 默认按修改处理。
        return change; // 返回当前变更。
    }

    private void normalizeChange(PatchFileChange change) {
        if (TYPE_RENAMED.equals(change.getChangeType())) {
            return; // 重命名保留 old/new。
        }
        if (change.getOldPath() == null && change.getNewPath() != null) {
            change.setChangeType(TYPE_ADDED); // 只有新路径表示新增。
            return; // 完成归一化。
        }
        if (change.getNewPath() == null && change.getOldPath() != null) {
            change.setChangeType(TYPE_DELETED); // 只有旧路径表示删除。
            return; // 完成归一化。
        }
        if (change.getChangeType() == null || change.getChangeType().isBlank()) {
            change.setChangeType(TYPE_MODIFIED); // 缺失类型时按修改处理。
        }
    }

    private void addByType(PatchParseResult result, PatchFileChange change) {
        String type = change.getChangeType() == null ? TYPE_MODIFIED : change.getChangeType().toUpperCase(Locale.ROOT); // 类型兜底。
        String primaryPath = change.getNewPath() != null ? change.getNewPath() : change.getOldPath(); // 对外展示优先使用新路径。
        if (TYPE_ADDED.equals(type)) {
            addIfPresent(result.getAddedFiles(), primaryPath); // 新增文件。
            return; // 结束。
        }
        if (TYPE_DELETED.equals(type)) {
            addIfPresent(result.getDeletedFiles(), change.getOldPath()); // 删除文件使用旧路径。
            return; // 结束。
        }
        if (TYPE_RENAMED.equals(type)) {
            addIfPresent(result.getRenamedFiles(), primaryPath); // 重命名展示新路径。
            return; // 结束。
        }
        addIfPresent(result.getModifiedFiles(), primaryPath); // 默认修改文件。
    }

    private String cleanPatchPath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("patch 文件路径为空。"); // 空路径拒绝。
        }
        String path = rawPath.trim().replace('\\', '/'); // 统一为 /。
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1); // 兼容带引号路径。
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2); // 去掉 git diff 的 a/ b/ 前缀。
        }
        while (path.startsWith("./")) {
            path = path.substring(2); // 去掉相对路径前缀。
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("patch 不允许绝对路径: " + path); // 拒绝绝对路径。
        }
        Path normalized = Path.of(path).normalize(); // 标准化路径。
        String normalizedPath = normalized.toString().replace('\\', '/'); // 转回统一分隔符。
        if (normalized.isAbsolute() || normalizedPath.startsWith("../") || normalizedPath.equals("..")
                || normalizedPath.contains("/../")) {
            throw new IllegalArgumentException("patch 不允许路径穿越: " + path); // 拒绝路径穿越。
        }
        if (normalizedPath.isBlank() || ".".equals(normalizedPath)) {
            throw new IllegalArgumentException("patch 文件路径无效。"); // 拒绝空路径。
        }
        return normalizedPath; // 返回 workspace 相对路径。
    }

    private void addIfPresent(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value); // Set 去重添加。
        }
    }

    private void addIfPresent(java.util.List<String> target, String value) {
        if (value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value); // List 去重添加。
        }
    }
}
