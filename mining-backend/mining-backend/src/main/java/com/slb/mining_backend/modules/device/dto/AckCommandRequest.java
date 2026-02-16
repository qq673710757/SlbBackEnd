package com.slb.mining_backend.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认指令执行请求
 */
@Data
public class AckCommandRequest {
    @NotBlank(message = "指令ID不能为空")
    private String commandId;
    
    @NotNull(message = "执行结果不能为空")
    private Boolean success;
    
    private String error; // 可选，失败原因
    
    @NotNull(message = "执行时间不能为空")
    private Long executedAt; // 秒级时间戳
}

