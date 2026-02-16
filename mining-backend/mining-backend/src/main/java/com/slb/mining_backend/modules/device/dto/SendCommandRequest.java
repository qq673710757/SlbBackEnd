package com.slb.mining_backend.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送远程控制指令请求
 */
@Data
public class SendCommandRequest {
    @NotBlank(message = "指令类型不能为空")
    private String commandType; // start_cpu | stop_cpu | start_gpu | stop_gpu
}

