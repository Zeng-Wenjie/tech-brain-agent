package com.agent.service;

import com.agent.entity.dto.SummaryRequest;
import com.agent.entity.dto.SummaryResult;

/**
 * 通用内容总结服务接口。
 *
 * <p>适用场景：为文章总结、笔记总结、爬虫文章总结、文件总结、URL 总结和纯文本总结提供统一后端入口。</p>
 * <p>当前调用链：Controller/Tool/业务 Service -> SummaryService.summarize(request) -> SummaryServiceImpl -> DeepSeek 同步模型 -> SummaryResult。</p>
 * <p>边界说明：本接口只定义总结能力，不读取数据库、不创建新 Tool、不影响 /chat/message 和 ToolCallingChatService 主链路。</p>
 */
public interface SummaryService { // 通用内容总结服务入口。

    SummaryResult summarize(SummaryRequest request); // 对传入任意内容生成结构化总结结果。
}
