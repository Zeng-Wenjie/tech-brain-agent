package com.agent.service.impl;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAuthDTO;
import com.agent.mapper.RegisterMapper;
import com.agent.service.RegisterService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class RegisterServiceImpl extends ServiceImpl<RegisterMapper, User> implements RegisterService {

    @Autowired
    private RegisterMapper registerMapper;

    @Override
    public Result<String> register(UserAuthDTO dto) {
        //判断用户名是否已存在
        // Check whether the username already exists.
        Long  count = registerMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            log.info("注册失败，用户名已存在：{}",dto.getUsername());
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户名已存在");
        }

        //密码加密
        // Encrypt the password.
        String hashPassword = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());

        //插入用户
        // Insert the user.
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(hashPassword);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        int rows = registerMapper.insert(user);
        return rows > 0 ? Result.success("注册成功") : Result.error(HttpServletResponse.SC_BAD_REQUEST,"注册失败");
    }
}
