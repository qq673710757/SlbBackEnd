package com.slb.mining_backend.modules.users.dto.user;

import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 请求体：更新收益结算偏好。
 */
@Data
@Schema(description = "更新收益结算偏好的请求体 / Request body for updating earnings settlement preference")
public class SettlementPreferenceDTO {

    @NotNull
    @Schema(description = "结算币种，必填：CAL 或 CNY。/ Settlement currency, required: CAL or CNY.", example = "CAL")
    private SettlementCurrency settlementCurrency;

    /**
     * 仅管理员可指定要修改的用户 ID；普通用户必须为空，后端会自动使用自身 ID。
     */
    @Schema(description = "目标用户 ID，仅管理员可指定；普通用户请勿填写。为避免精度丢失，前端建议使用字符串处理。/ Target user ID, only admin can specify. For JS clients, handle as string to avoid precision loss.", example = "10001")
    private Long targetUserId;
}
