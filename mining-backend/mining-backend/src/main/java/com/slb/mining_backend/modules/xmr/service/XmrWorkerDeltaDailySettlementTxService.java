package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.earnings.entity.EarningsHistory;
import com.slb.mining_backend.modules.earnings.mapper.EarningsHistoryMapper;
import com.slb.mining_backend.modules.earnings.mapper.EarningsMapper;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.xmr.config.XmrPoolProperties;
import com.slb.mining_backend.modules.xmr.dto.UserAtomicShare;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerEarningDeltaMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 单日结算的事务实现（确保 @Transactional 真实生效：public 方法 + 由外部 Bean 调用）。
 */
@Service
@Slf4j
public class XmrWorkerDeltaDailySettlementTxService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final int XMR_SCALE = 12;
    private static final int CAL_SCALE = 8;
    private static final int CNY_SCALE = 4;
    private static final String EARNING_TYPE = "CPU";
    // 与 Hourly 结算写入的 device_id 做区分，降低未来“双写明细”的取证与排障成本
    private static final String DEVICE_ID = "POOL_DELTA";

    private final XmrWorkerEarningDeltaMapper deltaMapper;
    private final EarningsHistoryMapper earningsHistoryMapper;
    private final EarningsMapper earningsMapper;
    private final MarketDataService marketDataService;
    private final long atomicPerXmr;

    public XmrWorkerDeltaDailySettlementTxService(XmrWorkerEarningDeltaMapper deltaMapper,
                                                 EarningsHistoryMapper earningsHistoryMapper,
                                                 EarningsMapper earningsMapper,
                                                 MarketDataService marketDataService,
                                                 XmrPoolProperties poolProperties) {
        this.deltaMapper = deltaMapper;
        this.earningsHistoryMapper = earningsHistoryMapper;
        this.earningsMapper = earningsMapper;
        this.marketDataService = marketDataService;
        long configured = poolProperties.getDefaultProvider().getUnit().getAtomicPerXmr();
        this.atomicPerXmr = Math.max(1L, configured);
    }

    /**
     * 结算指定“北京业务日”的全部未结算 delta，并置 settled=1。
     *
     * 时区与窗口：
     * - 业务窗口：昨日 00:00-24:00（Asia/Shanghai）
     * - delta.window_end：以 UTC 语义存储的 DATETIME
     * - 因此必须：BJT 窗口 -> 转换为 UTC LocalDateTime 窗口，用于查询与更新条件。
     */
    @Transactional
    public void settleOneBusinessDay(LocalDate businessDayBjt) {
        if (businessDayBjt == null) {
            return;
        }

        ZonedDateTime bjtStart = businessDayBjt.atStartOfDay(BJT);
        ZonedDateTime bjtEnd = bjtStart.plusDays(1);
        LocalDateTime startUtc = bjtStart.withZoneSameInstant(UTC).toLocalDateTime();
        LocalDateTime endUtc = bjtEnd.withZoneSameInstant(UTC).toLocalDateTime();

        List<UserAtomicShare> shares = deltaMapper.sumUnsettledInRange(startUtc, endUtc);
        if (shares == null || shares.isEmpty()) {
            log.info("Worker-delta daily settlement: no records in window. businessDayBjt={}, utcWindow=[{}, {})",
                    businessDayBjt, startUtc, endUtc);
            return;
        }

        BigDecimal ratio = safe(marketDataService.getCalXmrRatio()); // 1 CAL = ratio * XMR
        if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
            // 明确失败并回滚，避免把 delta 标记 settled 却没写明细
            throw new IllegalStateException("CAL/XMR ratio unavailable (app.rates.cal-xmr-ratio)");
        }
        BigDecimal calToCny = safe(marketDataService.getCalToCnyRate());

        int inserted = 0;
        BigDecimal totalCal = BigDecimal.ZERO;
        for (UserAtomicShare s : shares) {
            if (s == null || s.getUserId() == null || s.getTotalAtomic() == null || s.getTotalAtomic() <= 0) {
                continue;
            }
            BigDecimal xmr = BigDecimal.valueOf(s.getTotalAtomic())
                    .divide(BigDecimal.valueOf(atomicPerXmr), XMR_SCALE, RoundingMode.HALF_UP);
            if (xmr.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal amountCal = xmr.divide(ratio, CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal amountCny = (calToCny.compareTo(BigDecimal.ZERO) > 0)
                    ? amountCal.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(CNY_SCALE, RoundingMode.HALF_UP);

            EarningsHistory h = new EarningsHistory();
            h.setUserId(s.getUserId());
            h.setDeviceId(DEVICE_ID);
            h.setBonusCalAmount(BigDecimal.ZERO);
            h.setAmountCal(amountCal);
            h.setAmountCny(amountCny);
            h.setEarningType(EARNING_TYPE);
            // earning_time 用北京时间业务日的 00:00:00（清晰标识“按日结算”口径）
            h.setEarningTime(businessDayBjt.atStartOfDay());
            earningsHistoryMapper.insert(h);

            earningsMapper.upsertDailyStats(s.getUserId(), businessDayBjt, amountCal, amountCny, EARNING_TYPE);

            inserted++;
            totalCal = totalCal.add(amountCal);
        }

        int marked = deltaMapper.markSettledInRange(startUtc, endUtc);

        log.info("Worker-delta daily settlement done: businessDayBjt={}, utcWindow=[{}, {}), usersInserted={}, deltasMarkedSettled={}, totalCal={}",
                businessDayBjt, startUtc, endUtc, inserted, marked, totalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}


