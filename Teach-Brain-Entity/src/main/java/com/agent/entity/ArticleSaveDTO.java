package com.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
@Data
public class ArticleSaveDTO {
    private String title;//用户确认要修改的标题
    private String content;//AI生成的笔记内容
    private String tags;//标签
}
