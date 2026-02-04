package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.entity.F2PoolReconcileReport;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolReconcileReportMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolWorkerSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@Slf4j
public class F2PoolReconcileService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int RATIO_SCALE = 6;

    private final F2PoolProperties properties;
    private final F2PoolWorkerSnapshotMapper snapshotMapper;
    private final F2PoolReconcileReportMapper reportMapper;
    private final F2PoolAlertService alertService;

    public F2PoolReconcileService(F2PoolProperties properties,
                                  F2PoolWorkerSnapshotMapper snapshotMapper,
                                  F2PoolReconcileReportMapper reportMapper,
                                  F2PoolAlertService alertService) {
        this.properties = properties;
        this.snapshotMapper = snapshotMapper;
        this.reportMapper = reportMapper;
        this.alertService = alertService;
    }

    public void reconcileHashrate(String account, String coin, BigDecimal overviewHashrateHps) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (overviewHashrateHps == null || overviewHashrateHps.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Optional<LocalDateTime> bucketOpt;
        try {
            bucketOpt = snapshotMapper.selectLatestBucketTime(account, coin);
        } catch (DataAccessException ex) {
            log.warn("F2Pool reconcile skipped: snapshot table unavailable (account={}, coin={}, error={})",
                    account, coin, ex.getMessage());
            return;
        }
        if (bucketOpt.isEmpty()) {
            return;
        }
        BigDecimal workerHashrate = snapshotMapper.sumHashrateByBucket(account, coin, bucketOpt.get());
        BigDecimal diffRatio = computeRatioDiff(overviewHashrateHps, workerHashrate);
        String status = diffRatio.compareTo(thresholdHashrate()) > 0 ? "WARN" : "OK";

        F2PoolReconcileReport report = new F2PoolReconcileReport();
        report.setAccount(account);
        report.setCoin(coin);
        report.setReportTime(LocalDateTime.now(BJT));
        report.setOverviewHashrateHps(overviewHashrateHps);
        report.setWorkersHashrateHps(workerHashrate);
        report.setHashrateDiffRatio(diffRatio);
        report.setStatus(status);
        report.setCreatedTime(LocalDateTime.now(BJT));
        reportMapper.insert(report);

        if ("WARN".equals(status)) {
            alertService.raiseAlert(account, coin, null,
                    "RECONCILE_HASHRATE", "WARN", null, "hashrate diff ratio=" + diffRatio);
        }
    }

    public void reconcileRevenue(String account, String coin, BigDecimal grossAmountCoin, BigDecimal settlementGrossCoin) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (grossAmountCoin == null || grossAmountCoin.compareTo(BigDecimal.ZERO) <= 0
                || settlementGrossCoin == null || settlementGrossCoin.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal diffRatio = computeRatioDiff(grossAmountCoin, settlementGrossCoin);
        String status = diffRatio.compareTo(thresholdRevenue()) > 0 ? "WARN" : "OK";

        F2PoolReconcileReport report = new F2PoolReconcileReport();
        report.setAccount(account);
        report.setCoin(coin);
        report.setReportTime(LocalDateTime.now(BJT));
        report.setGrossAmountCoin(grossAmountCoin);
        report.setSettlementGrossCoin(settlementGrossCoin);
        report.setRevenueDiffRatio(diffRatio);
        report.setStatus(status);
        report.setCreatedTime(LocalDateTime.now(BJT));
        reportMapper.insert(report);

        if ("WARN".equals(status)) {
            alertService.raiseAlert(account, coin, null,
                    "RECONCILE_REVENUE", "WARN", null, "revenue diff ratio=" + diffRatio);
        }
    }

    private BigDecimal computeRatioDiff(BigDecimal baseline, BigDecimal observed) {
        if (baseline == null || baseline.compareTo(BigDecimal.ZERO) <= 0 || observed == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = baseline.subtract(observed).abs();
        return diff.divide(baseline, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal thresholdHashrate() {
        if (properties == null || properties.getReconcile() == null) {
            return BigDecimal.ZERO;
        }
        return properties.getReconcile().getHashrateDiffThreshold() != null
                ? properties.getReconcile().getHashrateDiffThreshold()
                : BigDecimal.ZERO;
    }

    private BigDecimal thresholdRevenue() {
        if (properties == null || properties.getReconcile() == null) {
            return BigDecimal.ZERO;
        }
        return properties.getReconcile().getRevenueDiffThreshold() != null
                ? properties.getReconcile().getRevenueDiffThreshold()
                : BigDecimal.ZERO;
    }
}
