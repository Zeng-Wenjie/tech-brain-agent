package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.service.UserInformationService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@Tag(name = "填写用户信息接口")
public class UserInformationController {

    @Autowired
    private UserInformationService userInformationService;

    @Schema(description = "更新用户信息接口")
    @PostMapping("/userInformation")
    public Result<String> userInformation(@RequestBody UserInformationDTO dto){
        log.info("用户填写用户信息:{}",dto);
        return userInformationService.userInformation(dto);
    }

    @Schema(description = "用户上传头像接口")
    @PostMapping("/avatar")
    public Result<String> avatar(MultipartFile file){
        log.info("用户上传头像:{}",file);
        return userInformationService.uploadAvatar(file);
    }

    @GetMapping("/info")
    @Schema(description = "获取当前登录用户信息")
    public Result<User> getUserInfo() {
        return userInformationService.getCurrentUserInfo();
    }
}

