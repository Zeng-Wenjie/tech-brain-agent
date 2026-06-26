package com.agent.service;

import com.agent.entity.DevActionLog;
import com.agent.entity.dto.DevActionLogSaveCommand;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 开发行为日志服务接口（P5.9 代码分析结果落库最小能力）。
 *
 * <p>适用场景：为 analyzeCode 提供“把本次代码分析结果保存到开发日志”的能力，支撑两条链路：
 * 一是“分析并保存”——AnalyzeCodeTool 分析成功且 saveToDevLog=true 时直接调用 {@link #saveCodeAnalysisLog}；
 * 二是“保存上一条分析结果”——用户显式要求保存时调用 {@link #saveLastCodeAnalysisLog} 读取最近一条 analyzeCode
 * 的 tool_call_log 再落库。两条链路复用同一套 title/summary/action_type 生成与保存逻辑。</p>
 *
 * <p>调用链：AnalyzeCodeTool / ChatMessageServiceImpl 的 DevActionLogRecorder 实现 -> 本接口 -> DevActionLogMapper
 * -> dev_action_log 表；保存失败必须捕获并返回，不能影响 analyzeCode 主流程或聊天 SSE。</p>
 *
 * <p>边界说明：本接口只做 P5.9 代码分析结果落库，不做 P6 完整开发行为日志、不记录 patch / 文件修改 / 编译 / 回滚，
 * 不做分页查询、后台接口或前端页面，不创建表，不执行建表 SQL，不修改 tool_call_log 表结构。</p>
 */
public interface DevActionLogService extends IService<DevActionLog> { // 继承 MyBatis-Plus 通用 Service 能力。

    Long saveCodeAnalysisLog(DevActionLogSaveCommand command); // 保存一次代码分析结果到 dev_action_log，返回日志 ID，失败返回 null。

    DevActionLogSaveResult saveLastCodeAnalysisLog(Long userId,
                                                   Long conversationId,
                                                   String traceId); // 读取当前会话最近一条成功 analyzeCode 结果并保存到开发日志。
}
