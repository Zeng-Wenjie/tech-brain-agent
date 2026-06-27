package com.agent.service;

import com.agent.entity.DevActionLog;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 开发行为日志服务接口（P6.1 统一开发行为日志服务）。
 *
 * <p>适用场景：统一保存开发行为日志。Claude Code 编排、patch 生成、编译验证、回滚等开发行为都通过
 * {@link #saveDevAction} 落库，
 * 自动补全 intent（为什么做）、summary（自然语言摘要，供 P17 向量化召回）、result（行为质量，供 P18 筛选）、
 * targetModule / targetFile（供 P17 按模块/文件召回），并通过 trace_id / tool_call_log_id 串起完整开发流程。</p>
 *
 * <p>调用链：SelfDevOrchestrator 或后续沙箱/编译/回滚流程构造 DevActionLogCreateRequest
 * -> {@link #saveDevAction} -> DevActionLogMapper -> dev_action_log。保存失败一律捕获，不影响主流程。</p>
 *
 * <p>边界说明：本接口只做开发行为日志落库与语义补全，不创建表、不执行建表 SQL、不改库结构、不做分页/后台/前端，
 * 不实现 P7-P14 的真实业务（patch/文件修改/编译/回滚），预留方法只设置 actionType 后复用 saveDevAction。</p>
 */
public interface DevActionLogService extends IService<DevActionLog> { // 继承 MyBatis-Plus 通用 Service 能力。

    // ===================== P6.1 统一入口 =====================

    DevActionLogSaveResult saveDevAction(DevActionLogCreateRequest request); // 统一保存开发行为日志：兜底/补全/脱敏/落库，失败不抛异常。

    // ===================== P7-P14 预留语义入口（仅薄包装 saveDevAction，本步骤不接入真实流程）=====================

    DevActionLogSaveResult recordClaudeCodeExecuted(DevActionLogCreateRequest request); // 记录 Claude Code 沙箱开发执行。

    DevActionLogSaveResult recordChangePlanGenerated(DevActionLogCreateRequest request); // 记录“生成修改方案”（P7 预留）。

    DevActionLogSaveResult recordPatchGenerated(DevActionLogCreateRequest request); // 记录“生成 patch”（P8 预留）。

    DevActionLogSaveResult recordFileModified(DevActionLogCreateRequest request); // 记录“文件修改”（P10 预留）。

    DevActionLogSaveResult recordCompileVerified(DevActionLogCreateRequest request); // 记录“编译验证”（P11 预留）。

    DevActionLogSaveResult recordFrontendBuildVerified(DevActionLogCreateRequest request); // 记录“前端构建验证”（预留）。

    DevActionLogSaveResult recordReleaseConfirmed(DevActionLogCreateRequest request); // 记录“发布确认”（预留）。

    DevActionLogSaveResult recordRollbackExecuted(DevActionLogCreateRequest request); // 记录“回滚执行”（P14 预留）。
}
