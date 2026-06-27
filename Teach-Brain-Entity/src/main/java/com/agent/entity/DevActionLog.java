package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开发行为日志实体。
 *
 * <p>适用场景：映射 dev_action_log 表，记录 Claude Code 沙箱执行、patch 生成、编译验证、发布确认和回滚等开发行为，
 * 为后续开发记忆召回提供 intent、result、summary 和目标范围。</p>
 *
 * <p>调用链：SelfDevOrchestrator 或后续沙箱/验证/回滚流程构造 DevActionLogCreateRequest
 * -> DevActionLogService.saveDevAction -> DevActionLogMapper -> dev_action_log。</p>
 *
 * <p>边界说明：本实体只描述表字段映射，不执行 SQL，不自动建表，不保存文件内容、源码全文或服务器绝对路径，
 * result_json 只承载已脱敏的执行结果、文件清单、输出摘要和 diff 摘要。</p>
 *
 * <p>P6.1 语义化增强：新增 intent（为什么做）、result（行为结果质量）、target_module（目标模块）、
 * target_file（目标文件，P17 召回优先用）、related_bug_id（关联缺陷）五个语义字段，对应表已通过 ALTER 预先添加，
 * 本步骤只补 Java 映射、不改库结构。</p>
 *
 * <p>建表 DDL（需人工执行，本步骤不自动连库建表；P6.1 字段已由 ALTER 添加，不再重复执行）：</p>
 * <pre>
 * CREATE TABLE dev_action_log (
 *   id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
 *   user_id         BIGINT       NULL COMMENT '用户ID',
 *   conversation_id BIGINT       NULL COMMENT '会话ID',
 *   trace_id        VARCHAR(64)  NULL COMMENT '与 tool_call_log 一致的链路追踪ID',
 *   tool_call_log_id BIGINT      NULL COMMENT '来源 tool_call_log.id',
 *   action_type     VARCHAR(50)  NULL COMMENT '开发行为类型，如 CODE_RISK_ANALYSIS',
 *   analysis_type   VARCHAR(30)  NULL COMMENT '历史分析类型，可空',
 *   target_type     VARCHAR(30)  NULL COMMENT '目标类型，如 CLASS/TOOL/ENDPOINT',
 *   target_path     VARCHAR(500) NULL COMMENT 'workspace 相对路径',
 *   class_name      VARCHAR(200) NULL COMMENT '类名',
 *   method_name     VARCHAR(200) NULL COMMENT '方法名',
 *   endpoint        VARCHAR(300) NULL COMMENT '接口路径',
 *   tool_name       VARCHAR(100) NULL COMMENT 'AI Tool 名称',
 *   event_name      VARCHAR(100) NULL COMMENT 'SSE 事件名',
 *   title           VARCHAR(255) NULL COMMENT '开发日志标题',
 *   summary         VARCHAR(1000) NULL COMMENT '开发日志摘要',
 *   result_json     LONGTEXT     NULL COMMENT '执行结果JSON（已脱敏、相对路径）',
 *   status          VARCHAR(20)  NULL COMMENT 'SUCCESS / FAILED',
 *   error_msg       VARCHAR(1000) NULL COMMENT '失败原因',
 *   created_at      DATETIME     NULL COMMENT '创建时间',
 *   updated_at      DATETIME     NULL COMMENT '更新时间',
 *   PRIMARY KEY (id),
 *   KEY idx_dev_action_log_conv (conversation_id, user_id),
 *   KEY idx_dev_action_log_trace (trace_id)
 * ) COMMENT='开发行为日志';
 * </pre>
 */
@Data // 使用 Lombok 生成 getter/setter，保持与 ToolCallLog 等实体一致的风格。
@TableName("dev_action_log") // 映射开发行为日志表 dev_action_log。
public class DevActionLog { // 开发行为日志持久化对象。

    @TableId(value = "id", type = IdType.AUTO) // 主键使用数据库自增 ID。
    private Long id; // dev_action_log.id。
    private Long userId; // 用户 ID，对应 user_id。
    private Long conversationId; // 会话 ID，对应 conversation_id。
    private String traceId; // 与 tool_call_log 一致的链路追踪 ID，对应 trace_id。
    private Long toolCallLogId; // 来源 tool_call_log.id，对应 tool_call_log_id，便于追踪分析来源。
    private String actionType; // 开发行为类型，对应 action_type，对应 DevActionType，例如 CLAUDE_CODE_EXECUTED。
    private String intent; // 行为意图“为什么做”，对应 intent，P17 语义召回用，自然语言。
    private String result; // 行为结果质量，对应 result，对应 DevActionResult，例如 SUCCESS / FAILED，供 P18 筛选。
    private String analysisType; // 历史分析类型，对应 analysis_type，新链路通常为空。
    private String targetType; // 目标类型，对应 target_type，对应 DevTargetType，例如 CLASS / TOOL / ENDPOINT。
    private String targetModule; // 目标模块，对应 target_module，例如 Tech-Brain-Agent，P17 按模块召回用。
    private String targetFile; // 目标文件 workspace 相对路径，对应 target_file，P17 召回优先用，绝不保存绝对路径。
    private String targetPath; // 历史兼容文件路径，对应 target_path，P5.9 已用，绝不保存服务器绝对路径。
    private String className; // 类名，对应 class_name。
    private String methodName; // 方法名，对应 method_name。
    private String endpoint; // 接口路径，对应 endpoint。
    private String toolName; // AI Tool 名称，对应 tool_name。
    private String eventName; // SSE 事件名，对应 event_name。
    private String relatedBugId; // 关联缺陷 / 问题编号，对应 related_bug_id，可空。
    private String title; // 开发日志标题，对应 title。
    private String summary; // 开发日志摘要，对应 summary。
    private String resultJson; // 执行结果 JSON，对应 result_json，只保存已脱敏的结构化结果。
    private String status; // 保存状态，对应 status，取值 SUCCESS / FAILED。
    private String errorMsg; // 失败原因，对应 error_msg。
    private LocalDateTime createdAt; // 创建时间，对应 created_at。
    private LocalDateTime updatedAt; // 更新时间，对应 updated_at。
}
