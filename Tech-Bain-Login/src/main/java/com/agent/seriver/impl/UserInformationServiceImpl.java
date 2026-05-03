package com.agent.seriver.impl;

import com.agent.entity.User;
import com.agent.entity.dto.UserInformationDTO;
import com.agent.mapper.UserInformationMapper;
import com.agent.seriver.UserInformationService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserInformationServiceImpl extends ServiceImpl<UserInformationMapper, User> implements UserInformationService {

    @Override
    public void userInformation(UserInformationDTO dto) {
        Long userId = UserContext.getUserId();// 获取用户id
        if(userId != null){
            User user = new User();
            BeanUtils.copyProperties(dto,user);
            user.setId(userId);
            boolean success = this.updateById(user);//同步
            if(!success){
                log.error("用户信息更新失败,UserId: {}",userId);
                throw new RuntimeException("保存用户信息失败");
            }
        }else throw new RuntimeException("无法获取当前用户信息，请重新登录");
    }
}
