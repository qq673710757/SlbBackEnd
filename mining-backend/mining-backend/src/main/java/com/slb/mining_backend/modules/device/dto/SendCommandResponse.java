package com.slb.mining_backend.modules.device.dto;

import lombok.Data;

/**
 * 发送远程控制指令响应
 */
@Data
public class SendCommandResponse {
    private String commandId;
    private String status;
    private Long expiresAt;
}

