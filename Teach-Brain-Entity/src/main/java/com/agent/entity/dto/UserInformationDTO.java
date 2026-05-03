package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户信息参数")
public class UserInformationDTO {
    private String name;
    private String age;
    private Integer gender;//1为男，2为女
    private String email;
    private String phone;
}
