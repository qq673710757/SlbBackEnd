package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.vo.EarningsGrantVo;
import com.slb.mining_backend.modules.admin.mapper.AdminEarningsIncrementMapper;
import com.slb.mining_backend.modules.admin.vo.AdminEarningsIncrementRow;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminEarningsGrantService {

    private static final DateTimeFormatter GRANT_ID_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdminEarningsIncrementMapper incrementMapper;

    public AdminEarningsGrantService(AdminEarningsIncrementMapper incrementMapper) {
        this.incrementMapper = incrementMapper;
    }

    public PageVo<EarningsGrantVo> listGrants(int page, int size, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        long total = incrementMapper.countDistinctDays(startTime, endTime);
        if (total <= 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = Math.max(0, (page - 1) * size);
        List<LocalDate> dates = incrementMapper.listDistinctDays(startTime, endTime, offset, size);
        if (CollectionUtils.isEmpty(dates)) {
            return new PageVo<>(0L, page, size, List.of());
        }
        List<AdminEarningsIncrementRow> rows = new ArrayList<>();
        rows.addAll(incrementMapper.listF2PoolDailyByDays(dates));
        rows.addAll(incrementMapper.listAntpoolDailyByDays(dates));
        Map<LocalDate, EarningsGrantVo> aggregates = new HashMap<>();
        for (LocalDate date : dates) {
            EarningsGrantVo vo = new EarningsGrantVo();
            vo.setGrantDate(date);
            vo.setGrantId("GRANT_" + GRANT_ID_DATE.format(date));
            vo.setPayoutXmr(BigDecimal.ZERO);
            vo.setPayoutCfx(BigDecimal.ZERO);
            vo.setPayoutRvn(BigDecimal.ZERO);
            vo.setCommissionXmr(BigDecimal.ZERO);
            vo.setCommissionCfx(BigDecimal.ZERO);
            vo.setCommissionRvn(BigDecimal.ZERO);
            aggregates.put(date, vo);
        }

        if (rows != null) {
            for (AdminEarningsIncrementRow row : rows) {
                if (row == null || row.getDay() == null) {
                    continue;
                }
                EarningsGrantVo target = aggregates.get(row.getDay());
                if (target == null) {
                    continue;
                }
                applyIncrementRow(target, row);
                LocalDateTime updatedTime = row.getUpdatedTime();
                if (updatedTime != null) {
                    LocalDateTime current = target.getCreatedAt();
                    if (current == null || updatedTime.isAfter(current)) {
                        target.setCreatedAt(updatedTime);
                    }
                }
            }
        }

        List<EarningsGrantVo> list = new ArrayList<>();
        for (LocalDate date : dates) {
            EarningsGrantVo vo = aggregates.get(date);
            if (vo != null) {
                list.add(vo);
            }
        }
        return new PageVo<>(total, page, size, list);
    }

    private void applyIncrementRow(EarningsGrantVo target, AdminEarningsIncrementRow row) {
        CoinType coinType = resolveCoin(row.getCoin());
        if (coinType == CoinType.UNKNOWN) {
            return;
        }
        BigDecimal totalCoin = safe(row.getTotalCoin());
        BigDecimal totalCal = safe(row.getTotalCal());
        BigDecimal userCal = safe(row.getUserCal());
        if (totalCoin.compareTo(BigDecimal.ZERO) <= 0 || totalCal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal ratio = clampRatio(userCal.divide(totalCal, 12, RoundingMode.HALF_UP));
        BigDecimal payout = totalCoin.multiply(ratio);
        BigDecimal commission = totalCoin.subtract(payout);
        if (commission.compareTo(BigDecimal.ZERO) < 0) {
            commission = BigDecimal.ZERO;
        }
        if (coinType == CoinType.XMR) {
            target.setPayoutXmr(addScaled(target.getPayoutXmr(), payout, 4));
            target.setCommissionXmr(addScaled(target.getCommissionXmr(), commission, 4));
        } else if (coinType == CoinType.CFX) {
            target.setPayoutCfx(addScaled(target.getPayoutCfx(), payout, 2));
            target.setCommissionCfx(addScaled(target.getCommissionCfx(), commission, 2));
        } else if (coinType == CoinType.RVN) {
            target.setPayoutRvn(addScaled(target.getPayoutRvn(), payout, 2));
            target.setCommissionRvn(addScaled(target.getCommissionRvn(), commission, 2));
        }
    }

    private CoinType resolveCoin(String coin) {
        if (coin == null) {
            return CoinType.UNKNOWN;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        if ("XMR".equals(normalized) || "MONERO".equals(normalized)) {
            return CoinType.XMR;
        }
        if ("CFX".equals(normalized) || "CONFLUX".equals(normalized) || "OCTOPUS".equals(normalized)) {
            return CoinType.CFX;
        }
        if ("RVN".equals(normalized) || "RAVENCOIN".equals(normalized) || "KAWPOW".equals(normalized)) {
            return CoinType.RVN;
        }
        return CoinType.UNKNOWN;
    }

    private BigDecimal addScaled(BigDecimal current, BigDecimal delta, int scale) {
        BigDecimal base = current == null ? BigDecimal.ZERO : current;
        BigDecimal added = base.add(safe(delta));
        return added.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal clampRatio(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private enum CoinType {
        XMR,
        CFX,
        RVN,
        UNKNOWN
    }
}
