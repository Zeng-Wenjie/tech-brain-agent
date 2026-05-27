package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tool Calling 调用日志实体。
 *
 * <p>适用场景：映射数据库中已经存在的 tool_call_log 表，用于保存一次工具调用的路由原因、
 * 入参、结果、耗时、成功状态和最终回答回填内容。</p>
 * <p>调用链：后续 Tool Calling 编排链路会通过 ToolCallLogService 创建运行中日志，
 * 工具执行完成后再调用 markSuccess 或 markFailed 更新结果，最终回答生成后再按 traceId
 * 调用 updateFinalAnswerByTraceId 回填同一轮调用记录。</p>
 * <p>边界说明：本实体只描述表字段映射，不执行 SQL，不创建表，不接入聊天主链路。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目现有实体风格。
@TableName("tool_call_log") // 映射数据库已存在的 tool_call_log 表。
public class ToolCallLog { // Tool Calling 工具调用日志持久化对象。

    @TableId(type = IdType.AUTO) // 主键使用数据库自增 ID。
    private Long id; // tool_call_log.id。
    private String traceId; // 同一轮 Tool Calling 调用链路的追踪 ID，对应 trace_id。
    private Long conversationId; // 当前会话 ID，对应 conversation_id。
    private Long userId; // 当前用户 ID，对应 user_id。
    private String userMessage; // 当前用户原始消息，对应 user_message。
    private String toolName; // 工具名称，对应 tool_name。
    private String toolType; // 工具类型，对应 tool_type。
    private String callSource; // 调用来源，对应 call_source。
    private String routeReason; // 工具路由原因，对应 route_reason。
    private String argumentsJson; // 工具调用参数 JSON，对应 arguments_json。
    private String resultJson; // 工具调用结果 JSON，对应 result_json。
    private String finalAnswer; // 本轮最终回答，对应 final_answer。
    private Integer success; // 调用成功标记，0 表示运行中或失败，1 表示成功。
    private String errorMessage; // 失败原因，对应 error_message。
    private Long durationMs; // 工具调用耗时毫秒数，对应 duration_ms。
    private LocalDateTime createTime; // 记录创建时间，对应 create_time。
    private LocalDateTime updateTime; // 记录更新时间，对应 update_time。
}
