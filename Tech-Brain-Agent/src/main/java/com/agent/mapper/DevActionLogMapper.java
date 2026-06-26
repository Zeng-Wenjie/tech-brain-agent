package com.agent.mapper;

import com.agent.entity.DevActionLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 开发行为日志 Mapper（P5.9）。
 *
 * <p>适用场景：为 dev_action_log 表提供 MyBatis-Plus 基础 CRUD 能力，第一版只用于保存 analyzeCode 代码分析结果。</p>
 * <p>调用链：DevActionLogServiceImpl -> DevActionLogMapper -> dev_action_log 表。</p>
 * <p>边界说明：本 Mapper 只继承 BaseMapper，不编写 XML，不创建表，不修改数据库结构。</p>
 */
@Mapper // 交给 MyBatis 扫描并生成代理对象。
public interface DevActionLogMapper extends BaseMapper<DevActionLog> { // 继承 MyBatis-Plus 基础 CRUD 接口。
}
