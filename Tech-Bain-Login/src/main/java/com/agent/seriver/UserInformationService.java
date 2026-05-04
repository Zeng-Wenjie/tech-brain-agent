package com.agent.seriver;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.UserAvatarUploadDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.stereotype.Service;

@Service
public interface UserInformationService extends IService<User> {
    Result<String> userInformation(UserInformationDTO dto);
}
