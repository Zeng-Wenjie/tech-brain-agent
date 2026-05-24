package com.agent.toolcalling.core; // Tool Calling公共上下文模型包。

import lombok.AllArgsConstructor; // 生成全参构造方法，便于后续测试或批量组装历史上下文。
import lombok.Builder; // 提供builder方式创建历史消息，避免调用方依赖具体实体。
import lombok.Data; // 生成getter/setter/toString等基础方法。
import lombok.NoArgsConstructor; // 生成无参构造方法，兼容序列化和框架反射创建对象。

import java.time.LocalDateTime;

/**
 * Tool Calling专用历史上下文消息模型。
 *
 * <p>该模型位于Tech-Brain-Tool公共模块，用于承接上层业务转换后的结构化多轮历史消息。</p>
 * <p>调用链规划为：ChatMessageServiceImpl读取chat_message历史 -> 转换为ToolChatHistoryMessage
 * -> 后续版本传入ToolCallingChatService -> Tool Calling编排器按时间顺序组织模型上下文。</p>
 * <p>本类不依赖Agent模块的ChatMessage实体、不依赖数据库表结构，只表达Tool Calling需要的role、content和createTime。</p>
 */
@Data // 生成role/content/createTime访问方法，保持公共模型使用简单。
@NoArgsConstructor // 框架反射和后续JSON反序列化可直接创建空对象。
@AllArgsConstructor // 支持一次性构造完整历史消息。
@Builder // 支持按字段显式构造，减少参数顺序误用。
public class ToolChatHistoryMessage { // Tool Calling侧的结构化历史消息。

    private String role; // 消息角色，只允许user或assistant。

    private String content; // 历史消息正文。

    private LocalDateTime createTime; // 历史消息创建时间，用于后续按时间顺序组织上下文。
}
