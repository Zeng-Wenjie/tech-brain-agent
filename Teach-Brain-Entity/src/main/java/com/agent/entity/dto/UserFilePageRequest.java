package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 用户文件分页查询参数。
 *
 * <p>适用场景：承载当前登录用户查询自己文件列表时的分页参数、文件名关键词、文件分类、扩展名和上传时间范围筛选条件。</p>
 * <p>调用链：UserFileController.page 接收本对象 -> UserFileService.pageMyFiles 从 UserContext 读取当前用户 ID
 * -> UserFileServiceImpl 构造 MyBatis-Plus 分页查询 -> user_file 表。</p>
 * <p>边界说明：本对象不包含 userId，前端不能通过传 userId 查询别人文件；本对象只作为查询入参，不执行 SQL，不修改数据库结构。</p>
 */
@Data // 使用 Lombok 生成 getter/setter。
@Schema(description = "用户文件分页查询参数")
public class UserFilePageRequest { // 用户文件列表查询入参。

    @Schema(description = "当前页码，默认 1", example = "1")
    private Integer pageNum = 1; // 页码，Service 层会兜底修正非法值。

    @Schema(description = "每页数量，默认 10，最大 100", example = "10")
    private Integer pageSize = 10; // 每页数量，Service 层会限制最大 100。

    @Schema(description = "文件名关键词，按 original_name 模糊查询", example = "需求文档")
    private String keyword; // 文件原始名称关键词。

    @Schema(description = "文件分类：IMAGE、DOCUMENT、OTHER", example = "DOCUMENT")
    private String fileType; // 文件分类筛选。

    @Schema(description = "文件扩展名，例如 pdf、docx、png", example = "pdf")
    private String fileExt; // 文件扩展名筛选。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持查询参数传入标准日期时间。
    @Schema(description = "上传开始时间，格式示例：2026-05-29T00:00:00", example = "2026-05-29T00:00:00")
    private LocalDateTime startTime; // create_time 查询起始时间。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持查询参数传入标准日期时间。
    @Schema(description = "上传结束时间，格式示例：2026-05-29T23:59:59", example = "2026-05-29T23:59:59")
    private LocalDateTime endTime; // create_time 查询结束时间。
}
