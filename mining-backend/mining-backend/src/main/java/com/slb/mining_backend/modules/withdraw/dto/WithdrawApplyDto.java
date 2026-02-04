package com.slb.mining_backend.modules.withdraw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "提现申请请求体 / Withdrawal application request body")
public class WithdrawApplyDto {

    @NotNull(message = "提现金额不能为空")
    @DecimalMin(value = "0.01", message = "提现金额必须大于0")
    @Schema(description = """
            提现金额（总扣金额）。其单位由 amountUnit 决定：
            - amountUnit=CNY（或不传，兼容旧调用）：amount 表示 CNY 总扣金额（含手续费计费基数）；
            - amountUnit=CAL：amount 表示 CAL 扣款数量；系统会按 1CAL=calXmrRatio*XMR 与 XMR/CNY 汇率折算等价 CNY（cnyGross）作为最小金额校验与手续费计费基数，落库仍以 CNY 金额存储以兼容管理端审核逻辑。
            注意：后端严禁“根据数值大小猜测单位”，必须由 amountUnit（或默认）决定。
            """, example = "100.50")
    private BigDecimal amount;

    @Schema(description = "amount 的单位：CNY 或 CAL。可选；不传表示兼容旧逻辑按 CNY 解释。/ Unit for 'amount': CNY|CAL (optional, default CNY).", example = "CNY", allowableValues = {"CNY", "CAL"})
    private String amountUnit;

    @NotBlank(message = "账户类型不能为空")
    @Schema(description = "收款账户类型：目前仅支持 alipay。/ Target account type: currently only 'alipay' is supported.", example = "alipay")
    private String accountType; // 目前只支持 "alipay"

    /**
     * 提现模式 / 币种：
     * - 对于支付宝提现（accountType=alipay）：
     *   - CNY：从用户现金余额（cash_balance）中扣减并提现到支付宝；
     *   - CAL：根据 1CAL=0.001XMR 和实时 XMR/CNY 汇率，将 CAL 等值折算为 CNY 提现到支付宝；
     *
     * 若前端未传该字段，则默认按 CNY 方式处理。
     *
     * 注意：当 amountUnit=CAL 时，amountUnit 优先级更高，currency 字段将被忽略（提现扣款来源强制为 CAL）。
     */
    @Schema(description = "提现币种/模式：对于支付宝提现可为 CNY 或 CAL，默认 CNY。/ Withdrawal currency or mode: for Alipay, 'CNY' or 'CAL' (default 'CNY').", example = "CNY")
    private String currency;

    @NotBlank(message = "收款账户不能为空")
    @Schema(description = "收款账户信息，例如支付宝账号。/ Target account identifier, e.g. Alipay account.", example = "13800000000")
    private String account;
}