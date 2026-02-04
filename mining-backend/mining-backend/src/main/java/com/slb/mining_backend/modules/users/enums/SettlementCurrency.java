package com.slb.mining_backend.modules.users.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户收益结算偏好。
 */
@Schema(description = "收益结算币种枚举：CAL 或 CNY。/ Settlement currency enum: CAL or CNY.")
public enum SettlementCurrency {
    @Schema(description = "以 CAL 代币结算收益。/ Settle earnings in CAL token.")
    CAL,

    @Schema(description = "以人民币(CNY)结算收益。/ Settle earnings in Chinese Yuan (CNY).")
    CNY;

    public static SettlementCurrency fromCode(String code) {
        if (code == null) {
            return CAL;
        }
        for (SettlementCurrency value : SettlementCurrency.values()) {
            if (value.name().equalsIgnoreCase(code)) {
                return value;
            }
        }
        return CAL;
    }
}
