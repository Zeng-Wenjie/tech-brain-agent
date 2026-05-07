package com.agent.mapper;

import com.agent.entity.OperationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/*
 * 操作日志
 * Operation log mapper.
 */
@Mapper
public interface OperationLohMapper extends BaseMapper<OperationLog> {
}
