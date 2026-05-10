package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    // 会话主键。
    private Long id;

    // 会话所属用户。
    private Long userId;

    // 会话标题，默认取首条问题前20个字。
    private String title;

    // 会话创建时间。
    private LocalDateTime createTime;

    // 会话最后更新时间。
    private LocalDateTime updateTime;
}
