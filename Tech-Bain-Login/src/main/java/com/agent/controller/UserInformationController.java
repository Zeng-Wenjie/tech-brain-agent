package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAvatarUploadDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.seriver.UserInformationService;
import com.agent.utils.AliOssUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@Tag(name = "填写用户信息接口")
public class UserInformationController {

    @Autowired
    private UserInformationService userInformationService;
    @Autowired
    private AliOssUtil aliOssUtil;

    @Schema(description = "用户信息接口")
    @PostMapping("/userInformation")
    public Result userInformation(@RequestBody UserInformationDTO dto){
        log.info("用户填写用户信息:{}",dto);
        userInformationService.userInformation(dto);
        return Result.success("填写用户信息成功");
    }

    @Schema(description = "用户上传头像接口")
    @PostMapping("/avatar")
    public Result avatar(MultipartFile file){
        log.info("用户上传头像:{}",file);
        try {
            String url = aliOssUtil.upload(file.getBytes(),file.getOriginalFilename());
            return Result.success(url);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(HttpServletResponse.SC_BAD_REQUEST ,"头像上传OSS失败");
        }
    }
}
