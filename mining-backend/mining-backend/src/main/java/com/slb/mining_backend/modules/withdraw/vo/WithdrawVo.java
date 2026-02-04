package com.slb.mining_backend.modules.withdraw.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "提现记录视图对象 / Withdrawal record view object")
public class WithdrawVo {

    @Schema(description = "提现单 ID。为避免前端精度丢失，建议按字符串处理。/ Withdrawal ID; JS clients should handle as string.", example = "10001")
    private Long withdrawId;

    @Schema(description = "提现金额，单位为 currency 字段指定的币种。/ Withdrawal amount in currency specified by 'currency'.", example = "100.50")
    private BigDecimal amount;

    @Schema(description = "收款账户类型，例如 alipay 或 usdt。/ Target account type, e.g. 'alipay' or 'usdt'.", example = "alipay")
    private String accountType;

    @Schema(description = "收款账户信息，例如支付宝账号或链上地址。/ Target account identifier, e.g. Alipay account or on-chain address.", example = "13800000000")
    private String account;

    @Schema(description = "提现状态，具体取值含义由业务约定（如 0=待审核，1=通过，2=拒绝等）。/ Withdrawal status; meaning of values defined by business (e.g. 0=pending,1=approved,2=rejected).", example = "0")
    private Integer status;

    @Schema(description = "创建时间（提交申请时间）。/ Creation time (application submitted time).", example = "2025-11-18T10:00:00")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间，例如审核通过或拒绝时间。/ Last updated time, e.g. approval or rejection time.", example = "2025-11-18T10:30:00")
    private LocalDateTime updateTime;

    @Schema(description = "管理员审核备注或用户备注信息。/ Admin review remark or user comment.", example = "审核通过，预计 30 分钟内到账。")
    private String remark;

    /** 提现币种，如 CNY / CAL */
    @Schema(description = "提现币种：支付宝提现时可为 CNY 或 CAL。/ Withdrawal currency: for Alipay, 'CNY' or 'CAL'.", example = "CNY")
    private String currency;

    /** 预留字段，如未来扩展链上提现网络（当前支付宝模式下可为空） */
    @Schema(description = "预留字段：如未来扩展链上提现网络。目前支付宝提现该字段通常为空。/ Reserved field for future on-chain withdrawals; usually null for Alipay.", example = "")
    private String transferNetwork;

    @Schema(description = "本次提现收取的手续费（CNY）。/ Withdrawal fee in CNY for this request.", example = "1.23")
    private BigDecimal feeAmount;

    @Schema(description = "当 currency=CAL 时的 CAL 数量（折算得到或原始扣减数量）。/ CAL amount for CAL withdrawals.", example = "2.50")
    private BigDecimal calAmount;

    @Schema(description = "当 currency=CAL 时的等价 CNY 金额（与 amount 一致）。/ CNY equivalent amount for CAL withdrawals.", example = "10.78")
    private BigDecimal cnyAmount;

    @Schema(description = "当 currency=CAL 时的 XMR/CNY 汇率（申请时刻）。/ XMR to CNY rate at request time for CAL withdrawals.", example = "3471.32")
    private BigDecimal xmrToCnyRate;

    @Schema(description = "当 currency=CAL 时的 CAL/CNY 汇率（申请时刻）。/ CAL to CNY rate at request time for CAL withdrawals.", example = "3.47132000")
    private BigDecimal calToCnyRate;
}