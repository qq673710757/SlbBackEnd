package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.dto.AnnouncementCreateDto;
import com.slb.mining_backend.modules.admin.dto.AnnouncementUpdateDto;
import com.slb.mining_backend.modules.admin.service.AdminAnnouncementService;
import com.slb.mining_backend.modules.system.entity.Announcement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/announcements")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/公告", description = "管理员创建/编辑/上下线/删除公告")
public class AdminAnnouncementController {

    private final AdminAnnouncementService announcementService;

    public AdminAnnouncementController(AdminAnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    @Operation(
            summary = "分页查询公告（管理员）",
            description = """
                    管理员分页查询所有公告（含草稿/下线）。

                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/announcements?page=1&size=10" \
                      -H "Authorization: Bearer <admin-token>"
                    """
    )
    public ApiResponse<PageVo<Announcement>> list(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(announcementService.listAll(page, size));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "公告详情（管理员）",
            description = """
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/announcements/1" \
                      -H "Authorization: Bearer <admin-token>"
                    """
    )
    public ApiResponse<Announcement> detail(
            @Parameter(description = "公告 ID", required = true, example = "1")
            @PathVariable Integer id) {
        return ApiResponse.ok(announcementService.getById(id));
    }

    @PostMapping
    @Operation(
            summary = "新增公告",
            description = """
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/admin/announcements" \
                      -H "Authorization: Bearer <admin-token>" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "title": "系统升级维护通知",
                        "content": "今晚 00:00-02:00 系统维护",
                        "isImportant": true,
                        "status": 1
                      }'
                    """
    )
    public ApiResponse<Void> create(
            @Parameter(description = "公告创建请求体", required = true)
            @Valid @RequestBody AnnouncementCreateDto dto) {
        announcementService.create(dto);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "编辑公告",
            description = """
                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/admin/announcements/1" \
                      -H "Authorization: Bearer <admin-token>" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "title": "系统升级维护通知（更新）",
                        "content": "维护时间调整为 01:00-03:00",
                        "isImportant": true,
                        "status": 1
                      }'
                    """
    )
    public ApiResponse<Void> update(
            @Parameter(description = "公告 ID", required = true, example = "1")
            @PathVariable Integer id,
            @Parameter(description = "公告更新请求体", required = true)
            @Valid @RequestBody AnnouncementUpdateDto dto) {
        announcementService.update(id, dto);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    @Operation(
            summary = "公告上下线",
            description = """
                    status: 1=上线, 0=下线

                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/admin/announcements/1/status?status=1" \
                      -H "Authorization: Bearer <admin-token>"
                    """
    )
    public ApiResponse<Void> updateStatus(
            @Parameter(description = "公告 ID", required = true, example = "1")
            @PathVariable Integer id,
            @Parameter(description = "状态：1=上线, 0=下线", required = true, example = "1")
            @RequestParam Integer status) {
        announcementService.updateStatus(id, status);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "删除公告",
            description = """
                    示例请求 (cURL):
                    curl -X DELETE "http://localhost:8080/api/v1/admin/announcements/1" \
                      -H "Authorization: Bearer <admin-token>"
                    """
    )
    public ApiResponse<Void> delete(
            @Parameter(description = "公告 ID", required = true, example = "1")
            @PathVariable Integer id) {
        announcementService.delete(id);
        return ApiResponse.ok();
    }
}
