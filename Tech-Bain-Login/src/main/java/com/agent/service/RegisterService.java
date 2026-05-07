package com.agent.service;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAuthDTO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface RegisterService extends IService<User> {

    Result<String> register(UserAuthDTO dto);
}
