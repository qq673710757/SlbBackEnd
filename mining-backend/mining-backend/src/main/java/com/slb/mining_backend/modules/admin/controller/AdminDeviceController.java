package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.service.AdminDeviceService;
import com.slb.mining_backend.modules.admin.vo.AdminDeviceListItemVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/devices")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/设备", description = "管理员设备列表查询")
public class AdminDeviceController {

    private final AdminDeviceService deviceService;

    public AdminDeviceController(AdminDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @Operation(summary = "设备列表（全部设备）")
    public ApiResponse<PageVo<AdminDeviceListItemVo>> listDevices(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "关键词（设备ID/名称）", example = "DEV-001")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "设备状态（ONLINE/OFFLINE）", example = "ONLINE")
            @RequestParam(required = false) String status,
            @Parameter(description = "设备归属用户ID", example = "1001")
            @RequestParam(required = false) Long ownerUid) {
        return ApiResponse.ok(deviceService.listDevices(page, size, keyword, status, ownerUid));
    }
}
