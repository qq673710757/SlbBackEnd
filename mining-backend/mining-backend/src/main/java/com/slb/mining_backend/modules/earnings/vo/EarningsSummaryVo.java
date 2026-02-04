package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 累计收益总览视图对象。
 * 用于在前端展示按来源（GPU 挖矿 / CPU 挖矿 / 邀请收益 / 系统补偿 / 系统激励）
 * 以及按 CAL / CNY 两个维度汇总的累计收益数据。
 */
@Data
@Schema(description = "累计收益总览视图对象 / Aggregated earnings summary view object")
public class EarningsSummaryVo {

    @Schema(description = "用户的收益结算偏好币种（后端驱动展示用）。/ User settlement currency preference for backend-driven UI.", example = "CAL")
    private String settlementCurrency;

    @Schema(description = "按 settlementCurrency 选出的主展示金额（CNY 对应 totalCny，CAL 对应 totalCal）。/ Primary amount chosen by settlementCurrency.", example = "1104.0000")
    private BigDecimal estimatedPayout;

    // GPU 挖矿累计收益
    @Schema(description = "GPU 挖矿累计收益（CAL）。/ Total CAL earnings from GPU mining.", example = "123.456")
    private BigDecimal gpuCalTotal;

    @Schema(description = "GPU 挖矿累计收益折合 CNY。/ Total CNY-equivalent earnings from GPU mining.", example = "1000.00")
    private BigDecimal gpuCnyTotal;

    @Schema(description = "GPU 挖矿累计收益（实际以 CNY 结算入账的部分）。/ Settled CNY earnings from GPU mining (actual CNY settlement).", example = "0.00")
    private BigDecimal gpuCnySettledTotal;

    // CPU 挖矿累计收益
    @Schema(description = "CPU 挖矿累计收益（CAL）。/ Total CAL earnings from CPU mining.", example = "12.345")
    private BigDecimal cpuCalTotal;

    @Schema(description = "CPU 挖矿累计收益折合 CNY。/ Total CNY-equivalent earnings from CPU mining.", example = "100.00")
    private BigDecimal cpuCnyTotal;

    @Schema(description = "CPU 挖矿累计收益（实际以 CNY 结算入账的部分）。/ Settled CNY earnings from CPU mining (actual CNY settlement).", example = "0.00")
    private BigDecimal cpuCnySettledTotal;

    // 邀请收益累计
    @Schema(description = "邀请收益累计（CAL）。/ Total CAL earnings from invitation commissions.", example = "10.00000000")
    private BigDecimal inviteCalTotal;

    @Schema(description = "邀请收益累计折合 CNY。/ Total CNY-equivalent earnings from invitation commissions.", example = "80.00")
    private BigDecimal inviteCnyTotal;

    @Schema(description = "邀请收益累计（实际以 CNY 结算入账的部分）。/ Settled CNY earnings from invitation commissions (actual CNY settlement).", example = "0.00")
    private BigDecimal inviteCnySettledTotal;

    // 被邀请者奖励累计（invited / invitee bonus）
    @Schema(description = "被邀请者奖励累计（CAL 等值）。/ Total CAL-equivalent earnings for invitee bonuses.", example = "1.00000000")
    private BigDecimal invitedCalTotal;

    @Schema(description = "被邀请者奖励累计折合 CNY。/ Total CNY-equivalent earnings for invitee bonuses.", example = "8.00")
    private BigDecimal invitedCnyTotal;

    @Schema(description = "被邀请者奖励累计（实际以 CNY 结算入账的部分）。/ Settled CNY earnings for invitee bonuses (actual CNY settlement).", example = "0.00")
    private BigDecimal invitedCnySettledTotal;

    // 系统补偿累计
    @Schema(description = "系统补偿累计收益（CAL）。/ Total CAL earnings from system compensations.", example = "5.00000000")
    private BigDecimal compensationCalTotal;

    @Schema(description = "系统补偿累计收益折合 CNY。/ Total CNY-equivalent earnings from system compensations.", example = "40.00")
    private BigDecimal compensationCnyTotal;

    @Schema(description = "系统补偿累计收益（实际以 CNY 结算入账的部分）。/ Settled CNY earnings from system compensations (actual CNY settlement).", example = "0.00")
    private BigDecimal compensationCnySettledTotal;

    // 系统激励累计
    @Schema(description = "系统激励累计收益（CAL）。/ Total CAL earnings from system incentives.", example = "3.00000000")
    private BigDecimal incentiveCalTotal;

    @Schema(description = "系统激励累计收益折合 CNY。/ Total CNY-equivalent earnings from system incentives.", example = "24.00")
    private BigDecimal incentiveCnyTotal;

    @Schema(description = "系统激励累计收益（实际以 CNY 结算入账的部分）。/ Settled CNY earnings from system incentives (actual CNY settlement).", example = "0.00")
    private BigDecimal incentiveCnySettledTotal;

    // 汇总
    @Schema(description = "所有来源累计 CAL 收益汇总。/ Total CAL earnings from all sources.", example = "153.80100000")
    private BigDecimal totalCal;

    @Schema(description = "所有来源累计 CNY 收益汇总。/ Total CNY-equivalent earnings from all sources.", example = "1244.00")
    private BigDecimal totalCny;

    @Schema(description = "所有来源累计收益汇总（实际以 CNY 结算入账的部分，非折合）。/ Total settled CNY earnings from all sources (actual CNY settlement).", example = "0.00")
    private BigDecimal totalCnySettled;

    @Schema(description = "累计总收益（实际入账到 CAL 钱包的部分）。来源：users.total_earnings（updateUserWallet 累加）。/ Total CAL credited to wallet (from users.total_earnings).", example = "138.00000000")
    private BigDecimal totalCalCredited;
}


