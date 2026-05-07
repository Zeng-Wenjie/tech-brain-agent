package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "修改密码参数 / Change password request")
public class ChangePasswordDTO {
    @Schema(description = "旧密码 / Old password")
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;
    @Schema(description = "旧密码 / New password")
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
    @Schema(description = "确认密码 / Confirm password")
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
