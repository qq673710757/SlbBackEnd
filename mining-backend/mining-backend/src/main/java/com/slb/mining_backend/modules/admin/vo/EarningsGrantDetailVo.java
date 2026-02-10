package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收益发放明细 VO（每一笔发放记录）
 */
@Data
public class EarningsGrantDetailVo {
    /**
     * 交易哈希（唯一标识一次发放）
     */
    private String txHash;
    
    /**
     * 矿池来源：C3POOL / F2POOL / ANTPOOL
     */
    private String poolSource;
    
    /**
     * 币种（XMR/CFX/RVN）
     */
    private String coin;
    
    /**
     * 发放时间
     */
    private LocalDateTime payoutTime;
    
    /**
     * 发放给用户的总金额（XMR）
     */
    private BigDecimal userTotalXmr;
    
    /**
     * 发放给用户的总金额（CAL）
     */
    private BigDecimal userTotalCal;
    
    /**
     * 发放给用户的总金额（CNY）
     */
    private BigDecimal userTotalCny;
    
    /**
     * 平台抽成金额（XMR）
     */
    private BigDecimal platformXmr;
    
    /**
     * 平台抽成金额（CAL）
     */
    private BigDecimal platformCal;
    
    /**
     * 平台抽成金额（CNY）
     */
    private BigDecimal platformCny;
    
    /**
     * 总发放金额（用户+平台，XMR）
     */
    private BigDecimal totalXmr;
    
    /**
     * 总发放金额（用户+平台，CAL）
     */
    private BigDecimal totalCal;
    
    /**
     * 总发放金额（用户+平台，CNY）
     */
    private BigDecimal totalCny;
    
    /**
     * 原始币种数量（CFX/RVN/XMR 的原始挖矿数量）
     */
    private BigDecimal totalCoin;
    
    /**
     * 受益用户数量
     */
    private Integer userCount;
}

