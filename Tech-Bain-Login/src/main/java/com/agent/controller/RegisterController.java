package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.UserAuthDTO;
import com.agent.service.RegisterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "注册接口")
@RestController
public class RegisterController {
    @Autowired
    private RegisterService registerService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<String> register(@RequestBody @Valid UserAuthDTO dto) {
        log.info("用户注册,用户名：{}", dto.getUsername());
        return registerService.register(dto);
    }
}
