package com.slb.mining_backend.modules.device.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 远程控制状态
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoteControlStatus {
    private String cpuCommand;   // start_cpu | stop_cpu | none
    private String gpuCommand;   // start_gpu | stop_gpu | none
    private String commandId;    // UUID
    private Long timestamp;      // 秒级时间戳
    private Long expiresAt;      // 秒级时间戳
    
    public boolean hasCommands() {
        return (cpuCommand != null && !cpuCommand.equals("none"))
            || (gpuCommand != null && !gpuCommand.equals("none"));
    }
}

