package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAvatarUploadDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.seriver.UserInformationService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@Tag(name = "填写用户信息接口")
public class UserInformationController {

    @Autowired
    private UserInformationService userInformationService;

    @Schema(description = "用户信息接口")
    @PostMapping("/userInformation")
    public Result userInformation(@RequestBody UserInformationDTO dto){
        log.info("用户填写用户信息:{}",dto);
        userInformationService.userInformation(dto);
        return Result.success("填写用户信息成功");
    }

//    @Schema(description = "用户上传头像接口")
//    @PostMapping("/avatar")
//    public Result avatar(@RequestBody UserAvatarUploadDTO dto){
//        log.info("用户上传头像:{}",dto);
//        userInformationService.avatar(dto);
//        return Result.success("上传头像成功");
//    }
}
