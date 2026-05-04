package com.agent.seriver.impl;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.mapper.UserInformationMapper;
import com.agent.seriver.UserInformationService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class UserInformationServiceImpl extends ServiceImpl<UserInformationMapper, User> implements UserInformationService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

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
}
