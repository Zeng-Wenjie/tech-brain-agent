package com.agent.mapper;

import com.agent.entity.ConversationMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话级长期记忆 Mapper。
 *
 * <p>适用场景：为 conversation_memory 表提供 MyBatis-Plus 基础 CRUD 能力。</p>
 * <p>当前调用链：ConversationMemoryServiceImpl -> ConversationMemoryMapper -> conversation_memory 表。</p>
 * <p>边界说明：本 Mapper 只暴露 BaseMapper 的基础能力，不写自定义 SQL，不创建或修改数据库表。</p>
 */
@Mapper // 交给 MyBatis 扫描并生成代理对象。
public interface ConversationMemoryMapper extends BaseMapper<ConversationMemory> { // 继承 MyBatis-Plus 基础 CRUD 接口。
}
