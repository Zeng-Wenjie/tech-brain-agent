package com.agent.service;

import com.agent.entity.Result;
import com.agent.entity.User;
import com.agent.entity.dto.ChangePasswordDTO;
import com.agent.entity.dto.UserInformationDTO;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public interface UserInformationService extends IService<User> {
    Result<String> userInformation(UserInformationDTO dto);

    Result<String> uploadAvatar(MultipartFile file);

    Result<User> getCurrentUserInfo();

    Result<String> changePassword(@Valid ChangePasswordDTO dto);
}
