package com.agent.service.impl;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.mapper.UserInformationMapper;
import com.agent.service.UserInformationService;
import com.agent.utils.AliOssUtil;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UserInformationServiceImpl extends ServiceImpl<UserInformationMapper, User> implements UserInformationService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private AliOssUtil aliOssUtil;

    @Override
    public Result<String> userInformation(UserInformationDTO dto) {
        Long userId = UserContext.getUserId();// 获取用户id
        if(userId != null){
            User user = new User();
            BeanUtils.copyProperties(dto,user);
            user.setId(userId);
            String redisKey = "user:info:" + userId;
            //先删Redis旧缓存
            redisTemplate.delete(redisKey);
            boolean success = this.updateById(user);//同步更新
            //更新成功，异步延迟双删
            if ( success){
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(500);//延迟500毫秒
                        redisTemplate.delete(redisKey);//再次删除
                        log.info("异步删除用户信息成功,KEY: {}",redisKey);
                    }catch (InterruptedException e){
                        log.error("异步删除用户信息失败",e);
                    }
                });
                return Result.success("用户信息更新成功");
            }
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"用户信息更新失败");
        }else throw new RuntimeException("无法获取当前用户信息，请重新登录");
    }

    @Override
    public Result<String> uploadAvatar(MultipartFile file) {
        try {
            String url = aliOssUtil.upload(file.getBytes(),file.getOriginalFilename());
            Long userId = UserContext.getUserId(); // 从 ThreadLocal/拦截器 获取当前用户ID
            if (userId != null) {
                User user = new User();
                user.setId(userId);      // 指定主键
                user.setAvatar(url);     // 设置要更新的头像字段
                this.updateById(user);
            }
            return Result.success(url);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(HttpServletResponse.SC_BAD_REQUEST ,"头像上传OSS失败");
        }
    }

    @Override
    public Result<User> getCurrentUserInfo() {
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
        user = this.getById(userId);

        // 把密码擦除再返回给前端
        if (user != null) {
            user.setPassword(null);
            redisTemplate.opsForValue().set(redisKey, user,2, TimeUnit.HOURS);
        }

        return Result.success(user);
    }
}
