package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.UserInfo;
import com.agent.mapper.LoginMapper;
import com.agent.utils.JwtUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "登录接口")
@Slf4j
@RestController
public class LoginController {
    @Autowired
    private LoginMapper loginMapper;

    @Operation(summary = "登录接口")
    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody User user) {
        log.info("用户登录：{}", user);
        //判断密码是否正确
        User loginUser = loginMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, user.getUsername())
                .eq(User::getPassword, user.getPassword()));
        if (loginUser == null) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户名或密码错误");
        }
        log.info("账号密码正确：{}", loginUser);
        // 生成Token
        String token = JwtUtils.createToken(loginUser.getId(), loginUser.getUsername());
        //把用户信息和Token封装到UserInfo对象中并放回前端
        UserInfo userInfo = new UserInfo();
        userInfo.setId(loginUser.getId());
        userInfo.setUsername(loginUser.getUsername());
        userInfo.setName(loginUser.getName());
        userInfo.setToken(token);
        log.info("登录成功，将Token发给用户：{}",userInfo);

        return Result.success(userInfo);
    }
}
