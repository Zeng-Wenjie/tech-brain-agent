package com.agent.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article")
public class Article {
    @TableId(type = IdType.AUTO)
    private Long id;//主键ID / Primary key ID.
    @TableField(fill = FieldFill.INSERT)
    private Long userId;//用户ID / User ID.
    private String title;//笔记标题 / Note title.
    private String content;//详情内容 / Detailed content.
    private Integer sourceType;//来源类型 / Source type.
    private String tags;//标签 / Tags.
    // 插入时自动填充时间
    // Automatically fill the time on insert.
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    // 更新时自动填充时间
    // Automatically fill the time on update.
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
//    逻辑删除字段
//    Logical delete field.
    private Boolean deleteFlag;

}
