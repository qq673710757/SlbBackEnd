package com.slb.mining_backend.modules.device.enums;

/**
 * 远程控制指令状态
 */
public enum CommandStatus {
    PENDING,   // 待执行
    EXECUTED,  // 已成功执行
    FAILED,    // 执行失败
    EXPIRED    // 已过期
}

