package com.agent.mapper;

import com.agent.entity.ToolCallLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Tool Calling 调用日志 Mapper。
 *
 * <p>适用场景：为数据库中已经存在的 tool_call_log 表提供 MyBatis-Plus 基础 CRUD 能力。</p>
 * <p>调用链：ToolCallLogServiceImpl -> ToolCallLogMapper -> tool_call_log 表。</p>
 * <p>边界说明：本 Mapper 只继承 BaseMapper，不编写 XML，不创建表，不修改数据库结构。</p>
 */
@Mapper // 交给 MyBatis 扫描并生成代理对象。
public interface ToolCallLogMapper extends BaseMapper<ToolCallLog> { // 继承 MyBatis-Plus 基础 CRUD 接口。
}
