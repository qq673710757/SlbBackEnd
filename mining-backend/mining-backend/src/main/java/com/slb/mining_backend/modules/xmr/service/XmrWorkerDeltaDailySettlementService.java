package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.config.XmrPoolProperties;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerEarningDeltaMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * xmr_worker_earning_delta -> earnings_history 的日结算任务。
 *
 * 关键点：
 * - xmr_worker_earning_delta.window_end 在库中以“UTC 语义的 DATETIME”存储（LocalDateTime 无时区）；
 * - 业务日以 Asia/Shanghai 计算，因此必须把“昨日 00:00-24:00（BJT）”转换成对应的 UTC 窗口；
 * - 全流程在单个事务中完成：按窗口聚合 -> 写 earnings_history / daily_stats -> 置 delta settled=1；
 * - 严格幂等：只处理 settled=0。
 */
@Service
@Slf4j
public class XmrWorkerDeltaDailySettlementService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneOffset.UTC;

    private final XmrWorkerEarningDeltaMapper deltaMapper;
    private final XmrWorkerDeltaDailySettlementTxService txService;
    private final int maxDaysPerRun;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean enabled;

    public XmrWorkerDeltaDailySettlementService(XmrWorkerEarningDeltaMapper deltaMapper,
                                                XmrWorkerDeltaDailySettlementTxService txService,
                                                XmrPoolProperties poolProperties, // 保留注入：用于启动期校验 atomicPerXmr 配置存在
                                                @Value("${app.settlement.worker-delta-daily-enabled:false}") boolean enabled,
                                                @Value("${app.settlement.worker-delta-max-days-per-run:7}") int maxDaysPerRun) {
        this.deltaMapper = deltaMapper;
        this.txService = txService;
        this.enabled = enabled;
        // 这里不直接使用 atomicPerXmr，只在启动期借助注入触发配置校验/绑定；
        // 真正的换算在 Tx Service 中使用同一份配置值。
        long configured = poolProperties.getDefaultProvider().getUnit().getAtomicPerXmr();
        if (configured <= 0) {
            log.warn("Invalid atomicPerXmr from XmrPoolProperties: {}. Daily settlement will fail until it is fixed.", configured);
        }
        this.maxDaysPerRun = Math.max(1, maxDaysPerRun);
    }

    /**
     * 每日北京时间 00:10 结算“昨日业务日”的 delta；若存在更早未结算数据，则自动追赶（按天最多追赶 maxDaysPerRun 天）。
     */
    @Scheduled(cron = "${app.settlement.worker-delta-daily-cron:0 10 0 * * ?}", zone = "Asia/Shanghai")
    public void dailySettlementJob() {
        if (!enabled) {
            log.info("Worker-delta daily settlement is disabled (app.settlement.worker-delta-daily-enabled=false), skip execution");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDate todayBjt = ZonedDateTime.now(BJT).toLocalDate();
            LocalDate yesterdayBjt = todayBjt.minusDays(1);

            LocalDateTime minUnsettledEndUtc = deltaMapper.selectMinUnsettledWindowEnd();
            if (minUnsettledEndUtc == null) {
                log.info("Worker-delta daily settlement skip: no unsettled records (targetBusinessDay={})", yesterdayBjt);
                return;
            }

            LocalDate earliestBjt = minUnsettledEndUtc.atZone(UTC).withZoneSameInstant(BJT).toLocalDate();
            // 不允许结算“今天/未来”的业务日（今天 00:00-24:00 尚未结束）
            if (!earliestBjt.isBefore(todayBjt)) {
                log.info("Worker-delta daily settlement skip: earliest unsettled business day is {} (todayBjt={})", earliestBjt, todayBjt);
                return;
            }

            LocalDate startDay = earliestBjt.isAfter(yesterdayBjt) ? yesterdayBjt : earliestBjt;
            int days = 0;
            LocalDate day = startDay;
            while (!day.isAfter(yesterdayBjt) && days < maxDaysPerRun) {
                LocalDate processingDay = day;
                try {
                    txService.settleOneBusinessDay(processingDay);
                } catch (Exception ex) {
                    // 失败直接中断，等待下次重试；避免在异常状态下连续推进日期造成更多偏差
                    log.error("Worker-delta daily settlement failed for businessDayBjt={}: {}", processingDay, ex.getMessage());
                    break;
                }
                day = day.plusDays(1);
                days++;
            }

            if (day.isBefore(yesterdayBjt.plusDays(1))) {
                log.warn("Worker-delta daily settlement reached maxDaysPerRun={}, remaining backlog from {} to {} will be processed in next runs",
                        maxDaysPerRun, day, yesterdayBjt);
            }
        } finally {
            running.set(false);
        }
    }
}


