package com.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分页查询参数实体")
public class PageQuery {

    @Schema(description = "当前页码", defaultValue = "1")
    private Integer pageNo = 1; // 给个默认值 1

    @Schema(description = "每页数量", defaultValue = "9")
    private Integer pageSize = 9; // 配合你前端的九宫格，默认给 9

    @Schema(description = "排序字段")
    private String sortBy;

    @Schema(description = "是否升序")
    private Boolean isAsc;

    // 💡 以后你想加搜索功能，直接往这里面塞字段就行，比如：
    // @Schema(description = "笔记标题搜索关键字")
    // private String title;
}