package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户头像上传参数")
public class UserAvatarUploadDTO {
    private String avatar;
}
