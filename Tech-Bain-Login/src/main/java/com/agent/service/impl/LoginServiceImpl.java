package com.agent.service.impl;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.UserInfo;
import com.agent.entity.dto.UserAuthDTO;
import com.agent.mapper.LoginMapper;
import com.agent.service.LoginService;
import com.agent.utils.JwtUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LoginServiceImpl extends ServiceImpl<LoginMapper, User> implements LoginService {

    @Autowired
    private LoginMapper loginMapper;

    @Override
    public Result<UserInfo> login(UserAuthDTO dto) {
        // 根据用户名查询用户
        // Query the user by username.
        User loginUser = loginMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (loginUser == null) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户名或密码错误");
        }

        //密码校验，调用BCrypt.checkpw方法
        // Validate the password with BCrypt.checkpw.
        boolean isPasswordMath = BCrypt.checkpw(dto.getPassword(), loginUser.getPassword());
        if (!isPasswordMath) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户名或密码错误");
        }

        log.info("账号密码正确：{}", loginUser);
        // 生成Token
        // Generate the token.
        String token = JwtUtils.createToken(loginUser.getId(), loginUser.getUsername());
        //把用户信息和Token封装到UserInfo对象中并放回前端
        // Wrap the user information and token in UserInfo for the frontend.
        UserInfo userInfo = new UserInfo();
        userInfo.setId(loginUser.getId());
        userInfo.setUsername(loginUser.getUsername());
        userInfo.setName(loginUser.getName());
        userInfo.setToken(token);
        log.info("登录成功，将Token发给用户：{}",userInfo);

        return Result.success(userInfo);
    }
}
