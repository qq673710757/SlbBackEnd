package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 余额查询接口视图对象
 */
@Data
@Schema(description = "余额查询结果视图对象 / Balance query view object")
public class BalanceVo {

    @Schema(description = "当前可用 CAL 余额。/ Current available CAL balance.", example = "123.456")
    private BigDecimal calBalance;

    @Schema(description = "当前可提现现金余额（CNY）。/ Current withdrawable cash balance in CNY.", example = "789.01")
    private BigDecimal cashBalance;

    @Schema(description = "CAL 对 CNY 汇率。/ Exchange rate of CAL to CNY.", example = "0.85")
    private BigDecimal calToCnyRate;

    @Schema(description = "历史累计总收益（折算为 CNY 或统一单位）。/ Historical total earnings.", example = "1024.50")
    private BigDecimal totalEarnings;

    @Schema(description = "历史累计已提现金额。/ Total amount already withdrawn.", example = "512.25")
    private BigDecimal totalWithdrawn;
}
