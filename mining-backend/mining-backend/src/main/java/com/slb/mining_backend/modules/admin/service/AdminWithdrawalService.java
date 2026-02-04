package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.vo.AdminWithdrawalListItemVo;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.withdraw.entity.Withdrawal;
import com.slb.mining_backend.modules.withdraw.mapper.WithdrawalMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminWithdrawalService {

    private final WithdrawalMapper withdrawalMapper;
    private final UserMapper userMapper;
    private final com.slb.mining_backend.modules.asset.service.AssetLedgerService assetLedgerService;

    /**
     * 1 CAL = calXmrRatio * XMR（例如 0.001）
     */
    @Value("${app.rates.cal-xmr-ratio:1.0}")
    private BigDecimal calXmrRatio;

    public AdminWithdrawalService(WithdrawalMapper withdrawalMapper, UserMapper userMapper,
                                  com.slb.mining_backend.modules.asset.service.AssetLedgerService assetLedgerService) {
        this.withdrawalMapper = withdrawalMapper;
        this.userMapper = userMapper;
        this.assetLedgerService = assetLedgerService;
    }

    @Transactional
    public void approveWithdrawal(Long withdrawalId, String adminRemark) {
        approveWithdrawal(withdrawalId, adminRemark, null);
    }

    @Transactional
    public void approveWithdrawal(Long withdrawalId, String adminRemark, Long adminUserId) {
        Withdrawal withdrawal = withdrawalMapper.findById(withdrawalId) // 管理员可以查询任何用户的
                .orElseThrow(() -> new BizException("提现记录不存在"));
        if (withdrawal.getStatus() != 0) {
            throw new BizException("该提现申请已被处理");
        }

        // 1. 更新提现单状态为“审核通过”
        withdrawal.setStatus(1); // 1: APPROVED
        withdrawal.setRemark(buildRemark("审核通过", adminRemark));
        withdrawal.setReviewTime(LocalDateTime.now());
        if (adminUserId != null) {
            withdrawal.setReviewedBy(adminUserId);
        }
        withdrawalMapper.update(withdrawal);

        // 2. 根据提现币种/模式，确认扣减并更新用户资产 & 资产流水
        String currency = withdrawal.getCurrency();
        BigDecimal amount = withdrawal.getAmount();
        BigDecimal xmrEquiv = withdrawal.getXmrEquivalent();

        if ("CNY".equalsIgnoreCase(currency)) {
            // CNY 余额提现：确认扣减冻结现金，并累计 total_withdrawn
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                userMapper.updateCashBalances(
                        withdrawal.getUserId(),
                        BigDecimal.ZERO,          // 可用不变
                        amount.negate(),          // 冻结减少
                        amount                    // 累计提现增加
                );
                try {
                    assetLedgerService.recordWithdrawal(
                            withdrawal.getUserId(),
                            "CNY",
                            null,
                            null,
                            amount.negate(),        // CNY 流出
                            withdrawal.getId()
                    );
                } catch (Exception ex) {
                    System.err.println("Failed to record withdrawal ledger: " + ex.getMessage());
                }
            }
        } else if ("CAL".equalsIgnoreCase(currency)) {
            // CAL 提现：在 apply 阶段已经扣减 CAL，这里只需记录资产流水和累计提现金额
            if (xmrEquiv != null && xmrEquiv.compareTo(BigDecimal.ZERO) > 0 && amount != null) {
                try {
                    if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BizException("CAL/XMR 换算比例未配置");
                    }
                    // 以 xmr_equivalent 为准：确保 reject/approve 的 CAL 复算与 apply 阶段扣减一致（尤其是 amountUnit=CAL 时）
                    // 若历史数据缺失 xmr_equivalent，则回退到 amount/rateAtRequest 复算。
                    BigDecimal xmrToCny = withdrawal.getRateAtRequest();
                    BigDecimal xmrUsed = (xmrEquiv != null && xmrEquiv.compareTo(BigDecimal.ZERO) > 0)
                            ? xmrEquiv
                            : ((xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0)
                            ? amount.divide(xmrToCny, 12, java.math.RoundingMode.HALF_UP)
                            : null);
                    if (xmrUsed == null || xmrUsed.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BizException("无法复算 XMR 等值，请检查提现记录");
                    }
                    BigDecimal calSpent = xmrUsed.divide(calXmrRatio, 8, java.math.RoundingMode.HALF_UP);
                    assetLedgerService.recordWithdrawal(
                            withdrawal.getUserId(),
                            "CAL",
                            xmrUsed.negate(),       // XMR 等值（审计用）
                            calSpent.negate(),      // CAL 流出
                            amount.negate(),        // 对应 CNY 流出
                            withdrawal.getId()
                    );
                    // 将本次提现金额计入累计已提现 CNY（不影响现金余额）
                    userMapper.updateCashBalances(
                            withdrawal.getUserId(),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            amount
                    );
                } catch (Exception ex) {
                    System.err.println("Failed to record withdrawal ledger: " + ex.getMessage());
                }
            }
        }
    }

    @Transactional
    public void rejectWithdrawal(Long withdrawalId, String adminRemark) {
        rejectWithdrawal(withdrawalId, adminRemark, null);
    }

    @Transactional
    public void rejectWithdrawal(Long withdrawalId, String adminRemark, Long adminUserId) {
        Withdrawal withdrawal = withdrawalMapper.findById(withdrawalId)
                .orElseThrow(() -> new BizException("提现记录不存在"));
        if (withdrawal.getStatus() != 0) {
            throw new BizException("该提现申请已被处理");
        }

        // 1. 更新提现单状态为“已拒绝”
        withdrawal.setStatus(2); // 2: REJECTED
        withdrawal.setRemark(buildRemark("审核拒绝", adminRemark));
        withdrawal.setReviewTime(LocalDateTime.now());
        if (adminUserId != null) {
            withdrawal.setReviewedBy(adminUserId);
        }
        withdrawalMapper.update(withdrawal);

        // 2. 根据币种回滚冻结或扣减的资产
        String currency = withdrawal.getCurrency();
        BigDecimal amount = withdrawal.getAmount();
        BigDecimal xmrEquiv = withdrawal.getXmrEquivalent();

        if ("CNY".equalsIgnoreCase(currency)) {
            // 回滚 CNY 冻结
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                userMapper.updateCashBalances(
                        withdrawal.getUserId(),
                        amount,                // 可用余额增加
                        amount.negate(),       // 冻结余额减少
                        BigDecimal.ZERO
                );
            }
        } else if ("CAL".equalsIgnoreCase(currency)) {
            // 回滚 CAL 扣减
            if (xmrEquiv != null && xmrEquiv.compareTo(BigDecimal.ZERO) > 0) {
                if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("CAL/XMR 换算比例未配置");
                }
                // 以 xmr_equivalent 为准，确保回滚的 CAL 数量与 apply 阶段扣减一致
                // 若历史数据缺失 xmr_equivalent，则回退到 amount/rateAtRequest 复算。
                BigDecimal xmrToCny = withdrawal.getRateAtRequest();
                BigDecimal xmrUsed = (xmrEquiv != null && xmrEquiv.compareTo(BigDecimal.ZERO) > 0)
                        ? xmrEquiv
                        : ((xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0 && amount != null && amount.compareTo(BigDecimal.ZERO) > 0)
                        ? amount.divide(xmrToCny, 12, java.math.RoundingMode.HALF_UP)
                        : null);
                if (xmrUsed == null || xmrUsed.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("无法复算 XMR 等值，请检查提现记录");
                }
                BigDecimal calToRefund = xmrUsed.divide(calXmrRatio, 8, java.math.RoundingMode.HALF_UP);
                userMapper.updateCalBalance(withdrawal.getUserId(), calToRefund);
            }
        }
    }

    // 分页查询
    public PageVo<Withdrawal> getPendingWithdrawals(int page, int size) {

        int statusPending = 0;
        long total = withdrawalMapper.countByStatus(statusPending);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<Withdrawal> list = withdrawalMapper.findByStatusPaginated(statusPending, offset, size);
        return new PageVo<>(total, page, size, list);

    }

    public PageVo<AdminWithdrawalListItemVo> getWithdrawals(Integer status,
                                                            Long userId,
                                                            String startTime,
                                                            String endTime,
                                                            int page,
                                                            int size) {
        long total = withdrawalMapper.countByFilters(status, userId, startTime, endTime);
        if (total <= 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = Math.max(0, (page - 1) * size);
        List<Withdrawal> list = withdrawalMapper.findByFiltersPaginated(status, userId, startTime, endTime, offset, size);
        List<AdminWithdrawalListItemVo> voList = new ArrayList<>();
        for (Withdrawal withdrawal : list) {
            if (withdrawal == null) {
                continue;
            }
            AdminWithdrawalListItemVo vo = new AdminWithdrawalListItemVo();
            vo.setWithdrawId(withdrawal.getId());
            vo.setUid(withdrawal.getUserId());
            vo.setCurrency(withdrawal.getCurrency());
            vo.setAmount(withdrawal.getAmount());
            vo.setFee(withdrawal.getFeeAmount());
            vo.setAddress(withdrawal.getAccountInfo());
            vo.setStatus(normalizeStatus(withdrawal.getStatus()));
            vo.setApplyTime(withdrawal.getCreateTime());
            voList.add(vo);
        }
        return new PageVo<>(total, page, size, voList);
    }

    public Withdrawal getWithdrawal(Long withdrawalId) {
        return withdrawalMapper.findById(withdrawalId).orElse(null);
    }

    private String normalizeStatus(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "APPROVED";
            case 2 -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    private String buildRemark(String prefix, String remark) {
        String normalized = remark == null ? "" : remark.trim();
        if (normalized.isEmpty()) {
            return prefix;
        }
        return prefix + ": " + normalized;
    }
}