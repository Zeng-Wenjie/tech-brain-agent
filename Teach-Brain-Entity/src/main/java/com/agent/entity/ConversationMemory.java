package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级长期记忆实体。
 *
 * <p>适用场景：用于映射 conversation_memory 表，保存“某个用户 + 某个会话”的长期摘要、已汇总消息数量以及创建/更新时间。</p>
 * <p>当前阶段只提供数据库字段到 Java 对象的基础映射，方便 ConversationMemoryMapper 和 ConversationMemoryService 做读写。</p>
 * <p>规划调用链：后续 ChatMessageServiceImpl 在完成短期历史处理后，可通过 ConversationMemoryService 读取/更新本实体；
 * 本步骤暂不接入 /chat/message 主流程，也不调用 DeepSeek 做自动总结。</p>
 * <p>边界说明：本实体不包含业务逻辑、不执行 SQL、不修改表结构，只描述 conversation_memory 表字段。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持和项目现有实体风格一致。
@TableName("conversation_memory") // 映射数据库 conversation_memory 表。
public class ConversationMemory { // 会话长期记忆持久化对象。

    @TableId(type = IdType.AUTO) // 主键使用数据库自增 ID。
    private Long id; // conversation_memory.id。

    private Long conversationId; // 所属会话 ID，对应 conversation_id。

    private Long userId; // 所属用户 ID，对应 user_id，用于用户数据隔离。

    private String summary; // 会话长期摘要正文，对应 summary。

    private Integer messageCount; // 已纳入长期摘要的消息数量，对应 message_count。

    private LocalDateTime createTime; // 记录创建时间，对应 create_time。

    private LocalDateTime updateTime; // 记录更新时间，对应 update_time。
}
