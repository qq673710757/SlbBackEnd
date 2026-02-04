package com.slb.mining_backend.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
@Schema(description = "系统运行状态视图对象 / System runtime status view object")
public class SystemStatusVo {

    @Schema(description = "注册的设备总数。/ Total number of registered devices.", example = "120")
    private Long totalDevices;

    @Schema(description = "当前在线设备数量。/ Number of devices currently online.", example = "80")
    private Long onlineDevices;

    @Schema(description = "注册用户总数。/ Total number of registered users.", example = "1000")
    private Long totalUsers;

    @Schema(description = "当前活跃用户数（例如最近 24 小时有登录或算力）。/ Number of active users.", example = "200")
    private Long activeUsers;

    @Schema(description = "全网总 CPU 算力，单位 H/s。/ Total CPU hashrate in H/s.", example = "500000")
    private BigDecimal totalCpuHashrate;

    @Schema(description = "全网总 GPU 算力，单位 H/s。/ Total GPU hashrate in H/s.", example = "2000000")
    private BigDecimal totalGpuHashrate;

    @Schema(description = "CAL 对 CNY 汇率。/ CAL to CNY exchange rate.", example = "0.85")
    private BigDecimal calToCnyRate;

    @Schema(description = "服务器总体状态描述，例如 OK/DEGRADED/MAINTENANCE。/ Overall server status, e.g. OK/DEGRADED/MAINTENANCE.", example = "OK")
    private String serverStatus;

    @Schema(description = "是否计划中的维护窗口（true=近期有维护计划）。/ Whether a maintenance window is planned.", example = "false")
    private Boolean maintenancePlanned;
}
