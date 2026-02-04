package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：xmr_pool_stats
 *
 * 用于记录用户在矿池中的最新挖矿统计数据，包括算力、shares、未支付 XMR 余额和历史已支付总额等。
 */
@Data
public class XmrPoolStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 用于挖矿的子地址 */
    private String subaddress;

    /** 最近一次抓取到的算力 (H/s) */
    private Long lastHashrate;

    /** 最近一次抓取到的 shares 数 */
    private Long lastReportedShares;

    /** 未支付余额 (XMR) */
    private BigDecimal unpaidXmr;

    /** 历史已支付总额 (XMR) */
    private BigDecimal paidXmrTotal;

    /** 数据来源 (矿池) */
    private String source;

    /** 最后更新时间 */
    private LocalDateTime lastUpdateTime;

    /** 用户工人 ID */
    private String workerId;
}