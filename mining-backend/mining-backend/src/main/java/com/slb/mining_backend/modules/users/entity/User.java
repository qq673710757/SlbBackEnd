package com.slb.mining_backend.modules.users.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：users
 */
@Data
public class User implements Serializable {

    // 序列化版本号
    private static final long serialVersionUID = 1L;

    private Long id;

    private String userName;
    private String passwordHash;
    private String salt;

    private String alipayAccount;
    private String alipayName;
    private LocalDateTime alipayAccountUpdatedAt;
    private String inviteCode;
    private BigDecimal totalEarnings;
    private BigDecimal totalWithdrawn;
    private Long inviterId; // 邀请人的用户ID

    /**
     * 来源渠道：web / app / client
     */
    private String regInto;

    /**
     * CAL 余额
     */
    private BigDecimal calBalance;

    /**
     * 现金余额（CNY）
     */
    private BigDecimal cashBalance;

    /**
     * 1=正常 0=禁用
     */
    private Integer status;

    // --- 新增字段_冻结余额 ---
    private BigDecimal frozenCashBalance;

    // --- 新增字段_权限 ---
    private String role;

    private String phone;

    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String settlementCurrency;

    /**
     * 已到账未提现的 XMR 余额。用于记录用户挖矿收入后尚未提现的余额。
     */
    private BigDecimal xmrBalance;

    /**
     * 正在提现冻结中的 XMR 余额。提交提现申请后将对应金额冻结，直至审核完成。
     */
    private BigDecimal frozenXmr;

    /**
     * 用户累计产出的 XMR（含已提现和未提现部分）。用于快速查询累计产量。
     */
    private BigDecimal totalEarnedXmr;

    /**
     * 账户是否被冻结。1 表示账户被冻结，禁止提现；0 表示正常。
     */
    private Integer statusFrozen;

    /**
     * 唯一的工人 ID（workerId），在用户注册时生成，用于挖矿收益隔离和矿池数据统计关联。
     */
    private String workerId;
}
