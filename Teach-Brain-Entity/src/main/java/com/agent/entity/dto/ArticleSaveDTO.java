package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@Schema(description = "笔记保存页参数")
public class ArticleSaveDTO {
    private String title;//用户确认要修改的标题
    private String content;//AI生成的笔记内容
    private String tags;//标签
}
