package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.admin.service.AdminUserService;
import com.slb.mining_backend.modules.admin.vo.AdminUserDetailVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/用户", description = "用户详情与邀请数据查询")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{uid}")
    @Operation(summary = "用户详情（含邀请用户与佣金）")
    public ApiResponse<AdminUserDetailVo> getUserDetail(
            @Parameter(description = "用户 ID", required = true, example = "1001")
            @PathVariable Long uid,
            @Parameter(description = "邀请列表页码", example = "1")
            @RequestParam(defaultValue = "1") int invitePage,
            @Parameter(description = "邀请列表每页数量", example = "10")
            @RequestParam(defaultValue = "10") int inviteSize) {
        return ApiResponse.ok(userService.getUserDetail(uid, invitePage, inviteSize));
    }
}
