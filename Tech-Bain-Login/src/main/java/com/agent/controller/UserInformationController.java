package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.ChangePasswordDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.service.UserInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@Tag(name = "用户信息管理接口")
public class UserInformationController {

    @Autowired
    private UserInformationService userInformationService;

    @Operation(summary = "更新当前登录用户信息")
    @PostMapping("/userInformation")
    public Result<String> userInformation(@RequestBody UserInformationDTO dto) {
        log.info("用户填写用户信息:{}", dto);
        return userInformationService.userInformation(dto);
    }

    @Operation(summary = "上传当前登录用户头像")
    @PostMapping("/avatar")
    public Result<String> avatar(MultipartFile file) {
        log.info("用户上传头像:{}", file);
        return userInformationService.uploadAvatar(file);
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/info")
    public Result<User> getUserInfo() {
        return userInformationService.getCurrentUserInfo();
    }

    @Operation(summary = "修改当前登录用户密码")
    @PostMapping("/password")
    public Result<String> changePassword(@RequestBody @Valid ChangePasswordDTO dto) {
        log.info("用户修改密码");
        return userInformationService.changePassword(dto);
    }
}
