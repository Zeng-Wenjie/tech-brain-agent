package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAuthDTO;
import com.agent.mapper.RegisterMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@Tag(name = "注册接口")
@RestController
public class RegisterController {
    @Autowired
    private RegisterMapper registerMapper;

    @Operation(summary = "注册接口")
    @PostMapping("/register")
    public Result<String> register(@RequestBody @Valid UserAuthDTO dto) {
        log.info("用户注册,用户名：{}",dto.getUsername());

        //判断用户名是否已存在
        Long  count = registerMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            log.info("注册失败，用户名已存在：{}",dto.getUsername());
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户名已存在");
        }

        //密码加密
        String hashPassword = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());

        //插入用户
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(hashPassword);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        int rows = registerMapper.insert(user);
        return rows > 0 ? Result.success("注册成功") : Result.error(HttpServletResponse.SC_BAD_REQUEST,"注册失败");
    }
}
