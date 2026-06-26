package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开发行为日志实体（P5.9 代码分析结果落库最小能力）。
 *
 * <p>适用场景：映射 dev_action_log 表，第一版只用于保存一次 analyzeCode 代码分析结果，
 * 为后续 P6 完整开发行为日志体系、P7 / P17 的开发行为追踪与记忆召回打基础。
 * P5.9 只记录代码分析结果，不记录 patch / 文件修改 / 编译 / 回滚等开发行为。</p>
 *
 * <p>调用链：AnalyzeCodeTool 执行成功且 saveToDevLog=true 时调用 DevActionLogService.saveCodeAnalysisLog 写入；
 * 或用户显式“保存上一条分析结果”时由 DevActionLogService.saveLastCodeAnalysisLog 读取最近一条 analyzeCode 的
 * tool_call_log 再写入本表。本表通过 trace_id 和 tool_call_log_id 与 tool_call_log 关联，便于追踪分析结果来源。</p>
 *
 * <p>边界说明：本实体只描述表字段映射，不执行 SQL，不自动建表，不保存文件内容、源码全文或服务器绝对路径，
 * result_json 只承载 analyzeCode 已脱敏的统一分析结果（路径均为 workspace 相对路径）。</p>
 *
 * <p>建表 DDL（需人工执行，本步骤不自动连库建表）：</p>
 * <pre>
 * CREATE TABLE dev_action_log (
 *   id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
 *   user_id         BIGINT       NULL COMMENT '用户ID',
 *   conversation_id BIGINT       NULL COMMENT '会话ID',
 *   trace_id        VARCHAR(64)  NULL COMMENT '与 tool_call_log 一致的链路追踪ID',
 *   tool_call_log_id BIGINT      NULL COMMENT '来源 tool_call_log.id',
 *   action_type     VARCHAR(50)  NULL COMMENT '开发行为类型，如 CODE_RISK_ANALYSIS',
 *   analysis_type   VARCHAR(30)  NULL COMMENT 'analyzeCode 的 analysisType',
 *   target_type     VARCHAR(30)  NULL COMMENT '目标类型，如 CLASS/TOOL/ENDPOINT',
 *   target_path     VARCHAR(500) NULL COMMENT 'workspace 相对路径',
 *   class_name      VARCHAR(200) NULL COMMENT '类名',
 *   method_name     VARCHAR(200) NULL COMMENT '方法名',
 *   endpoint        VARCHAR(300) NULL COMMENT '接口路径',
 *   tool_name       VARCHAR(100) NULL COMMENT 'AI Tool 名称',
 *   event_name      VARCHAR(100) NULL COMMENT 'SSE 事件名',
 *   title           VARCHAR(255) NULL COMMENT '开发日志标题',
 *   summary         VARCHAR(1000) NULL COMMENT '开发日志摘要',
 *   result_json     LONGTEXT     NULL COMMENT '分析结果JSON（已脱敏、相对路径）',
 *   status          VARCHAR(20)  NULL COMMENT 'SUCCESS / FAILED',
 *   error_msg       VARCHAR(1000) NULL COMMENT '失败原因',
 *   created_at      DATETIME     NULL COMMENT '创建时间',
 *   updated_at      DATETIME     NULL COMMENT '更新时间',
 *   PRIMARY KEY (id),
 *   KEY idx_dev_action_log_conv (conversation_id, user_id),
 *   KEY idx_dev_action_log_trace (trace_id)
 * ) COMMENT='开发行为日志（P5.9 代码分析结果）';
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
    private String actionType; // 开发行为类型，对应 action_type，例如 CODE_RISK_ANALYSIS。
    private String analysisType; // analyzeCode 的 analysisType，对应 analysis_type，例如 RISK。
    private String targetType; // 目标类型，对应 target_type，例如 CLASS / TOOL / ENDPOINT。
    private String targetPath; // workspace 相对路径，对应 target_path，绝不保存服务器绝对路径。
    private String className; // 类名，对应 class_name。
    private String methodName; // 方法名，对应 method_name。
    private String endpoint; // 接口路径，对应 endpoint。
    private String toolName; // AI Tool 名称，对应 tool_name。
    private String eventName; // SSE 事件名，对应 event_name。
    private String title; // 开发日志标题，对应 title。
    private String summary; // 开发日志摘要，对应 summary。
    private String resultJson; // 分析结果 JSON，对应 result_json，只保存已脱敏的统一分析结果。
    private String status; // 保存状态，对应 status，取值 SUCCESS / FAILED。
    private String errorMsg; // 失败原因，对应 error_msg。
    private LocalDateTime createdAt; // 创建时间，对应 created_at。
    private LocalDateTime updatedAt; // 更新时间，对应 updated_at。
}
