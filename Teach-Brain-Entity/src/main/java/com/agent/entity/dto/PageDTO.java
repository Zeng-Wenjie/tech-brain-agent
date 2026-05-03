package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "分页结果参数")
public class PageDTO<T> {
    @Schema(description = "总记录数")
    private Long total;
    @Schema(description = "总页数")
    private Long pages;
    @Schema(description = "结果列表")
    private List<T> list;


}