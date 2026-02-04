package com.slb.mining_backend.modules.withdraw.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.withdraw.config.WithdrawProperties;
import com.slb.mining_backend.modules.withdraw.dto.WithdrawApplyDto;
import com.slb.mining_backend.modules.withdraw.entity.Withdrawal;
import com.slb.mining_backend.modules.withdraw.mapper.WithdrawalMapper;
import com.slb.mining_backend.modules.withdraw.vo.WithdrawVo;
import com.slb.mining_backend.modules.xmr.service.f2pool.F2PoolAlertService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WithdrawalService {

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("3");
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal FIFTY = new BigDecimal("50");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIVE_HUNDRED = new BigDecimal("500");

    private final WithdrawalMapper withdrawalMapper;
    private final UserMapper userMapper;
    private final WithdrawProperties withdrawProperties;
    private final com.slb.mining_backend.modules.exchange.service.ExchangeRateService exchangeRateService;
    private final F2PoolAlertService f2PoolAlertService;

    /**
     * 1 CAL = calXmrRatio * XMR（例如 0.001）
     */
    @Value("${app.rates.cal-xmr-ratio:1.0}")
    private BigDecimal calXmrRatio;

    public WithdrawalService(WithdrawalMapper withdrawalMapper,
                             UserMapper userMapper,
                             WithdrawProperties withdrawProperties,
                             com.slb.mining_backend.modules.exchange.service.ExchangeRateService exchangeRateService,
                             F2PoolAlertService f2PoolAlertService) {
        this.withdrawalMapper = withdrawalMapper;
        this.userMapper = userMapper;
        this.withdrawProperties = withdrawProperties;
        this.exchangeRateService = exchangeRateService;
        this.f2PoolAlertService = f2PoolAlertService;
    }

    @Transactional
    public WithdrawVo applyForWithdrawal(Long userId, WithdrawApplyDto dto) {

        // 1. 校验业务规则（最小金额/手续费/渠道）
        BigDecimal minAmount = withdrawProperties.getMinAmount() != null
                ? withdrawProperties.getMinAmount()
                : MIN_AMOUNT;
        if (minAmount.compareTo(MIN_AMOUNT) < 0) {
            minAmount = MIN_AMOUNT;
        }

        String accountType = dto.getAccountType();
        if (!"alipay".equalsIgnoreCase(accountType)) {
            throw new BizException("当前仅支持支付宝提现");
        }

        BigDecimal requestAmount = dto.getAmount();
        if (requestAmount == null || requestAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("提现金额必须大于 0");
        }

        // amountUnit 决定 dto.amount 的单位（严禁启发式猜测单位）
        String amountUnit = normalizeAmountUnit(dto.getAmountUnit()); // default: CNY

        // 统一按“等价 CNY 总扣金额”（cnyGross）进行：最小金额校验 + 手续费计算
        BigDecimal cnyGross;
        BigDecimal xmrEquiv = null;
        BigDecimal rateAtRequest = null;
        String currency; // withdrawals.currency：表示扣款来源（CNY 现金余额 或 CAL 余额）

        // amountUnit=CAL 时：dto.amount 表示 CAL 数量；按汇率折算等价 CNY
        BigDecimal calDebit = null; // 仅 amountUnit=CAL 时使用：从 cal_balance 扣减的 CAL 数量
        // legacy CAL 模式：amountUnit=CNY 且 currency=CAL 时使用：从 cal_balance 扣减的 CAL 数量（由 CNY 折算）
        BigDecimal calRequiredLegacy = null;

        if ("CAL".equals(amountUnit)) {
            currency = "CAL";
            calDebit = requestAmount;
            BigDecimal xmrToCny = exchangeRateService.getLatestRate("XMR/CNY");
            if (xmrToCny == null || xmrToCny.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("无法获取当前汇率，请稍后再试");
            }
            if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("CAL/XMR 换算比例未配置");
            }
            xmrEquiv = calDebit.multiply(calXmrRatio).setScale(12, RoundingMode.HALF_UP);
            rateAtRequest = xmrToCny;
            cnyGross = xmrEquiv.multiply(xmrToCny).setScale(2, RoundingMode.HALF_UP);
            if (cnyGross.compareTo(minAmount) < 0) {
                throw new BizException("按等价CNY计算，提现金额不能小于 " + minAmount + " CNY");
            }
        } else {
            // 默认/兼容：amountUnit=CNY
            cnyGross = requestAmount;
            if (cnyGross.compareTo(minAmount) < 0) {
                throw new BizException("提现金额不能小于 " + minAmount + " CNY");
            }

            String currencyParam = dto.getCurrency();
            String normalizedCurrency = (currencyParam == null || currencyParam.isBlank())
                    ? "CNY"
                    : currencyParam.trim().toUpperCase();
            currency = "CAL".equalsIgnoreCase(normalizedCurrency) ? "CAL" : "CNY";

            // 兼容旧 CAL 提现模式：申请金额仍按 CNY 输入，但资金来源用 CAL 余额折算扣减
            if ("CAL".equalsIgnoreCase(currency)) {
                BigDecimal xmrToCny = exchangeRateService.getLatestRate("XMR/CNY");
                if (xmrToCny == null || xmrToCny.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("无法获取当前汇率，请稍后再试");
                }
                if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("CAL/XMR 换算比例未配置");
                }
                // xmrRequired = CNY / (XMR/CNY)
                // calRequired = xmrRequired / (CAL->XMR ratio)
                BigDecimal xmrRequired = cnyGross.divide(xmrToCny, 12, RoundingMode.HALF_UP);
                calRequiredLegacy = xmrRequired.divide(calXmrRatio, 8, RoundingMode.HALF_UP);
                xmrEquiv = xmrRequired;
                rateAtRequest = xmrToCny;
            } else {
                xmrEquiv = null;
                rateAtRequest = null;
            }
        }

        BigDecimal feeAmount = calculateFee(cnyGross);
        if (cnyGross.subtract(feeAmount).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("扣除手续费后到账金额必须大于 0");
        }

        // 1.1 同一用户互斥：存在未完成提现（status=0 待审核）时，不允许重复申请
        // 通过锁定 users 行避免并发下“同时通过校验、写入两笔待审核单”的竞态。
        Long lockedUserId = userMapper.lockByIdForUpdate(userId);
        if (lockedUserId == null) {
            throw new BizException("用户不存在");
        }
        if (f2PoolAlertService != null && f2PoolAlertService.hasOpenAlerts(userId)) {
            throw new BizException("账户存在未处理风控告警，暂不可提现");
        }
        long activeCount = withdrawalMapper.countActiveByUserId(userId);
        if (activeCount > 0) {
            throw new BizException("你有一笔提现正在处理中，请等待处理完成后再申请");
        }

        long todayCount = withdrawalMapper.countTodayWithdrawalsByUserId(userId);
        if (todayCount >= withdrawProperties.getDailyLimitCount()) {
            throw new BizException("今日提现次数已达上限");
        }

        // 2. 加载用户信息
        User user = userMapper.selectById(userId).orElseThrow(() -> new BizException("用户不存在"));

        // 2.1 扣减余额（保持管理端审核兼容：withdrawals.amount 始终按 CNY 存储；CAL 模式依赖 xmr_equivalent + rate_at_request）
        if ("CAL".equalsIgnoreCase(currency)) {
            if ("CAL".equals(amountUnit)) {
                // 新契约：amountUnit=CAL => 直接扣减用户输入的 CAL 数量（不再扣“折算所需 CAL”）
                if (calDebit == null) {
                    throw new BizException("CAL 提现金额异常");
                }
                if (user.getCalBalance() == null || user.getCalBalance().compareTo(calDebit) < 0) {
                    throw new BizException("可用 CAL 余额不足");
                }
                userMapper.updateCalBalance(userId, calDebit.negate());
                // xmrEquiv/rateAtRequest 已在上面算好：xmrEquiv = calDebit * calXmrRatio, amount=cnyGross
            } else {
                // 兼容旧 CAL 模式：amountUnit=CNY 且 currency=CAL => 按 cnyGross 折算并扣减 calRequiredLegacy
                if (calRequiredLegacy == null || calRequiredLegacy.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("CAL 提现折算失败，请稍后再试");
                }
                if (user.getCalBalance() == null || user.getCalBalance().compareTo(calRequiredLegacy) < 0) {
                    throw new BizException("可用 CAL 余额不足");
                }
                userMapper.updateCalBalance(userId, calRequiredLegacy.negate());
            }
        } else {
            // 默认：CNY 余额提现到支付宝（冻结现金）
            // 校验可用现金余额
            if (user.getCashBalance() == null || user.getCashBalance().compareTo(cnyGross) < 0) {
                throw new BizException("可用余额不足");
            }
            // 原子性冻结现金：可用减少，冻结增加，总提现额暂不变
            int affected = userMapper.updateCashBalances(
                    userId,
                    cnyGross.negate(),
                    cnyGross,
                    BigDecimal.ZERO
            );
            if (affected == 0) {
                throw new BizException("可用余额不足");
            }
            // CNY 提现不再依赖 XMR 等值，xmrEquiv/rateAtRequest 留空
            xmrEquiv = null;
            rateAtRequest = null;
        }

        // 3. 创建提现记录
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setUserId(userId);
        // 落库 amount 统一按 CNY 存储，保持管理端审核/复算逻辑兼容
        withdrawal.setAmount(cnyGross);
        withdrawal.setAccountType(dto.getAccountType());
        withdrawal.setAccountInfo(dto.getAccount());
        withdrawal.setStatus(0);
        withdrawal.setRemark("等待管理员审核");
        withdrawal.setCurrency(currency);
        withdrawal.setXmrEquivalent(xmrEquiv);
        withdrawal.setRateAtRequest(rateAtRequest);
        withdrawal.setFeeAmount(feeAmount);
        // transferNetwork 留空
        withdrawalMapper.insert(withdrawal);
        return toVo(withdrawal);
    }


    public PageVo<WithdrawVo> getWithdrawalHistory(Long userId, Integer status, int page, int size) {
        long total = withdrawalMapper.countByUserIdAndStatus(userId, status);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<Withdrawal> list = withdrawalMapper.findByUserIdAndStatusPaginated(userId, status, offset, size);
        List<WithdrawVo> voList = list.stream().map(this::toVo).collect(Collectors.toList());
        return new PageVo<>(total, page, size, voList);
    }

    private WithdrawVo toVo(Withdrawal entity) {
        WithdrawVo vo = new WithdrawVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setWithdrawId(entity.getId());
        vo.setAccount(entity.getAccountInfo());
        vo.setCurrency(entity.getCurrency());
        vo.setTransferNetwork(entity.getTransferNetwork());
        if ("CAL".equalsIgnoreCase(entity.getCurrency())) {
            vo.setCnyAmount(entity.getAmount());
            BigDecimal xmrEquivalent = entity.getXmrEquivalent();
            if (xmrEquivalent != null && calXmrRatio != null && calXmrRatio.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calAmount = xmrEquivalent.divide(calXmrRatio, 8, RoundingMode.HALF_UP);
                vo.setCalAmount(calAmount);
            }
            BigDecimal rateAtRequest = entity.getRateAtRequest();
            if (rateAtRequest != null && rateAtRequest.compareTo(BigDecimal.ZERO) > 0) {
                vo.setXmrToCnyRate(rateAtRequest);
                if (calXmrRatio != null && calXmrRatio.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal calToCnyRate = rateAtRequest.multiply(calXmrRatio)
                            .setScale(8, RoundingMode.HALF_UP);
                    vo.setCalToCnyRate(calToCnyRate);
                }
            }
        }
        return vo;
    }

    private BigDecimal calculateFee(BigDecimal amount) {
        BigDecimal rate;
        if (amount.compareTo(FIVE_HUNDRED) >= 0) {
            rate = new BigDecimal("0.001");
        } else if (amount.compareTo(ONE_HUNDRED) >= 0) {
            rate = new BigDecimal("0.005");
        } else if (amount.compareTo(FIFTY) >= 0) {
            rate = new BigDecimal("0.01");
        } else if (amount.compareTo(TEN) >= 0) {
            rate = new BigDecimal("0.02");
        } else {
            rate = new BigDecimal("0.03");
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 归一化 amountUnit：仅允许 CNY / CAL；为空时默认 CNY（兼容旧调用）。
     * 严禁根据数值大小猜测单位，避免把 100 CNY 误当成 100 CAL 造成资金风险。
     */
    private String normalizeAmountUnit(String amountUnit) {
        if (amountUnit == null || amountUnit.isBlank()) {
            return "CNY";
        }
        String v = amountUnit.trim().toUpperCase();
        if (!"CNY".equals(v) && !"CAL".equals(v)) {
            throw new BizException("amountUnit 参数非法，仅支持 CNY 或 CAL");
        }
        return v;
    }
}
