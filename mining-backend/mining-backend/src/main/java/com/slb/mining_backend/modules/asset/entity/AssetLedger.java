package com.slb.mining_backend.modules.asset.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产流水记录。
 * 对应 asset_ledger 表。
 */
@Data
public class AssetLedger implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    /** 货币类型：XMR / CNY / CAL （USDT 不再参与挖矿结算与提现，仅保留汇率查询） */
    private String currency;
    /** 对应的 XMR 数量（可为空） */
    private BigDecimal amountXmr;
    /** 对应的 CAL 数量（可为空） */
    private BigDecimal amountCal;
    /** 折合 CNY 金额（可为空） */
    private BigDecimal amountCny;
    /** 流水类型（mining_payout、withdrawal 等） */
    private String refType;
    /** 关联的业务记录 ID */
    private Long refId;
    /** 区块链交易哈希（若有） */
    private String txHash;
    /** 备注 */
    private String remark;
    /** 创建时间 */
    private LocalDateTime createdTime;
    /**
     * 业务发生时间（用于统一时间口径）。
     * 示例：矿池入账结算写入 xmr_wallet_incoming.ts；createdTime 仅作为“入库审计时间”。
     */
    private LocalDateTime eventTime;
}
