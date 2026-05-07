package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.UserInfo;
import com.agent.entity.dto.UserAuthDTO;
import com.agent.service.LoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    private LoginService loginService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody @Valid UserAuthDTO dto) {
        log.info("用户登录：{}", dto.getUsername());
        return loginService.login(dto);
    }
}
