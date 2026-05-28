package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.ToolCallLogExportRequest;
import com.agent.entity.dto.ToolCallLogPageRequest;
import com.agent.entity.dto.ToolCallRagHitStatsRequest;
import com.agent.entity.dto.ToolCallStatsRequest;
import com.agent.entity.vo.ToolCallLogVO;
import com.agent.entity.vo.ToolCallRagHitStatsVO;
import com.agent.entity.vo.ToolCallStatsVO;
import com.agent.service.ToolCallLogService;
import com.agent.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工具调用日志后台查询接口。
 *
 * <p>适用场景：后台排查 Tool Calling 工具调用问题时，按用户、会话、traceId、工具名、
 * 执行状态和时间范围查询 tool_call_log，也可以按工具名称查看调用量、失败率、平均耗时、RAG 命中率和 CSV 导出。</p>
 * <p>调用链：前端后台页面或接口调试工具 -> ToolCallLogController -> ToolCallLogService
 * -> ToolCallLogMapper -> tool_call_log。</p>
 * <p>边界说明：本 Controller 只提供只读查询接口，不修改数据库结构，不写入日志，
 * 不改变 /chat/message、summary_result、ragSearch、summarizeArticle 或长期记忆链路。</p>
 */
@RestController // 注册为 Spring MVC 控制器。
@RequestMapping("/api/tool-log") // 工具调用日志后台查询路径。
@Tag(name = "工具调用日志接口")
public class ToolCallLogController { // 工具调用日志查询 Controller。

    private static final DateTimeFormatter EXPORT_FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); // CSV 文件名时间格式。
    private static final DateTimeFormatter EXPORT_CELL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // CSV 时间单元格格式。

    @Autowired
    private ToolCallLogService toolCallLogService; // 注入工具调用日志服务。

    @Operation(summary = "分页查询工具调用日志")
    @GetMapping("/page")
    public Result<PageDTO<ToolCallLogVO>> page(@ParameterObject ToolCallLogPageRequest request) { // 查询当前用户自己的工具调用日志分页列表。
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 没有登录用户时拒绝查询。
            return Result.error(HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
        }
        ToolCallLogPageRequest safeRequest = request == null ? new ToolCallLogPageRequest() : request; // 请求为空时使用默认分页参数。
        safeRequest.setUserId(currentUserId); // 强制覆盖为当前用户 ID，禁止普通用户查询其它用户日志。
        return Result.success(toolCallLogService.pageToolCallLogs(safeRequest)); // 返回项目统一分页结果。
    }

    @Operation(summary = "按工具名统计工具调用情况")
    @GetMapping("/stats/tool")
    public Result<List<ToolCallStatsVO>> statByToolName(@ParameterObject ToolCallStatsRequest request) { // 查询当前用户自己的工具调用统计数据。
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 没有登录用户时拒绝统计。
            return Result.error(HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
        }
        ToolCallStatsRequest safeRequest = request == null ? new ToolCallStatsRequest() : request; // 请求为空时按当前用户做无筛选统计。
        return Result.success(toolCallLogService.statByToolName(safeRequest)); // 返回按工具名称分组后的统计结果。
    }

    @Operation(summary = "统计 RAG 工具命中率")
    @GetMapping("/stats/rag-hit")
    public Result<ToolCallRagHitStatsVO> statRagHit(@ParameterObject ToolCallRagHitStatsRequest request) { // 查询当前用户自己的 ragSearch 命中率统计数据。
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 没有登录用户时拒绝统计。
            return Result.error(HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
        }
        ToolCallRagHitStatsRequest safeRequest = request == null ? new ToolCallRagHitStatsRequest() : request; // 请求为空时按当前用户做无筛选 RAG 统计。
        return Result.success(toolCallLogService.statRagHit(safeRequest)); // 返回 ragSearch 命中率统计结果。
    }

    @Operation(summary = "导出工具调用日志 CSV")
    @GetMapping("/export")
    public void export(@ParameterObject ToolCallLogExportRequest request,
                       HttpServletResponse response) throws IOException { // 按当前筛选条件导出当前用户自己的工具调用日志。
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 没有登录用户时拒绝导出。
            writeUnauthorizedResponse(response); // 写入未登录 JSON 响应。
            return; // 结束导出流程。
        }
        ToolCallLogExportRequest safeRequest = request == null ? new ToolCallLogExportRequest() : request; // 请求为空时按当前用户无筛选导出。
        List<ToolCallLogVO> exportLogs = toolCallLogService.listForExport(safeRequest); // 查询当前用户可导出的日志，Service 会限制最多 5000 条。
        writeCsvResponse(response, exportLogs, Boolean.TRUE.equals(safeRequest.getIncludeDetail())); // 写入 UTF-8 BOM CSV 响应。
    }

    @Operation(summary = "查询工具调用日志详情")
    @GetMapping("/{id}")
    public Result<ToolCallLogVO> detail(@PathVariable("id") Long id) { // 查询当前用户自己的单条工具调用日志详情。
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 没有登录用户时拒绝查询。
            return Result.error(HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
        }
        ToolCallLogVO detail = toolCallLogService.getToolCallLogDetail(id, currentUserId); // 按日志 ID 和当前用户 ID 查询详情。
        if (detail == null) { // 不存在或不属于当前用户时统一按不存在处理。
            return Result.error(HttpServletResponse.SC_NOT_FOUND, "工具调用日志不存在"); // 避免泄露其它用户日志是否存在。
        }
        return Result.success(detail); // 返回完整日志详情。
    }

    private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 设置 HTTP 401 状态。
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // 使用 UTF-8 输出中文。
        response.setContentType("application/json; charset=UTF-8"); // 未登录时返回 JSON。
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录状态失效\"}"); // 写入统一未登录提示。
    }

    private void writeCsvResponse(HttpServletResponse response,
                                  List<ToolCallLogVO> exportLogs,
                                  boolean includeDetail) throws IOException {
        String fileName = "tool-call-log-" + LocalDateTime.now().format(EXPORT_FILE_TIME_FORMATTER) + ".csv"; // 生成固定英文文件名，避免响应头编码问题。
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // 声明 UTF-8 编码。
        response.setContentType("text/csv; charset=UTF-8"); // 设置 CSV 内容类型。
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\""); // 设置浏览器下载文件名。

        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8); // 用 UTF-8 写入响应流。
        writer.write('\uFEFF'); // 写入 UTF-8 BOM，避免 Windows Excel 打开中文乱码。
        writeCsvRow(writer, "ID", "Trace ID", "会话ID", "用户ID", "用户输入", "工具名", "工具类型", "调用来源",
                "路由原因", "参数", "结果", "最终回答", "执行状态", "错误信息", "耗时ms", "创建时间", "更新时间"); // 写入 CSV 表头。
        for (ToolCallLogVO logVO : exportLogs) { // 逐行写入日志数据，避免构造超大字符串。
            writeCsvRow(writer,
                    valueOf(logVO == null ? null : logVO.getId()),
                    logVO == null ? null : logVO.getTraceId(),
                    valueOf(logVO == null ? null : logVO.getConversationId()),
                    valueOf(logVO == null ? null : logVO.getUserId()),
                    exportText(logVO == null ? null : logVO.getUserMessage(), logVO == null ? null : logVO.getUserMessagePreview(), includeDetail, 2000, 200),
                    logVO == null ? null : logVO.getToolName(),
                    logVO == null ? null : logVO.getToolType(),
                    logVO == null ? null : logVO.getCallSource(),
                    logVO == null ? null : logVO.getRouteReason(),
                    exportText(logVO == null ? null : logVO.getArgumentsJson(), logVO == null ? null : logVO.getArgumentsPreview(), includeDetail, 5000, 300),
                    exportText(logVO == null ? null : logVO.getResultJson(), logVO == null ? null : logVO.getResultPreview(), includeDetail, 10000, 500),
                    exportText(logVO == null ? null : logVO.getFinalAnswer(), logVO == null ? null : logVO.getFinalAnswerPreview(), includeDetail, 5000, 300),
                    statusText(logVO == null ? null : logVO.getSuccess()),
                    exportText(logVO == null ? null : logVO.getErrorMessage(), logVO == null ? null : logVO.getErrorMessagePreview(), includeDetail, 2000, 300),
                    valueOf(logVO == null ? null : logVO.getDurationMs()),
                    formatTime(logVO == null ? null : logVO.getCreateTime()),
                    formatTime(logVO == null ? null : logVO.getUpdateTime())); // 写入单条日志。
        }
        writer.flush(); // 刷新响应流。
    }

    private void writeCsvRow(Writer writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) { // 逐列写入，确保每个字段都经过 CSV 转义。
            if (i > 0) { // 非首列前写入分隔逗号。
                writer.write(','); // CSV 列分隔符。
            }
            writer.write(escapeCsv(values[i])); // 写入转义后的字段值。
        }
        writer.write("\r\n"); // 使用 CRLF 换行，兼容 Excel。
    }

    private String escapeCsv(String value) {
        if (value == null) { // 空值导出为空单元格。
            return ""; // 返回空字符串。
        }
        String escapedValue = value.replace("\"", "\"\""); // CSV 中双引号需要替换为两个双引号。
        boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r"); // 逗号、引号和换行都需要整体加引号。
        return needQuote ? "\"" + escapedValue + "\"" : escapedValue; // 按 CSV 规则返回字段值。
    }

    private String exportText(String detailText,
                              String previewText,
                              boolean includeDetail,
                              int detailMaxLength,
                              int previewMaxLength) {
        String text = includeDetail ? detailText : previewText; // includeDetail=true 时优先使用完整字段，否则使用预览字段。
        int maxLength = includeDetail ? detailMaxLength : previewMaxLength; // 根据导出模式选择最大长度。
        return limitText(text, maxLength); // 返回长度受控的导出文本。
    }

    private String limitText(String text, int maxLength) {
        if (text == null) { // 空文本保持为空。
            return null; // 返回空值。
        }
        if (maxLength <= 0 || text.length() <= maxLength) { // 未超过长度限制时直接返回。
            return text; // 保留原始文本。
        }
        return text.substring(0, maxLength); // 超长字段截断，避免 CSV 文件过大。
    }

    private String statusText(Integer success) {
        if (success == null) { // 状态为空时导出空单元格。
            return ""; // 返回空字符串。
        }
        return Integer.valueOf(1).equals(success) ? "成功" : "失败"; // success=1 导出成功，其它按失败展示。
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(EXPORT_CELL_TIME_FORMATTER); // 时间为空时导出空单元格。
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value); // 统一把基础类型转成 CSV 文本。
    }
}
