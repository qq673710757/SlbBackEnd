package com.slb.mining_backend.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WithdrawalAuditDto {
    @NotNull
    private Long withdrawId;
    @NotBlank
    private String action;
    private String remark;
}
