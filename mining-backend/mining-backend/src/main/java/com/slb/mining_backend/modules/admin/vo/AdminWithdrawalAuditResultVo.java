package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminWithdrawalAuditResultVo {
    private Long withdrawId;
    private String status;
    private LocalDateTime auditTime;
    private String auditor;
}
