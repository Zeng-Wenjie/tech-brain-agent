package com.agent.mapper;

import com.agent.entity.ChatMessageFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息附件关联 Mapper。
 *
 * <p>适用场景：为数据库中已存在的 chat_message_file 表提供 MyBatis-Plus 基础 CRUD 能力。</p>
 * <p>调用链：ChatMessageFileServiceImpl -> ChatMessageFileMapper -> chat_message_file 表。</p>
 * <p>边界说明：本 Mapper 只继承 BaseMapper，不编写 XML，不创建表，不修改数据库结构，不读取文件内容。</p>
 */
@Mapper // 交给 MyBatis 扫描并生成代理对象。
public interface ChatMessageFileMapper extends BaseMapper<ChatMessageFile> { // 继承 MyBatis-Plus 基础 CRUD 接口。
}
