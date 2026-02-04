package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.asset.service.AssetLedgerService;
import com.slb.mining_backend.modules.earnings.entity.EarningsHistory;
import com.slb.mining_backend.modules.earnings.mapper.EarningsHistoryMapper;
import com.slb.mining_backend.modules.earnings.mapper.EarningsMapper;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlement;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlementItem;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayoutDailyMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementItemMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementMapper;
import com.slb.mining_backend.modules.xmr.service.f2pool.F2PoolAlertService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminF2PoolSettlementService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int CNY_SCALE = 4;
    private static final int CAL_SCALE = 8;

    private final F2PoolSettlementMapper settlementMapper;
    private final F2PoolSettlementItemMapper itemMapper;
    private final F2PoolPayoutDailyMapper payoutMapper;
    private final UserMapper userMapper;
    private final EarningsHistoryMapper earningsHistoryMapper;
    private final EarningsMapper earningsMapper;
    private final MarketDataService marketDataService;
    private final AssetLedgerService assetLedgerService;
    private final DeviceMapper deviceMapper;
    private final F2PoolAlertService alertService;
    private final long adminUserId;
    private final Long unclaimedUserId;

    @Value("${app.rates.cal-xmr-ratio:1.0}")
    private BigDecimal calXmrRatio;

    public AdminF2PoolSettlementService(F2PoolSettlementMapper settlementMapper,
                                        F2PoolSettlementItemMapper itemMapper,
                                        F2PoolPayoutDailyMapper payoutMapper,
                                        UserMapper userMapper,
                                        EarningsHistoryMapper earningsHistoryMapper,
                                        EarningsMapper earningsMapper,
                                        MarketDataService marketDataService,
                                        AssetLedgerService assetLedgerService,
                                        DeviceMapper deviceMapper,
                                        F2PoolAlertService alertService,
                                        @Value("${app.settlement.admin-user-id:1}") long adminUserId,
                                        @Value("${app.settlement.unclaimed-user-id:1}") long unclaimedUserId) {
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.payoutMapper = payoutMapper;
        this.userMapper = userMapper;
        this.earningsHistoryMapper = earningsHistoryMapper;
        this.earningsMapper = earningsMapper;
        this.marketDataService = marketDataService;
        this.assetLedgerService = assetLedgerService;
        this.deviceMapper = deviceMapper;
        this.alertService = alertService;
        this.adminUserId = adminUserId;
        this.unclaimedUserId = unclaimedUserId > 0 ? unclaimedUserId : null;
    }

    @Transactional
    public void approveSettlement(Long settlementId, String remark) {
        F2PoolSettlement settlement = settlementMapper.selectById(settlementId)
                .orElseThrow(() -> new BizException("Settlement not found"));
        if (!"AUDIT".equalsIgnoreCase(settlement.getStatus()) && !"PENDING".equalsIgnoreCase(settlement.getStatus())) {
            throw new BizException("Settlement status is not pending");
        }
        List<F2PoolSettlementItem> items = itemMapper.findBySettlementId(settlementId);
        if (items == null || items.isEmpty()) {
            throw new BizException("Settlement items are empty");
        }

        BigDecimal calToCnyFallback = marketDataService.getCalToCnyRate();
        BigDecimal feeTotalCal = BigDecimal.ZERO;
        BigDecimal feeTotalCny = BigDecimal.ZERO;
        BigDecimal feeTotalCnyEq = BigDecimal.ZERO;
        BigDecimal feeTotalXmrCny = BigDecimal.ZERO;
        Map<Long, CpuGpuRatio> ratioCache = new HashMap<>();
        for (F2PoolSettlementItem item : items) {
            if (item == null || item.getUserId() == null) {
                continue;
            }
            BigDecimal netCal = safe(item.getNetCal());
            if (netCal.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Optional<User> userOpt = userMapper.selectById(item.getUserId());
            if (userOpt.isEmpty()) {
                alertService.raiseAlert(settlement.getAccount(), settlement.getCoin(), item.getUserId(),
                        "SETTLEMENT_APPLY_FAILED", "WARN", String.valueOf(item.getId()),
                        "user not found for settlement item");
                continue;
            }
            User user = userOpt.get();
            SettlementCurrency preference = SettlementCurrency.fromCode(user.getSettlementCurrency());
            BigDecimal itemCalToCny = resolveCalToCnyRate(netCal, item.getNetCny(), calToCnyFallback);
            BigDecimal amountCny = resolveAmountCny(netCal, item.getNetCny(), itemCalToCny);
            BigDecimal amountXmr = resolveAmountXmr(netCal);
            String gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(item.getCoin());
            if (!StringUtils.hasText(gpuEarningTypeOverride)) {
                gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(settlement.getCoin());
            }
            boolean preferGpuWhenTotalZero = StringUtils.hasText(gpuEarningTypeOverride);
            CpuGpuRatio ratio = ratioCache.computeIfAbsent(user.getId(), id -> resolveCpuGpuRatio(id, preferGpuWhenTotalZero));

            if (preference == SettlementCurrency.CNY) {
                if (amountCny.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("CNY settlement requires valid CAL/CNY rate");
                }
                userMapper.updateCashBalances(user.getId(), amountCny, BigDecimal.ZERO, BigDecimal.ZERO);
                recordMiningEarningsHistorySplitCpuGpu(user, amountCny, netCal, settlement.getPayoutDate().atStartOfDay(), ratio, gpuEarningTypeOverride);
                assetLedgerService.recordMiningPayout(
                        user.getId(),
                        "CNY",
                        amountXmr,
                        null,
                        amountCny,
                        item.getId(),
                        null,
                        "F2Pool settlement (CNY)",
                        settlement.getPayoutDate().atStartOfDay());
                BigDecimal feeCal = safe(item.getFeeCal());
                if (feeCal.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal feeCny = resolveAmountCny(feeCal, null, itemCalToCny);
                    feeTotalCny = feeTotalCny.add(feeCny);
                    feeTotalXmrCny = feeTotalXmrCny.add(resolveAmountXmr(feeCal));
                }
            } else {
                userMapper.updateUserWallet(user.getId(), netCal);
                recordMiningEarningsHistorySplitCpuGpu(user, amountCny, netCal, settlement.getPayoutDate().atStartOfDay(), ratio, gpuEarningTypeOverride);
                assetLedgerService.recordMiningPayout(
                        user.getId(),
                        "CAL",
                        amountXmr,
                        netCal,
                        amountCny,
                        item.getId(),
                        null,
                        "F2Pool settlement (CAL)",
                        settlement.getPayoutDate().atStartOfDay());
                BigDecimal feeCal = safe(item.getFeeCal());
                if (feeCal.compareTo(BigDecimal.ZERO) > 0) {
                    feeTotalCal = feeTotalCal.add(feeCal);
                    feeTotalCnyEq = feeTotalCnyEq.add(resolveAmountCny(feeCal, null, itemCalToCny));
                }
            }
        }

        if (feeTotalCal.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.updateUserWallet(adminUserId, feeTotalCal);
            assetLedgerService.recordMiningPayout(
                    adminUserId,
                    "CAL",
                    resolveAmountXmr(feeTotalCal),
                    feeTotalCal,
                    feeTotalCnyEq,
                    settlementId,
                    null,
                    "F2Pool commission (CAL)",
                    settlement.getPayoutDate().atStartOfDay());
        }

        if (feeTotalCny.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.updateCashBalances(adminUserId, feeTotalCny, BigDecimal.ZERO, BigDecimal.ZERO);
            assetLedgerService.recordMiningPayout(
                    adminUserId,
                    "CNY",
                    feeTotalXmrCny,
                    null,
                    feeTotalCny,
                    settlementId,
                    null,
                    "F2Pool commission (CNY)",
                    settlement.getPayoutDate().atStartOfDay());
        }

        itemMapper.updateStatusBySettlementId(settlementId, "POSTED");
        settlementMapper.updateStatus(settlementId, "PAID", LocalDateTime.now(BJT));

        payoutMapper.selectByAccountAndDate(settlement.getAccount(), settlement.getCoin(), settlement.getPayoutDate())
                .ifPresent(p -> payoutMapper.updateStatus(p.getId(), "PAID"));
    }

    @Transactional
    public void rejectSettlement(Long settlementId, String remark) {
        F2PoolSettlement settlement = settlementMapper.selectById(settlementId)
                .orElseThrow(() -> new BizException("Settlement not found"));
        if (!"AUDIT".equalsIgnoreCase(settlement.getStatus()) && !"PENDING".equalsIgnoreCase(settlement.getStatus())) {
            throw new BizException("Settlement status is not pending");
        }
        itemMapper.updateStatusBySettlementId(settlementId, "REJECTED");
        settlementMapper.updateStatus(settlementId, "REJECTED", LocalDateTime.now(BJT));
        payoutMapper.selectByAccountAndDate(settlement.getAccount(), settlement.getCoin(), settlement.getPayoutDate())
                .ifPresent(p -> payoutMapper.updateStatus(p.getId(), "REJECTED"));
    }

    public List<F2PoolSettlementItem> getSettlementItems(Long settlementId) {
        return itemMapper.findBySettlementId(settlementId);
    }

    public Optional<F2PoolSettlement> getSettlement(Long settlementId) {
        return settlementMapper.selectById(settlementId);
    }

    public List<F2PoolSettlement> listSettlementsByStatus(String status, int offset, int size) {
        return settlementMapper.findByStatusPaginated(status, offset, size);
    }

    public long countSettlementsByStatus(String status) {
        return settlementMapper.countByStatus(status);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal resolveCalToCnyRate(BigDecimal amountCal, BigDecimal amountCny, BigDecimal fallback) {
        if (amountCal != null && amountCal.compareTo(BigDecimal.ZERO) > 0
                && amountCny != null && amountCny.compareTo(BigDecimal.ZERO) > 0) {
            return amountCny.divide(amountCal, CAL_SCALE, RoundingMode.HALF_UP);
        }
        if (fallback != null && fallback.compareTo(BigDecimal.ZERO) > 0) {
            return fallback;
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveAmountCny(BigDecimal amountCal, BigDecimal amountCny, BigDecimal calToCnyRate) {
        if (amountCny != null && amountCny.compareTo(BigDecimal.ZERO) > 0) {
            return amountCny;
        }
        if (amountCal == null || amountCal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (calToCnyRate == null || calToCnyRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amountCal.multiply(calToCnyRate).setScale(CNY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveAmountXmr(BigDecimal amountCal) {
        if (amountCal == null || amountCal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amountCal.multiply(calXmrRatio).setScale(12, RoundingMode.HALF_UP);
    }

    private CpuGpuRatio resolveCpuGpuRatio(Long userId, boolean preferGpuWhenTotalZero) {
        BigDecimal cpuHps = safe(deviceMapper.sumCpuHashrateByUserId(userId));
        BigDecimal gpuMh = safe(deviceMapper.sumGpuHashrateByUserId(userId));
        BigDecimal cpuMh = toMhFromHps(cpuHps);
        BigDecimal total = cpuMh.add(gpuMh);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            if (preferGpuWhenTotalZero || (unclaimedUserId != null && unclaimedUserId.equals(userId))) {
                return new CpuGpuRatio(BigDecimal.ZERO, BigDecimal.ONE);
            }
            return new CpuGpuRatio(BigDecimal.ONE, BigDecimal.ZERO);
        }
        BigDecimal cpuRatio = cpuMh.divide(total, 12, RoundingMode.HALF_UP);
        BigDecimal gpuRatio = BigDecimal.ONE.subtract(cpuRatio);
        return new CpuGpuRatio(cpuRatio, gpuRatio);
    }

    private String resolveGpuEarningTypeOverrideByCoin(String coin) {
        if (!StringUtils.hasText(coin)) {
            return null;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        if ("CONFLUX".equals(normalized) || "CFX".equals(normalized)) {
            return "GPU_OCTOPUS";
        }
        if ("RVN".equals(normalized) || "RAVENCOIN".equals(normalized) || "KAWPOW".equals(normalized)) {
            return "GPU_KAWPOW";
        }
        return null;
    }

    private BigDecimal toMhFromHps(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
    }

    private void recordMiningEarningsHistorySplitCpuGpu(User user,
                                                        BigDecimal totalAmountCny,
                                                        BigDecimal totalAmountCal,
                                                        LocalDateTime earningTime,
                                                        CpuGpuRatio ratio,
                                                        String gpuEarningTypeOverride) {
        BigDecimal totalCal = safe(totalAmountCal);
        BigDecimal totalCny = safe(totalAmountCny);

        boolean forceGpuOnly = StringUtils.hasText(gpuEarningTypeOverride);
        BigDecimal cpuCal;
        BigDecimal gpuCal;
        BigDecimal cpuCny;
        BigDecimal gpuCny;
        if (forceGpuOnly) {
            cpuCal = BigDecimal.ZERO.setScale(CAL_SCALE, RoundingMode.HALF_UP);
            gpuCal = totalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP);
            cpuCny = BigDecimal.ZERO.setScale(CNY_SCALE, RoundingMode.HALF_UP);
            gpuCny = totalCny.setScale(CNY_SCALE, RoundingMode.HALF_UP);
        } else {
            cpuCal = totalCal.multiply(ratio.cpuRatio).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            gpuCal = totalCal.subtract(cpuCal).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            cpuCny = totalCny.multiply(ratio.cpuRatio).setScale(CNY_SCALE, RoundingMode.HALF_UP);
            gpuCny = totalCny.subtract(cpuCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        }

        if (cpuCal.compareTo(BigDecimal.ZERO) > 0 || cpuCny.compareTo(BigDecimal.ZERO) > 0) {
            recordEarningsHistory(user, cpuCny, cpuCal, "CPU", earningTime);
        }
        if (gpuCal.compareTo(BigDecimal.ZERO) > 0 || gpuCny.compareTo(BigDecimal.ZERO) > 0) {
            String earningType = StringUtils.hasText(gpuEarningTypeOverride)
                    ? gpuEarningTypeOverride
                    : "GPU";
            recordEarningsHistory(user, gpuCny, gpuCal, earningType, earningTime);
        }
        if (cpuCal.compareTo(BigDecimal.ZERO) <= 0 && gpuCal.compareTo(BigDecimal.ZERO) <= 0
                && cpuCny.compareTo(BigDecimal.ZERO) <= 0 && gpuCny.compareTo(BigDecimal.ZERO) <= 0) {
            String fallbackType = forceGpuOnly && StringUtils.hasText(gpuEarningTypeOverride)
                    ? gpuEarningTypeOverride
                    : "CPU";
            recordEarningsHistory(user, totalCny, totalCal, fallbackType, earningTime);
        }
    }

    private void recordEarningsHistory(User user,
                                       BigDecimal amountCny,
                                       BigDecimal amountCal,
                                       String earningType,
                                       LocalDateTime earningTime) {
        EarningsHistory history = new EarningsHistory();
        history.setUserId(user.getId());
        history.setDeviceId(user.getWorkerId() != null ? user.getWorkerId() : "POOL");
        history.setBonusCalAmount(BigDecimal.ZERO);
        history.setAmountCal(amountCal != null ? amountCal : BigDecimal.ZERO);
        history.setAmountCny(amountCny != null ? amountCny : BigDecimal.ZERO);
        history.setEarningType(earningType != null ? earningType : "CPU");
        history.setEarningTime(earningTime);
        earningsHistoryMapper.insert(history);
        LocalDateTime time = earningTime != null ? earningTime : LocalDateTime.now(BJT);
        earningsMapper.upsertDailyStats(user.getId(), time.toLocalDate(), history.getAmountCal(), history.getAmountCny(), earningType);
    }

    private record CpuGpuRatio(BigDecimal cpuRatio, BigDecimal gpuRatio) {
    }
}
