package com.agent.service;

import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.ToolCallLogCreateRequest;
import com.agent.entity.dto.ToolCallLogPageRequest;
import com.agent.entity.vo.ToolCallLogVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * Tool Calling 调用日志服务接口。
 *
 * <p>适用场景：为 Tool Calling 链路提供日志创建、成功更新、失败更新、最终回答回填、
 * 后台分页查询和详情查询能力。</p>
 * <p>调用链：ToolCallingChatServiceImpl 通过 ChatMessageServiceImpl 注入的回调写入日志；
 * ToolCallLogController 通过本接口查询 tool_call_log；最终由 ToolCallLogMapper 访问数据库。</p>
 * <p>边界说明：本接口只定义 tool_call_log 的持久化和查询能力，不创建表，不执行建表 SQL，
 * 不修改聊天接口路径，也不改变 ragSearch 或 summarizeArticle 的业务逻辑。</p>
 */
public interface ToolCallLogService extends IService<ToolCallLog> { // 继承 MyBatis-Plus 通用 Service 能力。

    Long createRunningLog(ToolCallLogCreateRequest request); // 创建 success=0 的运行中工具调用日志。

    void markSuccess(Long id, String resultJson, Long durationMs); // 按日志 ID 标记工具调用成功。

    void markFailed(Long id, String errorMessage, Long durationMs); // 按日志 ID 标记工具调用失败。

    void updateFinalAnswerByTraceId(String traceId, String finalAnswer); // 按 traceId 回填同一轮全部工具调用的最终回答。

    PageDTO<ToolCallLogVO> pageToolCallLogs(ToolCallLogPageRequest request); // 分页查询当前用户可见的工具调用日志。

    ToolCallLogVO getToolCallLogDetail(Long id, Long currentUserId); // 查询当前用户可见的单条工具调用日志详情。
}
