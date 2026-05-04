package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAvatarUploadDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.seriver.UserInformationService;
import com.agent.utils.AliOssUtil;
import com.agent.utils.UserContext;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@Tag(name = "填写用户信息接口")
public class UserInformationController {

    @Autowired
    private UserInformationService userInformationService;
    @Autowired
    private AliOssUtil aliOssUtil;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Schema(description = "更新用户信息接口")
    @PostMapping("/userInformation")
    public Result<String> userInformation(@RequestBody UserInformationDTO dto){
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
            Long userId = UserContext.getUserId(); // 从 ThreadLocal/拦截器 获取当前用户ID
            if (userId != null) {
                User user = new User();
                user.setId(userId);      // 指定主键
                user.setAvatar(url);     // 设置要更新的头像字段
                userInformationService.updateById(user);
            }
            return Result.success(url);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(HttpServletResponse.SC_BAD_REQUEST ,"头像上传OSS失败");
        }
    }

    @GetMapping("/info")
    @Schema(description = "获取当前登录用户信息")
    public Result<User> getUserInfo() {
        // 从当前线程上下文获取已登录用户的 ID
        Long userId = UserContext.getUserId();

        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }

        String redisKey = "user:info:" + userId;
        // 尝试从 Redis 中获取用户信息
        User user = (User) redisTemplate.opsForValue().get(redisKey);
        if (user != null) {
            log.info("命中Redis缓存！用户ID: {}", userId);
            return Result.success(user);
        }
        //Redis未命中，从数据库中获取用户信息
        log.info("未命中Redis缓存！用户ID: {}", userId);
        user = userInformationService.getById(userId);

        // 把密码擦除再返回给前端
        if (user != null) {
            user.setPassword(null);
            redisTemplate.opsForValue().set(redisKey, user,2, TimeUnit.HOURS);
        }

        return Result.success(user);
    }
}

