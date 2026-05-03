package com.agent.mapper;

import com.agent.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserInformationMapper extends BaseMapper<User> {
}
