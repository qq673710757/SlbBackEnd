package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体：用户 Monero 子地址。
 *
 * 对应表 xmr_user_addresses，用于记录每个用户从主钱包派生出的子地址。
 * 每个子地址拥有唯一的 minor index，作为挖矿收益汇聚的目标地址。
 */
@Data
public class XmrUserAddress implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键自增 ID */
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** Monero 钱包的账户索引(major index)，通常为 0 */
    private Integer accountIndex;

    /** 子地址索引(minor index) */
    private Integer subaddressIndex;

    /** 子地址(Base58 格式) */
    private String subaddress;

    /** 标签，通常存储为用户的唯一标识或备注 */
    private String label;

    /** 是否启用此地址 */
    private Boolean isActive;

    /** 创建时间 */
    private LocalDateTime createdTime;
}