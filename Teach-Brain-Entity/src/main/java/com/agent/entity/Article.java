package com.agent.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article")
public class Article {
    @TableId(type = IdType.AUTO)
    private Long id;//主键ID
    private String title;//笔记标题
    private String content;//详情内容
    private Integer sourceType;//来源类型
    private String tags;//标签
    // 插入时自动填充时间
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    // 更新时自动填充时间
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
//    逻辑删除字段
    private Boolean deleteFlag;

}
