package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.ToolCallLogPageRequest;
import com.agent.entity.vo.ToolCallLogVO;
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

/**
 * 工具调用日志后台查询接口。
 *
 * <p>适用场景：后台排查 Tool Calling 工具调用问题时，按用户、会话、traceId、工具名、
 * 执行状态和时间范围查询 tool_call_log。</p>
 * <p>调用链：前端后台页面或接口调试工具 -> ToolCallLogController -> ToolCallLogService
 * -> ToolCallLogMapper -> tool_call_log。</p>
 * <p>边界说明：本 Controller 只提供只读查询接口，不修改数据库结构，不写入日志，
 * 不改变 /chat/message、summary_result、ragSearch、summarizeArticle 或长期记忆链路。</p>
 */
@RestController // 注册为 Spring MVC 控制器。
@RequestMapping("/api/tool-log") // 工具调用日志后台查询路径。
@Tag(name = "工具调用日志接口")
public class ToolCallLogController { // 工具调用日志查询 Controller。

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
}
