package com.slb.mining_backend.modules.device.entity;

import com.slb.mining_backend.modules.device.enums.CommandStatus;
import com.slb.mining_backend.modules.device.enums.CommandType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备远程控制指令实体类
 */
@Data
public class DeviceRemoteCommand {
    private Long id;
    private String commandId;
    private Long userId;
    private String deviceId;
    private CommandType commandType;
    private CommandStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime executedAt;
    private LocalDateTime expiresAt;
    private String errorMessage;
}

