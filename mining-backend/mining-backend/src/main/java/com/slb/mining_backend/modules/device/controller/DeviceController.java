package com.slb.mining_backend.modules.device.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;

import com.slb.mining_backend.modules.device.dto.AckCommandRequest;
import com.slb.mining_backend.modules.device.dto.DeviceGpuHashrateReportReqDto;
import com.slb.mining_backend.modules.device.dto.DeviceHashrateReportReqDto;
import com.slb.mining_backend.modules.device.dto.DeviceRegisterReqDto;
import com.slb.mining_backend.modules.device.dto.DeviceUpdateReqDto;
import com.slb.mining_backend.modules.device.dto.SendCommandRequest;
import com.slb.mining_backend.modules.device.dto.SendCommandResponse;
import com.slb.mining_backend.modules.device.service.DeviceService;
import com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSeriesVo;
import com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSnapshotVo;
import com.slb.mining_backend.modules.device.vo.DeviceHashratePointVo;
import com.slb.mining_backend.modules.device.vo.DeviceVo;
import com.slb.mining_backend.modules.device.vo.HashrateSummaryVo;
import com.slb.mining_backend.common.vo.PageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "用户端/设备", description = "用于管理用户设备的接口，包括注册、心跳上报、查询列表/详情和删除等操作")
public class DeviceController {

    private final DeviceService deviceService;

    @Autowired
    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    @Operation(
            summary = "注册新设备",
            description = """
                    当前登录用户绑定一台新的挖矿设备，注册成功后返回设备标识和详细信息。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/devices" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "deviceName": "My Mining Rig #1",
                        "deviceType": "gpu",
                        "deviceInfo": {
                          "os": "Windows 11",
                          "cpu": "Intel i7"
                        }
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "deviceId": "device-123456",
                        "deviceName": "My Mining Rig #1",
                        "status": 1
                        // ... 其他字段略
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<DeviceVo> registerDevice(
            @Parameter(description = "设备注册请求体，包含设备唯一标识、名称等信息", required = true)
            @Valid @RequestBody DeviceRegisterReqDto dto,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DeviceVo result = deviceService.registerDevice(dto, userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @PostMapping("/{deviceId}/hashrate-report")
    @Operation(
            summary = "设备算力上报（分钟级，仅用于展示）",
            description = """
                    设备端建议每分钟上报一次 CPU/GPU 算力数据，用于给用户展示“参考算力”。
                    服务端会将同一设备同一分钟内的重复上报做幂等覆盖（minute bucket upsert）。

                    注意：设备是否在线将以“最近 N 分钟是否收到算力上报”为准（N 由 app.devices.offline-threshold-minutes 控制）。
                    """
    )
    public ApiResponse<Void> reportHashrate(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "算力上报请求体（分钟级）", required = true)
            @Valid @RequestBody DeviceHashrateReportReqDto dto,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.reportHashrate(deviceId, dto, userDetails.getUser().getId());
        return ApiResponse.ok();
    }

    @PostMapping("/{deviceId}/gpu-hashrate-report")
    @Operation(
            summary = "设备 GPU 明细算力上报（分钟级，仅用于展示）",
            description = """
                    设备端建议每分钟上报一次 GPU 明细算力数据，用于给用户展示每块显卡的趋势。
                    服务端会将同一设备同一分钟内同一 GPU index 的重复上报做幂等覆盖（minute bucket upsert）。
                    """
    )
    public ApiResponse<Void> reportGpuHashrate(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "GPU 明细算力上报请求体（分钟级）", required = true)
            @Valid @RequestBody DeviceGpuHashrateReportReqDto dto,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.reportGpuHashrateDetail(deviceId, dto, userDetails.getUser().getId());
        return ApiResponse.ok();
    }

    @GetMapping("/{deviceId}/hashrate-trend")
    @Operation(
            summary = "获取设备算力趋势（最近 N 分钟）",
            description = """
                    返回设备最近 N 分钟的分钟桶算力数据（按时间升序），用于前端绘制趋势图。
                    注意：该数据仅用于展示参考算力，不参与结算。
                    """
    )
    public ApiResponse<List<DeviceHashratePointVo>> getHashrateTrend(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "查询最近多少分钟，默认 60，最大 1440", example = "60")
            @RequestParam(defaultValue = "60") int minutes,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<DeviceHashratePointVo> points = deviceService.getDeviceHashrateTrend(deviceId, userDetails.getUser().getId(), minutes);
        return ApiResponse.ok(points);
    }

    @GetMapping("/{deviceId}/gpu-hashrate-trend")
    @Operation(
            summary = "获取设备 GPU 明细算力趋势（最近 N 分钟）",
            description = """
                    返回设备最近 N 分钟的 GPU 明细分钟桶数据（按 GPU index 分组），用于前端绘制趋势图。
                    注意：该数据仅用于展示参考算力，不参与结算。
                    """
    )
    public ApiResponse<List<DeviceGpuHashrateSeriesVo>> getGpuHashrateTrend(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "查询最近多少分钟，默认 60，最大 1440", example = "60")
            @RequestParam(defaultValue = "60") int minutes,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<DeviceGpuHashrateSeriesVo> series = deviceService.getDeviceGpuHashrateTrend(deviceId, userDetails.getUser().getId(), minutes);
        return ApiResponse.ok(series);
    }

    @GetMapping("/gpu-hashrate-latest")
    @Operation(
            summary = "获取所有设备 GPU 顺时算力列表",
            description = """
                    返回当前用户下所有设备的 GPU 顺时算力（每块 GPU 最近一分钟桶记录）。
                    注意：该数据仅用于展示参考算力，不参与结算。
                    """
    )
    public ApiResponse<List<DeviceGpuHashrateSnapshotVo>> getLatestGpuHashrate(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<DeviceGpuHashrateSnapshotVo> snapshots = deviceService.getLatestGpuHashrateSnapshots(userDetails.getUser().getId());
        return ApiResponse.ok(snapshots);
    }

    @GetMapping
    @Operation(
            summary = "获取我的设备列表",
            description = """
                    分页查询当前登录用户名下的所有设备，可按状态和创建时间等字段排序。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/devices?page=1&size=10" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "total": 2,
                        "page": 1,
                        "size": 10,
                        "list": [
                          {
                            "deviceId": "device-123456",
                            "deviceName": "My Mining Rig #1",
                            "status": 1
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<DeviceVo>> getDeviceList(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "设备状态，可选，具体取值含义见枚举说明")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "排序字段，默认 createTime", example = "createTime")
            @RequestParam(defaultValue = "createTime") String sortBy,
            @Parameter(description = "排序方式，asc 或 desc", example = "desc")
            @RequestParam(defaultValue = "desc") String orderBy,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<DeviceVo> result = deviceService.getDeviceList(userDetails.getUser().getId(), status, page, size, sortBy, orderBy);
        return ApiResponse.ok(result);
    }

    @GetMapping("/hashrate-summary")
    @Operation(
            summary = "算力汇总与实时收益",
            description = """
                    汇总当前登录用户所有在线设备的 CPU/GPU 算力，并基于平台当前总算力与汇率估算每小时/每日/每月的折合 CNY 收益。
                    """
    )
    public ApiResponse<HashrateSummaryVo> getHashrateSummary(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        HashrateSummaryVo result = deviceService.getHashrateSummary(userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @GetMapping("/{deviceId}")
    @Operation(
            summary = "获取设备详情",
            description = """
                    根据设备 ID 查询当前用户名下的设备详细信息。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/devices/device-123456" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "deviceId": "device-123456",
                        "deviceName": "My Mining Rig #1",
                        "status": 1,
                        "createTime": "2025-11-18T10:00:00"
                        // ... 其他字段略
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<DeviceVo> getDeviceDetail(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DeviceVo result = deviceService.getDeviceDetail(deviceId, userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @PutMapping("/{deviceId}")
    @Operation(
            summary = "修改设备信息",
            description = """
                    更新已绑定设备的名称等信息，仅设备所属用户可修改。
                    
                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/devices/device-123456" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "deviceName": "My Mining Rig #1 - Updated"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "deviceId": "device-123456",
                        "deviceName": "My Mining Rig #1 - Updated"
                        // ... 其他字段略
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<DeviceVo> updateDevice(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "设备更新请求体，例如修改名称等", required = true)
            @Valid @RequestBody DeviceUpdateReqDto dto,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DeviceVo result = deviceService.updateDevice(deviceId, dto, userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{deviceId}")
    @Operation(
            summary = "删除设备",
            description = """
                    将设备从当前用户名下解绑，删除后将不再统计该设备的收益数据。
                    
                    示例请求 (cURL):
                    curl -X DELETE "http://localhost:8080/api/v1/devices/device-123456" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> deleteDevice(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.deleteDevice(deviceId, userDetails.getUser().getId());
        return ApiResponse.ok();
    }

    @PostMapping("/{deviceId}/remote-control")
    @Operation(
            summary = "发送远程控制指令",
            description = """
                    Web或App端调用此接口向指定设备发送启停指令。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/devices/device-123456/remote-control" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{"commandType": "start_cpu"}'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "commandId": "cmd-uuid-123456",
                        "status": "pending",
                        "expiresAt": 1737014700
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<SendCommandResponse> sendRemoteControl(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "远程控制指令请求体", required = true)
            @Valid @RequestBody SendCommandRequest request,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SendCommandResponse result = deviceService.sendRemoteControl(deviceId, request, userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @PostMapping("/{deviceId}/remote-control/ack")
    @Operation(
            summary = "确认指令已执行",
            description = """
                    客户端执行完指令后调用此接口确认执行结果。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/devices/device-123456/remote-control/ack" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{"commandId": "cmd-uuid-123456", "success": true, "executedAt": 1737014450}'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> ackRemoteControl(
            @Parameter(description = "设备唯一标识", required = true, example = "device-123456")
            @PathVariable String deviceId,
            @Parameter(description = "确认执行请求体", required = true)
            @Valid @RequestBody AckCommandRequest request,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.ackRemoteControl(deviceId, request, userDetails.getUser().getId());
        return ApiResponse.ok();
    }
}
