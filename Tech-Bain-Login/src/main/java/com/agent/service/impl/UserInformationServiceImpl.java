package com.agent.service.impl;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.ChangePasswordDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.mapper.UserInformationMapper;
import com.agent.service.UserInformationService;
import com.agent.utils.AliOssUtil;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
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
        Long userId = UserContext.getUserId();// 获取用户id / Get the user ID.
        if(userId != null){
            User user = new User();
            BeanUtils.copyProperties(dto,user);
            user.setId(userId);
            String redisKey = "user:info:" + userId;
            //先删Redis旧缓存
            // Delete the old Redis cache first.
            redisTemplate.delete(redisKey);
            boolean success = this.updateById(user);//同步更新 / Update synchronously.
            //更新成功，异步延迟双删
            // After a successful update, perform asynchronous delayed double deletion.
            if ( success){
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(500);//延迟500毫秒 / Delay for 500 milliseconds.
                        redisTemplate.delete(redisKey);//再次删除 / Delete the cache again.
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
            Long userId = UserContext.getUserId(); // 从 ThreadLocal/拦截器 获取当前用户ID / Get the current user ID from ThreadLocal or the interceptor.
            if (userId != null) {
                User user = new User();
                user.setId(userId);      // 指定主键 / Set the primary key.
                user.setAvatar(url);     // 设置要更新的头像字段 / Set the avatar field to update.
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
        // Get the logged-in user ID from the current thread context.
        Long userId = UserContext.getUserId();

        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }

        String redisKey = "user:info:" + userId;
        // 尝试从 Redis 中获取用户信息
        // Try to read the user information from Redis.
        User user = (User) redisTemplate.opsForValue().get(redisKey);
        if (user != null) {
            log.info("命中Redis缓存！用户ID: {}", userId);
            return Result.success(user);
        }
        //Redis未命中，从数据库中获取用户信息
        // If Redis misses, load the user information from the database.
        log.info("未命中Redis缓存！用户ID: {}", userId);
        user = this.getById(userId);

        // 把密码擦除再返回给前端
        // Clear the password before returning the user to the frontend.
        if (user != null) {
            user.setPassword(null);
            redisTemplate.opsForValue().set(redisKey, user,2, TimeUnit.HOURS);
        }

        return Result.success(user);
    }

    @Override
    public Result<String> changePassword(ChangePasswordDTO dto) {
        Long userId = UserContext.getUserId();
        if(userId == null){
            throw new RuntimeException("用户未登录");
        }

        User user = this.getById(userId);
        if(user == null){
            return Result.error( HttpServletResponse.SC_BAD_REQUEST,"用户不存在");
        }
        //效验旧密码是否正确
        // Validate whether the old password is correct.
        boolean oldPasswordMatches = BCrypt.checkpw(dto.getOldPassword(), user.getPassword());
        if(!oldPasswordMatches){
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"旧密码错误");
        }
        //新密码和旧密码不能一样
        //The New password cannot be the same as the old password.
        boolean sameAsOldPassword = BCrypt.checkpw(dto.getNewPassword(), user.getPassword());
        if(sameAsOldPassword){
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"新密码和旧密码不能一样");
        }
        //加新密码
       String newHashPassword = BCrypt.hashpw(dto.getNewPassword(),BCrypt.gensalt());
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setPassword(newHashPassword);
        boolean success = this.updateById(updateUser);
        if(!success){
            return Result.error(HttpServletResponse.SC_BAD_REQUEST,"密码修改失败");
        }
        //删除Redis缓存
        // Delete the user information cache.
        String redisKey = "user:info:" + userId;
        redisTemplate.delete(redisKey);

        return Result.success("密码修改成功");
    }
}
