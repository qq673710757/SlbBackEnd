package com.slb.mining_backend.modules.earnings.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.earnings.entity.EarningsHistory;
import com.slb.mining_backend.modules.earnings.mapper.EarningsHistoryMapper;
import com.slb.mining_backend.modules.earnings.mapper.EarningsMapper;
import com.slb.mining_backend.modules.earnings.vo.*;
import com.slb.mining_backend.modules.invite.entity.CommissionRecord;
import com.slb.mining_backend.modules.invite.entity.PlatformCommission;
import com.slb.mining_backend.modules.invite.mapper.CommissionRecordMapper;
import com.slb.mining_backend.modules.invite.mapper.PlatformCommissionMapper;
import com.slb.mining_backend.modules.invite.service.InviteService;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
public class EarningsService {

    private final EarningsMapper earningsMapper;
    private final UserMapper userMapper;
    private final MarketDataService marketDataService;
    private final InviteService inviteService;
    private final PlatformCommissionMapper platformCommissionMapper;
    private final EarningsHistoryMapper earningsHistoryMapper;
    private final CommissionRecordMapper commissionRecordMapper;
    private final PlatformSettingsService platformSettingsService;

    @Value("${app.platform.commission-rate}")
    private BigDecimal platformCommissionRate;
    @Value("${app.earnings.estimate.xmr-block-reward}")
    private BigDecimal xmrBlockReward;
    @Value("${app.earnings.estimate.blocks-per-hour}")
    private BigDecimal blocksPerHour;
    @Value("${app.earnings.estimate.bonus-factor}")
    private BigDecimal bonusFactor;

    @Value("${app.earnings.estimate.manual-cpu-daily-cny-per-1000h:0}")
    private BigDecimal manualCpuDailyCnyPer1000H;

    @Value("${app.earnings.estimate.manual-gpu-daily-cny-per-1mh:0}")
    private BigDecimal manualGpuDailyCnyPer1Mh;

    // 增加一个全网算力兜底值 (3.0 GH/s = 3000 MH/s)，防止分母过小导致收益预估爆炸
    private static final BigDecimal FALLBACK_NETWORK_HASHRATE = BigDecimal.valueOf(3_000L);
    // 最小有效算力阈值 (10 MH/s)，低于此值认为数据无效(可能回退到了本地单机算力)
    private static final BigDecimal MIN_VALID_HASHRATE = BigDecimal.valueOf(10L);
    // CNY 金额统一保留 4 位小数
    private static final int CNY_SCALE = 4;
    @Value("${app.external-api.active-port-profit-max:0.1}")
    private BigDecimal activePortProfitMax;

    @Autowired
    public EarningsService(EarningsMapper earningsMapper, UserMapper userMapper, MarketDataService marketDataService,
                           InviteService inviteService, PlatformCommissionMapper platformCommissionMapper,
                           EarningsHistoryMapper earningsHistoryMapper, CommissionRecordMapper commissionRecordMapper,
                           PlatformSettingsService platformSettingsService) {
        this.earningsMapper = earningsMapper;
        this.userMapper = userMapper;
        this.marketDataService = marketDataService;
        this.inviteService = inviteService;
        this.platformCommissionMapper = platformCommissionMapper;
        this.earningsHistoryMapper = earningsHistoryMapper;
        this.commissionRecordMapper = commissionRecordMapper;
        this.platformSettingsService = platformSettingsService;
    }

    /**
     * 核心方法：处理一笔新产生的原始收益
     * @param earningUser 产生收益的用户
     * @param deviceId 产生收益的设备ID
     * @param originalEarningAmount 原始收益金额 (CAL)
     */
    @Transactional
    public void processNewEarning(User earningUser, String deviceId, BigDecimal originalEarningAmount) {
        // 1. 计算并扣除平台佣金
        BigDecimal platformRate = resolvePlatformCommissionRate();
        BigDecimal platformCommission = originalEarningAmount.multiply(platformRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal userNetEarning = originalEarningAmount.subtract(platformCommission);

        // 2. 将用户净收益记入历史
        EarningsHistory eh = new EarningsHistory();
        eh.setUserId(earningUser.getId());
        eh.setDeviceId(deviceId);
        eh.setAmountCal(userNetEarning);
        eh.setAmountCny(userNetEarning.multiply(marketDataService.getCalToCnyRate()).setScale(CNY_SCALE, RoundingMode.HALF_UP));
        // earning_type 列为枚举(CPU/GPU...)，统一用 CPU 兼容存储
        eh.setEarningType("CPU");
        earningsHistoryMapper.insert(eh); // 假设有此方法, 并且返回了自增ID到eh对象

        // 2.1 更新每日收益统计
        earningsMapper.upsertDailyStats(
                earningUser.getId(),
                LocalDate.now(),
                userNetEarning,
                eh.getAmountCny(),
                "CPU"
        );

        // 3. 将平台佣金记录到平台池
        PlatformCommission pc = new PlatformCommission();
        pc.setSourceEarningId(eh.getId()); // 关联收益历史记录
        pc.setUserId(earningUser.getId());
        pc.setDeviceId(deviceId);
        pc.setOriginalEarningAmount(originalEarningAmount);
        pc.setPlatformRate(platformRate);
        pc.setPlatformCommissionAmount(platformCommission);
        pc.setCurrency("CAL");
        platformCommissionMapper.insert(pc);

        // 4. 更新用户钱包（净收益）
        userMapper.updateUserWallet(earningUser.getId(), userNetEarning);

        // 5. 处理邀请佣金（从平台池支付）
        if (earningUser.getInviterId() != null) {
            Long inviterId = earningUser.getInviterId();
            BigDecimal inviterRate = inviteService.getCommissionRateForUser(inviterId);
            // 基于原始收益计算邀请佣金
            BigDecimal invitationCommission = originalEarningAmount.multiply(inviterRate).setScale(8, RoundingMode.HALF_UP);

            if (invitationCommission.compareTo(BigDecimal.ZERO) > 0) {
                // 记录邀请佣金
                CommissionRecord cr = new CommissionRecord();
                cr.setUserId(inviterId); // 佣金受益人
                cr.setInviteeId(earningUser.getId()); // 贡献者
                cr.setSourceEarningId(eh.getId()); // 关联收益历史记录
                cr.setCommissionAmount(invitationCommission);
                cr.setCommissionRate(inviterRate);
                commissionRecordMapper.insert(cr); // 假设有此方法

                // 更新邀请人的钱包
                userMapper.updateUserWallet(inviterId, invitationCommission);
            }
        }
    }

    /**
     * [定时任务] 每30分钟清空一次排行榜缓存，强制下次请求重新计算
     */
    @Scheduled(cron = "0 */30 * * * ?", zone = "Asia/Shanghai")
    @CacheEvict(cacheNames = {"leaderboardPageCache", "leaderboardMyRankCache"}, allEntries = true)
    public void evictLeaderboardCache() {
        // 这个方法体可以是空的，注解会自动完成缓存清理工作
    }

    /**
     * 获取收益排行榜（对外接口方法）：入参校验 + 组装返回。
     *
     * 缓存策略：
     * - 榜单分页（type/page/size）共享缓存，提高命中率；
     * - myRank（type/userId）单独缓存，保证“不同用户”不互相污染且不会把 userId 混进榜单页缓存 key。
     */
    public LeaderboardVo getLeaderboard(Long userId, String type, int page, int size) {
        String normalizedType = normalizeLeaderboardType(type);
        validateLeaderboardPaging(page, size);

        // 为保证缓存口径一致：endTime 统一按 30 分钟窗口对齐（与定时清缓存一致）。
        LocalDateTime endTime = floorToHalfHour(LocalDateTime.now());
        LocalDateTime startTime = computeLeaderboardStartTime(normalizedType, endTime);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String startTimeStr = startTime.format(formatter);
        String endTimeStr = endTime.format(formatter);

        PageVo<LeaderboardVo.RankItem> leaderboard = getLeaderboardPageCached(normalizedType, startTimeStr, endTimeStr, page, size);
        LeaderboardVo.RankItem myRank = getMyRankCached(userId, normalizedType, startTimeStr, endTimeStr);

        LeaderboardVo vo = new LeaderboardVo();
        vo.setType(normalizedType);
        vo.setStartTime(startTime);
        vo.setEndTime(endTime);
        vo.setLeaderboard(leaderboard);
        vo.setMyRank(myRank);
        return vo;
    }

    @Cacheable(cacheNames = "leaderboardPageCache",
            key = "'type=' + #type + ':page=' + #page + ':size=' + #size",
            sync = true)
    protected PageVo<LeaderboardVo.RankItem> getLeaderboardPageCached(String type, String startTime, String endTime, int page, int size) {
        long total = earningsMapper.countLeaderboardUsers(startTime, endTime);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<LeaderboardVo.RankItem> list = earningsMapper.findLeaderboard(startTime, endTime, offset, size);
        return new PageVo<>(total, page, size, list);
    }

    @Cacheable(cacheNames = "leaderboardMyRankCache",
            key = "'type=' + #type + ':user=' + #userId",
            sync = true)
    protected LeaderboardVo.RankItem getMyRankCached(Long userId, String type, String startTime, String endTime) {
        return earningsMapper.findUserRank(userId, startTime, endTime);
    }

    private String normalizeLeaderboardType(String type) {
        if (type == null || type.isBlank()) {
            return "month";
        }
        String v = type.trim().toLowerCase();
        if (!"week".equals(v) && !"month".equals(v)) {
            throw new BizException("type 参数非法，仅支持 week 或 month");
        }
        return v;
    }

    private void validateLeaderboardPaging(int page, int size) {
        if (page < 1) {
            throw new BizException("page 必须 >= 1");
        }
        if (size < 1 || size > 100) {
            throw new BizException("size 参数非法，仅支持 1-100");
        }
    }

    private LocalDateTime computeLeaderboardStartTime(String type, LocalDateTime endTime) {
        if ("week".equalsIgnoreCase(type)) {
            return endTime.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN);
        }
        return endTime.with(TemporalAdjusters.firstDayOfMonth()).with(LocalTime.MIN);
    }

    /**
     * 将时间向下对齐到半小时窗口，例如 10:02->10:00, 10:35->10:30。
     * 用于排行榜口径与缓存一致性（避免每次请求 endTime 都不同导致口径漂移）。
     */
    private LocalDateTime floorToHalfHour(LocalDateTime time) {
        int minute = time.getMinute();
        int floored = (minute / 30) * 30;
        return time.withMinute(floored).withSecond(0).withNano(0);
    }

    public BalanceVo getBalance(Long userId) {
        User user = userMapper.selectById(userId).orElseThrow();
        BalanceVo vo = new BalanceVo();
        vo.setCalBalance(user.getCalBalance());
        vo.setCashBalance(user.getCashBalance());
        vo.setTotalEarnings(user.getTotalEarnings());
        vo.setTotalWithdrawn(user.getTotalWithdrawn());
        vo.setCalToCnyRate(marketDataService.getCalToCnyRate());
        return vo;
    }

    public PageVo<EarningsHistoryItemVo> getEarningsHistory(Long userId, String deviceId, String earningType, LocalDate startDate, LocalDate endDate, int page, int size) {
        String normalizedEarningType = normalizeQueryEarningType(earningType);
        long total = earningsMapper.countHistory(userId, deviceId, normalizedEarningType, startDate, endDate);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<EarningsHistoryItemVo> list = earningsMapper.findHistoryPaginated(userId, deviceId, normalizedEarningType, startDate, endDate, offset, size);
        return new PageVo<>(total, page, size, list);
    }

    /**
     * 按小时汇总收益历史：用于列表展示“一小时按收益类型多条”，明细仍可通过 /history 查询。
     */
    public PageVo<EarningsHistoryHourlyItemVo> getEarningsHistoryHourly(Long userId, String deviceId, String groupBy, String earningType, LocalDate startDate, LocalDate endDate, int page, int size) {
        String normalizedEarningType = normalizeHourlyEarningType(earningType);
        boolean groupByEarningType = isGroupByEarningType(groupBy);
        long total = earningsMapper.countHistoryHourly(userId, deviceId, normalizedEarningType, groupByEarningType, startDate, endDate);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<EarningsHistoryHourlyItemVo> list = earningsMapper.findHistoryHourlyPaginated(userId, deviceId, normalizedEarningType, groupByEarningType, startDate, endDate, offset, size);
        return new PageVo<>(total, page, size, list);
    }

    /**
     * /history-hourly 的 earningType 允许：
     * - null/空/ALL：不过滤（SQL 返回 earningType=ALL）
     * - 其它值：按该类型过滤（统一转大写）
     */
    private String normalizeHourlyEarningType(String earningType) {
        if (earningType == null) return null;
        String v = earningType.trim();
        if (v.isEmpty()) return null;
        if ("ALL".equalsIgnoreCase(v)) return null;
        return v.toUpperCase();
    }

    /**
     * groupBy 允许：
     * - null/空：按小时+收益类型聚合（默认）
     * - hour：按小时聚合（合并收益类型）
     * - earningType：按小时+收益类型聚合
     */
    private boolean isGroupByEarningType(String groupBy) {
        if (groupBy == null) return true;
        String v = groupBy.trim();
        if (v.isEmpty()) return true;
        if ("hour".equalsIgnoreCase(v)) return false;
        return "earningType".equalsIgnoreCase(v);
    }

    private String normalizeQueryEarningType(String earningType) {
        if (earningType == null) return null;
        String v = earningType.trim();
        if (v.isEmpty()) return null;
        if ("ALL".equalsIgnoreCase(v)) return null;
        return v.toUpperCase();
    }

    public List<DailyStatsVo> getDailyStats(Long userId, String deviceId, LocalDate startDate, LocalDate endDate) {
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(29);
        }
        return earningsMapper.findDailyStats(userId, deviceId, startDate, endDate);
    }

    /**
     * 用户维度的每日收益统计（分页）。
     */
    public PageVo<DailyStatsVo> getDailyStatsPage(Long userId, LocalDate startDate, LocalDate endDate, int page, int size) {
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(29);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int offset = Math.max(0, (safePage - 1) * safeSize);
        long total = earningsMapper.countDailyStats(userId, startDate, endDate);
        List<DailyStatsVo> list = earningsMapper.findDailyStatsPaginated(userId, startDate, endDate, offset, safeSize);
        return new PageVo<>(total, safePage, safeSize, list);
    }

    /**
     * 设备维度的每日收益统计（分页）。
     */
    public PageVo<DailyStatsVo> getDailyStatsByDevice(Long userId, String deviceId, LocalDate startDate, LocalDate endDate, int page, int size) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BizException("deviceId is required");
        }
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(29);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int offset = Math.max(0, (safePage - 1) * safeSize);
        long total = earningsMapper.countDailyStatsByDevice(userId, deviceId, startDate, endDate);
        List<DailyStatsVo> list = earningsMapper.findDailyStatsByDevicePaginated(userId, deviceId, startDate, endDate, offset, safeSize);
        return new PageVo<>(total, safePage, safeSize, list);
    }

    /**
     * 未登录收益预估：入参为 CPU 算力（H/s）与 GPU 算力（MH/s，可为空；负数按 0 处理）。
     *
     * 口径统一：
     * - 先算出 XMR（优先使用矿池 activePortProfit；缺失则 fallback 到“块奖励 * blocksPerHour * 份额”）
     * - 再按 ratio（1 CAL = ratio * XMR，因此 CAL = XMR / ratio）换算为 CAL
     * - 再按 CAL/CNY 汇率换算为 CNY
     *
     * bonusFactor 语义：乘数（multiplier）
     * - 1.0 表示无加成
     * - total = base * bonusFactor
     */
    public EstimateVo getEstimate(Double cpuHashrate, Double gpuHashrate) {
        BigDecimal cpuHps = sanitizeHashrateHps(cpuHashrate);
        BigDecimal gpuMh = sanitizeHashrate(gpuHashrate);
        BigDecimal cpuMh = toMhFromHps(cpuHps);
        BigDecimal totalHashrate = cpuMh.add(gpuMh);

        BigDecimal poolTotalHashrate = marketDataService.getPoolTotalHashrate();
        // 如果获取到的总算力太小（说明可能降级到了平台设备和/或本地测试算力），强制使用全网算力兜底值
        if (poolTotalHashrate.compareTo(MIN_VALID_HASHRATE) < 0) {
            poolTotalHashrate = FALLBACK_NETWORK_HASHRATE;
        }

        BigDecimal ratio = safePositive(marketDataService.getCalXmrRatio());
        BigDecimal calToCnyRate = safePositive(marketDataService.getCalToCnyRate());
        BigDecimal xmrToCnyRate = safePositive(marketDataService.getXmrToCnyRate());
        BigDecimal bonusMultiplier = safeBonusMultiplier(bonusFactor);
        BigDecimal netMultiplier = resolveEstimateNetMultiplier();

        BigDecimal manualDailyCny = computeManualDailyCny(cpuMh, gpuMh);
        if (isPositive(manualDailyCny)) {
            EstimateVo vo = new EstimateVo();
            BigDecimal dailyCal = safeCalFromCny(manualDailyCny, calToCnyRate);
            dailyCal = applyEstimateNetMultiplier(dailyCal, netMultiplier);
            BigDecimal hourlyCal = dailyCal.divide(BigDecimal.valueOf(24), 18, RoundingMode.HALF_UP);
            BigDecimal monthlyCal = hourlyCal.multiply(BigDecimal.valueOf(720));
            vo.setHourly(createEstimateDetail(hourlyCal, calToCnyRate));
            vo.setDaily(createEstimateDetail(dailyCal, calToCnyRate));
            vo.setMonthly(createEstimateDetail(monthlyCal, calToCnyRate));
            if (isPositive(totalHashrate)) {
                vo.setCpuContribution(cpuMh.divide(totalHashrate, 8, RoundingMode.HALF_UP));
                vo.setGpuContribution(gpuMh.divide(totalHashrate, 8, RoundingMode.HALF_UP));
            } else {
                vo.setCpuContribution(BigDecimal.ZERO);
                vo.setGpuContribution(BigDecimal.ZERO);
            }
            vo.setCurrentXmrPrice(xmrToCnyRate.setScale(CNY_SCALE, RoundingMode.HALF_UP));
            vo.setCalToCnyRate(calToCnyRate);
            return vo;
        }

        BigDecimal hourlyXmrBase = BigDecimal.ZERO;
        if (isPositive(totalHashrate)) {
            // 优先使用矿池提供的“单位算力收益”字段（更贴近真实矿池收益）
            BigDecimal activePortProfit = sanitizeActivePortProfit(
                    safePositive(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()));
            if (isPositive(activePortProfit)) {
                // XMR/day = MH/s * (XMR/(MH/s·day))
                BigDecimal dailyXmr = totalHashrate.multiply(activePortProfit);
                hourlyXmrBase = dailyXmr.divide(BigDecimal.valueOf(24), 18, RoundingMode.HALF_UP);
            } else if (isPositive(poolTotalHashrate)) {
                // fallback：按“块奖励 * blocksPerHour”估算矿池小时产出
                BigDecimal poolHourlyRewardXmr = safeConfig(xmrBlockReward).multiply(safeConfig(blocksPerHour));
                if (isPositive(poolHourlyRewardXmr)) {
                    BigDecimal share = totalHashrate.divide(poolTotalHashrate, 18, RoundingMode.HALF_UP);
                    hourlyXmrBase = share.multiply(poolHourlyRewardXmr);
                }
            }
        }

        BigDecimal hourlyXmr = hourlyXmrBase.multiply(bonusMultiplier);
        BigDecimal hourlyCal = xmrToCal(hourlyXmr, ratio);
        hourlyCal = applyEstimateNetMultiplier(hourlyCal, netMultiplier);

        EstimateVo vo = new EstimateVo();
        vo.setHourly(createEstimateDetail(hourlyCal, calToCnyRate));
        vo.setDaily(createEstimateDetail(hourlyCal.multiply(BigDecimal.valueOf(24)), calToCnyRate));
        vo.setMonthly(createEstimateDetail(hourlyCal.multiply(BigDecimal.valueOf(720)), calToCnyRate));

        if (isPositive(totalHashrate)) {
            vo.setCpuContribution(cpuMh.divide(totalHashrate, 8, RoundingMode.HALF_UP));
            vo.setGpuContribution(gpuMh.divide(totalHashrate, 8, RoundingMode.HALF_UP));
        } else {
            vo.setCpuContribution(BigDecimal.ZERO);
            vo.setGpuContribution(BigDecimal.ZERO);
        }

        vo.setCurrentXmrPrice(xmrToCnyRate.setScale(CNY_SCALE, RoundingMode.HALF_UP));
        vo.setCalToCnyRate(calToCnyRate);
        return vo;
    }

    private EstimateVo.EstimateDetail createEstimateDetail(BigDecimal calAmount, BigDecimal rate) {
        EstimateVo.EstimateDetail detail = new EstimateVo.EstimateDetail();
        detail.setCalAmount(calAmount.setScale(8, RoundingMode.HALF_UP));
        detail.setCnyAmount(calAmount.multiply(rate).setScale(CNY_SCALE, RoundingMode.HALF_UP));
        return detail;
    }

    private BigDecimal sanitizeHashrate(Double hashrateMh) {
        if (hashrateMh == null) {
            return BigDecimal.ZERO;
        }
        if (hashrateMh <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(hashrateMh);
    }

    private BigDecimal sanitizeHashrateHps(Double hashrateHps) {
        if (hashrateHps == null) {
            return BigDecimal.ZERO;
        }
        if (hashrateHps <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(hashrateHps);
    }

    private BigDecimal toMhFromHps(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal safePositive(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return v;
    }

    private BigDecimal safeBonusMultiplier(BigDecimal v) {
        // bonusFactor 作为“乘数”，<=0 视为无加成
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return v;
    }

    private BigDecimal resolveEstimateNetMultiplier() {
        BigDecimal platformRate = clampRate(safePositive(resolvePlatformCommissionRate()));
        BigDecimal userRate = BigDecimal.ONE.subtract(platformRate);
        if (userRate.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (userRate.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return userRate;
    }

    private BigDecimal resolvePlatformCommissionRate() {
        if (platformSettingsService == null) {
            return platformCommissionRate;
        }
        BigDecimal rate = platformSettingsService.getPlatformCommissionRate();
        return rate != null ? rate : platformCommissionRate;
    }

    private BigDecimal applyEstimateNetMultiplier(BigDecimal value, BigDecimal multiplier, int scale) {
        if (!isPositive(value)) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal rate = multiplier == null ? BigDecimal.ONE : multiplier;
        return value.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal applyEstimateNetMultiplier(BigDecimal value, BigDecimal multiplier) {
        if (!isPositive(value)) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = multiplier == null ? BigDecimal.ONE : multiplier;
        return value.multiply(rate);
    }

    private BigDecimal clampRate(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (v.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return v;
    }

    private BigDecimal safeConfig(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal xmrToCal(BigDecimal xmrAmount, BigDecimal ratio) {
        if (!isPositive(xmrAmount) || !isPositive(ratio)) {
            return BigDecimal.ZERO;
        }
        // 1 CAL = ratio * XMR，因此 CAL = XMR / ratio
        return xmrAmount.divide(ratio, 18, RoundingMode.HALF_UP);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal computeManualDailyCny(BigDecimal cpuMh, BigDecimal gpuMh) {
        BigDecimal total = BigDecimal.ZERO;
        if (isPositive(manualCpuDailyCnyPer1000H) && isPositive(cpuMh)) {
            total = total.add(cpuMh.multiply(BigDecimal.valueOf(1000)).multiply(manualCpuDailyCnyPer1000H));
        }
        if (isPositive(manualGpuDailyCnyPer1Mh) && isPositive(gpuMh)) {
            total = total.add(gpuMh.multiply(manualGpuDailyCnyPer1Mh));
        }
        return total;
    }

    private BigDecimal safeCalFromCny(BigDecimal cny, BigDecimal calToCnyRate) {
        if (!isPositive(cny) || !isPositive(calToCnyRate)) {
            return BigDecimal.ZERO;
        }
        return cny.divide(calToCnyRate, 18, RoundingMode.HALF_UP);
    }

    /**
     * 获取单位收益（人民币）
     * - CPU：每 0.001 MH/s · 天 的收益（CNY）= 1000 H/s
     * - GPU：每 1 MH/s · 天 的收益（CNY）
     *
     * 说明：当前预估模型不区分 CPU/GPU 的“收益权重”，因此本质上是同一套“每 MH/s·天”的收益，换单位展示。
     */
    public com.slb.mining_backend.modules.earnings.vo.UnitIncomeVo getUnitIncomeCny() {
        BigDecimal poolTotalHashrate = marketDataService.getPoolTotalHashrate();
        if (poolTotalHashrate.compareTo(MIN_VALID_HASHRATE) < 0) {
            poolTotalHashrate = FALLBACK_NETWORK_HASHRATE;
        }

        BigDecimal ratio = safePositive(marketDataService.getCalXmrRatio());
        BigDecimal calToCnyRate = safePositive(marketDataService.getCalToCnyRate());
        BigDecimal xmrToCnyRate = safePositive(marketDataService.getXmrToCnyRate());
        BigDecimal bonusMultiplier = safeBonusMultiplier(bonusFactor);
        BigDecimal netMultiplier = resolveEstimateNetMultiplier();

        // CPU 单位：0.001 MH/s；GPU 单位：1 MH/s
        BigDecimal cpuUnitHashrate = BigDecimal.valueOf(0.001d);
        BigDecimal gpuUnitHashrate = BigDecimal.ONE;

        // 先算“单位算力”的 daily XMR，再按系统口径换算到 CNY
        BigDecimal activePortProfit = sanitizeActivePortProfit(
                safePositive(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()));
        boolean usingProfit = isPositive(activePortProfit);

        BigDecimal cpuDailyXmrBase;
        BigDecimal gpuDailyXmrBase;
        if (usingProfit) {
            cpuDailyXmrBase = cpuUnitHashrate.multiply(activePortProfit);
            gpuDailyXmrBase = gpuUnitHashrate.multiply(activePortProfit);
        } else if (isPositive(poolTotalHashrate)) {
            BigDecimal poolDailyRewardXmr = safeConfig(xmrBlockReward)
                    .multiply(safeConfig(blocksPerHour))
                    .multiply(BigDecimal.valueOf(24));
            BigDecimal cpuShare = cpuUnitHashrate.divide(poolTotalHashrate, 18, RoundingMode.HALF_UP);
            BigDecimal gpuShare = gpuUnitHashrate.divide(poolTotalHashrate, 18, RoundingMode.HALF_UP);
            cpuDailyXmrBase = isPositive(poolDailyRewardXmr) ? cpuShare.multiply(poolDailyRewardXmr) : BigDecimal.ZERO;
            gpuDailyXmrBase = isPositive(poolDailyRewardXmr) ? gpuShare.multiply(poolDailyRewardXmr) : BigDecimal.ZERO;
        } else {
            cpuDailyXmrBase = BigDecimal.ZERO;
            gpuDailyXmrBase = BigDecimal.ZERO;
        }

        BigDecimal cpuDailyXmr = cpuDailyXmrBase.multiply(bonusMultiplier);
        BigDecimal gpuDailyXmr = gpuDailyXmrBase.multiply(bonusMultiplier);

        BigDecimal cpuDailyCal = xmrToCal(cpuDailyXmr, ratio);
        BigDecimal gpuDailyCal = xmrToCal(gpuDailyXmr, ratio);

        // 单位收益建议多保留几位，避免客户端乘算力时误差放大；展示时前端可再格式化为 2 位。
        BigDecimal cpuDailyCny = cpuDailyCal.multiply(calToCnyRate).setScale(6, RoundingMode.HALF_UP);
        BigDecimal gpuDailyCny = gpuDailyCal.multiply(calToCnyRate).setScale(6, RoundingMode.HALF_UP);
        if (isPositive(manualCpuDailyCnyPer1000H)) {
            cpuDailyCny = manualCpuDailyCnyPer1000H.setScale(6, RoundingMode.HALF_UP);
        }
        if (isPositive(manualGpuDailyCnyPer1Mh)) {
            gpuDailyCny = manualGpuDailyCnyPer1Mh.setScale(6, RoundingMode.HALF_UP);
        }
        cpuDailyCny = applyEstimateNetMultiplier(cpuDailyCny, netMultiplier, 6);
        gpuDailyCny = applyEstimateNetMultiplier(gpuDailyCny, netMultiplier, 6);

        com.slb.mining_backend.modules.earnings.vo.UnitIncomeVo vo = new com.slb.mining_backend.modules.earnings.vo.UnitIncomeVo();
        vo.setCpuDailyIncomeCnyPer1000H(cpuDailyCny);
        vo.setGpuDailyIncomeCnyPer1Mh(gpuDailyCny);
        vo.setBonusFactor(bonusMultiplier);
        vo.setPoolTotalHashrateHps(poolTotalHashrate);
        vo.setUsingActivePortProfit(usingProfit);
        vo.setActivePortProfitXmrPerHashDay(usingProfit ? activePortProfit : BigDecimal.ZERO);
        vo.setCalXmrRatio(ratio);
        vo.setCalToCnyRate(calToCnyRate);
        vo.setXmrToCnyRate(xmrToCnyRate);
        return vo;
    }

    /**
     * GPU 单位收益接口：复用 getUnitIncomeCny 的计算结果。
     */
    public com.slb.mining_backend.modules.earnings.vo.GpuUnitIncomeVo getGpuUnitIncomeCny() {
        com.slb.mining_backend.modules.earnings.vo.UnitIncomeVo unit = getUnitIncomeCny();
        com.slb.mining_backend.modules.earnings.vo.GpuUnitIncomeVo vo = new com.slb.mining_backend.modules.earnings.vo.GpuUnitIncomeVo();
        vo.setGpuDailyIncomeCnyPer1Mh(unit.getGpuDailyIncomeCnyPer1Mh());
        vo.setBonusFactor(unit.getBonusFactor());
        vo.setPoolTotalHashrateHps(unit.getPoolTotalHashrateHps());
        vo.setUsingActivePortProfit(unit.getUsingActivePortProfit());
        vo.setActivePortProfitXmrPerHashDay(unit.getActivePortProfitXmrPerHashDay());
        vo.setCalXmrRatio(unit.getCalXmrRatio());
        vo.setCalToCnyRate(unit.getCalToCnyRate());
        vo.setXmrToCnyRate(unit.getXmrToCnyRate());
        return vo;
    }

    /**
     * GPU 日收益估算（CFX / XMR）。输入单位为 MH/s。
     */
    public com.slb.mining_backend.modules.earnings.vo.GpuDailyIncomeVo getGpuDailyIncome(Double gpuHashrateMh) {
        BigDecimal hashrateMh = sanitizeHashrate(gpuHashrateMh);
        if (isPositive(manualGpuDailyCnyPer1Mh)) {
            com.slb.mining_backend.modules.earnings.vo.GpuDailyIncomeVo vo =
                    new com.slb.mining_backend.modules.earnings.vo.GpuDailyIncomeVo();
            vo.setHashrateMh(hashrateMh);
            vo.setHashrateHps(hashrateMh.multiply(BigDecimal.valueOf(1_000_000L)));
            vo.setDailyCfx(BigDecimal.ZERO);
            vo.setDailyXmr(BigDecimal.ZERO);
            vo.setUsingActivePortProfit(false);
            vo.setCfxToXmrRate(BigDecimal.ZERO);
            vo.setActivePortProfitXmrPerHashDay(BigDecimal.ZERO);
            vo.setPoolTotalHashrateHps(BigDecimal.ZERO);
            return vo;
        }

        BigDecimal poolTotalHashrate = marketDataService.getPoolTotalHashrate();
        if (poolTotalHashrate.compareTo(MIN_VALID_HASHRATE) < 0) {
            poolTotalHashrate = FALLBACK_NETWORK_HASHRATE;
        }

        BigDecimal cfxDailyCoinPerMh = safePositive(marketDataService.getCfxDailyCoinPerMh());
        BigDecimal cfxToXmrRate = safePositive(marketDataService.getCfxToXmrRate());
        BigDecimal bonusMultiplier = safeBonusMultiplier(bonusFactor);

        BigDecimal dailyCfx = BigDecimal.ZERO;
        BigDecimal dailyXmr = BigDecimal.ZERO;
        boolean usingProfit = false;
        BigDecimal activePortProfit = BigDecimal.ZERO;

        if (isPositive(hashrateMh)) {
            if (isPositive(cfxDailyCoinPerMh)) {
                BigDecimal dailyCfxBase = hashrateMh.multiply(cfxDailyCoinPerMh);
                dailyCfx = dailyCfxBase.multiply(bonusMultiplier).setScale(8, RoundingMode.HALF_UP);
                if (isPositive(dailyCfx) && isPositive(cfxToXmrRate)) {
                    dailyXmr = dailyCfx.multiply(cfxToXmrRate).setScale(12, RoundingMode.HALF_UP);
                }
            } else {
                activePortProfit = sanitizeActivePortProfit(
                        safePositive(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()));
                usingProfit = isPositive(activePortProfit);
                BigDecimal dailyXmrBase = BigDecimal.ZERO;
                if (usingProfit) {
                    dailyXmrBase = hashrateMh.multiply(activePortProfit);
                } else if (isPositive(poolTotalHashrate)) {
                    BigDecimal poolDailyRewardXmr = safeConfig(xmrBlockReward)
                            .multiply(safeConfig(blocksPerHour))
                            .multiply(BigDecimal.valueOf(24));
                    BigDecimal share = hashrateMh.divide(poolTotalHashrate, 18, RoundingMode.HALF_UP);
                    dailyXmrBase = isPositive(poolDailyRewardXmr) ? share.multiply(poolDailyRewardXmr) : BigDecimal.ZERO;
                }
                dailyXmr = dailyXmrBase.multiply(bonusMultiplier).setScale(12, RoundingMode.HALF_UP);
                if (isPositive(dailyXmr) && isPositive(cfxToXmrRate)) {
                    dailyCfx = dailyXmr.divide(cfxToXmrRate, 8, RoundingMode.HALF_UP);
                }
            }
        }

        BigDecimal netMultiplier = resolveEstimateNetMultiplier();
        dailyCfx = applyEstimateNetMultiplier(dailyCfx, netMultiplier, 8);
        dailyXmr = applyEstimateNetMultiplier(dailyXmr, netMultiplier, 12);

        com.slb.mining_backend.modules.earnings.vo.GpuDailyIncomeVo vo =
                new com.slb.mining_backend.modules.earnings.vo.GpuDailyIncomeVo();
        vo.setHashrateMh(hashrateMh);
        vo.setHashrateHps(hashrateMh.multiply(BigDecimal.valueOf(1_000_000L)));
        vo.setDailyXmr(dailyXmr);
        vo.setDailyCfx(dailyCfx);
        vo.setCfxToXmrRate(cfxToXmrRate);
        vo.setUsingActivePortProfit(usingProfit);
        vo.setActivePortProfitXmrPerHashDay(usingProfit ? activePortProfit : BigDecimal.ZERO);
        vo.setPoolTotalHashrateHps(poolTotalHashrate);
        return vo;
    }

    /**
     * GPU 日收益估算（RVN / XMR）。输入单位为 MH/s。
     */
    public GpuRvnDailyIncomeVo getGpuRvnDailyIncome(Double gpuHashrateMh) {
        BigDecimal hashrateMh = sanitizeHashrate(gpuHashrateMh);
        if (isPositive(manualGpuDailyCnyPer1Mh)) {
            GpuRvnDailyIncomeVo vo = new GpuRvnDailyIncomeVo();
            vo.setHashrateMh(hashrateMh);
            vo.setHashrateHps(hashrateMh.multiply(BigDecimal.valueOf(1_000_000L)));
            vo.setDailyRvn(BigDecimal.ZERO);
            vo.setDailyXmr(BigDecimal.ZERO);
            vo.setUsingActivePortProfit(false);
            vo.setRvnToXmrRate(BigDecimal.ZERO);
            vo.setActivePortProfitXmrPerHashDay(BigDecimal.ZERO);
            vo.setPoolTotalHashrateHps(BigDecimal.ZERO);
            return vo;
        }

        BigDecimal poolTotalHashrate = marketDataService.getPoolTotalHashrate();
        if (poolTotalHashrate.compareTo(MIN_VALID_HASHRATE) < 0) {
            poolTotalHashrate = FALLBACK_NETWORK_HASHRATE;
        }

        BigDecimal rvnDailyCoinPerMh = safePositive(marketDataService.getRvnDailyCoinPerMh());
        BigDecimal rvnToXmrRate = safePositive(marketDataService.getRvnToXmrRate());
        BigDecimal bonusMultiplier = safeBonusMultiplier(bonusFactor);

        BigDecimal dailyRvn = BigDecimal.ZERO;
        BigDecimal dailyXmr = BigDecimal.ZERO;
        boolean usingProfit = false;
        BigDecimal activePortProfit = BigDecimal.ZERO;

        if (isPositive(hashrateMh)) {
            if (isPositive(rvnDailyCoinPerMh)) {
                BigDecimal dailyRvnBase = hashrateMh.multiply(rvnDailyCoinPerMh);
                dailyRvn = dailyRvnBase.multiply(bonusMultiplier).setScale(8, RoundingMode.HALF_UP);
                if (isPositive(dailyRvn) && isPositive(rvnToXmrRate)) {
                    dailyXmr = dailyRvn.multiply(rvnToXmrRate).setScale(12, RoundingMode.HALF_UP);
                }
            } else {
                activePortProfit = sanitizeActivePortProfit(
                        safePositive(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()));
                usingProfit = isPositive(activePortProfit);
                BigDecimal dailyXmrBase = BigDecimal.ZERO;
                if (usingProfit) {
                    dailyXmrBase = hashrateMh.multiply(activePortProfit);
                } else if (isPositive(poolTotalHashrate)) {
                    BigDecimal poolDailyRewardXmr = safeConfig(xmrBlockReward)
                            .multiply(safeConfig(blocksPerHour))
                            .multiply(BigDecimal.valueOf(24));
                    BigDecimal share = hashrateMh.divide(poolTotalHashrate, 18, RoundingMode.HALF_UP);
                    dailyXmrBase = isPositive(poolDailyRewardXmr) ? share.multiply(poolDailyRewardXmr) : BigDecimal.ZERO;
                }
                dailyXmr = dailyXmrBase.multiply(bonusMultiplier).setScale(12, RoundingMode.HALF_UP);
                if (isPositive(dailyXmr) && isPositive(rvnToXmrRate)) {
                    dailyRvn = dailyXmr.divide(rvnToXmrRate, 8, RoundingMode.HALF_UP);
                }
            }
        }

        BigDecimal netMultiplier = resolveEstimateNetMultiplier();
        dailyRvn = applyEstimateNetMultiplier(dailyRvn, netMultiplier, 8);
        dailyXmr = applyEstimateNetMultiplier(dailyXmr, netMultiplier, 12);

        GpuRvnDailyIncomeVo vo = new GpuRvnDailyIncomeVo();
        vo.setHashrateMh(hashrateMh);
        vo.setHashrateHps(hashrateMh.multiply(BigDecimal.valueOf(1_000_000L)));
        vo.setDailyXmr(dailyXmr);
        vo.setDailyRvn(dailyRvn);
        vo.setRvnToXmrRate(rvnToXmrRate);
        vo.setUsingActivePortProfit(usingProfit);
        vo.setActivePortProfitXmrPerHashDay(usingProfit ? activePortProfit : BigDecimal.ZERO);
        vo.setPoolTotalHashrateHps(poolTotalHashrate);
        return vo;
    }

    /**
     * 累计收益总览：按来源（GPU / CPU / 邀请 / 系统补偿 / 系统激励）以及 CAL / CNY 维度汇总。
     * 汇总逻辑基于现有明细表，不改变原有入账流程。
     */
    public EarningsSummaryVo getEarningsSummary(Long userId, String settlementCurrencyCode) {
        User user = userMapper.selectById(userId).orElseThrow();
        BigDecimal totalCalCredited = safeCal(user.getTotalEarnings());

        BigDecimal calToCnyRate = marketDataService.getCalToCnyRate();
        if (calToCnyRate == null || calToCnyRate.compareTo(BigDecimal.ZERO) <= 0) {
            calToCnyRate = BigDecimal.ZERO;
        }

        // 1. GPU / CPU 挖矿累计收益（CAL 从 daily_earnings_stats 汇总，CNY 按当前 CAL/CNY 汇率折算）
        BigDecimal gpuCal = safeCal(earningsMapper.sumGpuCalEarnings(userId));
        BigDecimal cpuCal = safeCal(earningsMapper.sumCpuCalEarnings(userId));
        BigDecimal gpuCny = gpuCal.multiply(calToCnyRate).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cpuCny = cpuCal.multiply(calToCnyRate).setScale(CNY_SCALE, RoundingMode.HALF_UP);

        // 2. 邀请收益累计：来自 commission_records.commission_amount（CAL），CNY 亦按当前汇率折算
        BigDecimal inviteCal = safeCal(commissionRecordMapper.sumCommissionByUserId(userId));
        BigDecimal inviteCny = inviteCal.multiply(calToCnyRate).setScale(CNY_SCALE, RoundingMode.HALF_UP);

        // 3. 被邀请者奖励累计：基于 earnings_history.earning_type=INVITED 汇总（让利/折扣等，按 CAL 等值记账）
        BigDecimal invitedCal = safeCal(earningsMapper.sumAmountCalByType(userId, "INVITED"));
        BigDecimal invitedCny = safeCny(earningsMapper.sumAmountCnyByType(userId, "INVITED"));

        // 4. 系统补偿 & 系统激励：基于 earnings_history.earning_type 汇总
        BigDecimal compensationCal = safeCal(earningsMapper.sumAmountCalByType(userId, "COMPENSATION"));
        // 兼容两类“激励”口径：INCENTIVE（活动/运营）+ SYSTEM_INCENTIVE（系统激励）合并展示到 incentiveTotal
        BigDecimal incentiveCal = safeCal(earningsMapper.sumAmountCalByType(userId, "INCENTIVE"))
                .add(safeCal(earningsMapper.sumAmountCalByType(userId, "SYSTEM_INCENTIVE")))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal compensationCny = safeCny(earningsMapper.sumAmountCnyByType(userId, "COMPENSATION"));
        BigDecimal incentiveCny = safeCny(earningsMapper.sumAmountCnyByType(userId, "INCENTIVE"))
                .add(safeCny(earningsMapper.sumAmountCnyByType(userId, "SYSTEM_INCENTIVE")))
                .setScale(CNY_SCALE, RoundingMode.HALF_UP);

        // 4.1 实际以 CNY 结算入账的累计金额（非折合口径）：通过 platform_commissions.currency 推断
        BigDecimal gpuCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("GPU"), "CNY"));
        BigDecimal cpuCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("CPU", "POOL", "AUTO"), "CNY"));
        BigDecimal inviteCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("INVITE", "INVITE_CPU", "INVITE_GPU"), "CNY"));
        BigDecimal invitedCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("INVITED"), "CNY"));
        BigDecimal compensationCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("COMPENSATION"), "CNY"));
        BigDecimal incentiveCnySettled = safeCny(earningsMapper.sumAmountCnyByTypesAndSettleCurrency(userId, List.of("INCENTIVE", "SYSTEM_INCENTIVE"), "CNY"));
        BigDecimal totalCnySettled = safeCny(earningsMapper.sumAmountCnyBySettleCurrency(userId, "CNY"));

        // 5. 汇总
        BigDecimal totalCal = gpuCal.add(cpuCal)
                .add(inviteCal)
                .add(invitedCal)
                .add(compensationCal)
                .add(incentiveCal);
        BigDecimal totalCny = gpuCny.add(cpuCny)
                .add(inviteCny)
                .add(invitedCny)
                .add(compensationCny)
                .add(incentiveCny);
        totalCal = totalCal.setScale(8, RoundingMode.HALF_UP);
        totalCny = totalCny.setScale(CNY_SCALE, RoundingMode.HALF_UP);
        totalCnySettled = totalCnySettled.setScale(CNY_SCALE, RoundingMode.HALF_UP);

        EarningsSummaryVo vo = new EarningsSummaryVo();

        SettlementCurrency settlementCurrency = SettlementCurrency.fromCode(settlementCurrencyCode);
        vo.setSettlementCurrency(settlementCurrency.name());
        vo.setEstimatedPayout(settlementCurrency == SettlementCurrency.CNY ? totalCny : totalCal);

        vo.setGpuCalTotal(gpuCal);
        vo.setGpuCnyTotal(gpuCny);
        vo.setGpuCnySettledTotal(gpuCnySettled);
        vo.setCpuCalTotal(cpuCal);
        vo.setCpuCnyTotal(cpuCny);
        vo.setCpuCnySettledTotal(cpuCnySettled);
        vo.setInviteCalTotal(inviteCal);
        vo.setInviteCnyTotal(inviteCny);
        vo.setInviteCnySettledTotal(inviteCnySettled);
        vo.setInvitedCalTotal(invitedCal);
        vo.setInvitedCnyTotal(invitedCny);
        vo.setInvitedCnySettledTotal(invitedCnySettled);
        vo.setCompensationCalTotal(compensationCal);
        vo.setCompensationCnyTotal(compensationCny);
        vo.setCompensationCnySettledTotal(compensationCnySettled);
        vo.setIncentiveCalTotal(incentiveCal);
        vo.setIncentiveCnyTotal(incentiveCny);
        vo.setIncentiveCnySettledTotal(incentiveCnySettled);
        vo.setTotalCal(totalCal);
        vo.setTotalCny(totalCny);
        vo.setTotalCnySettled(totalCnySettled);
        vo.setTotalCalCredited(totalCalCredited);
        return vo;
    }

    private BigDecimal safeCal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : value.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal safeCny(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(CNY_SCALE, RoundingMode.HALF_UP) : value.setScale(CNY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal sanitizeActivePortProfit(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal max = activePortProfitMax;
        if (max == null || max.compareTo(BigDecimal.ZERO) <= 0) {
            max = new BigDecimal("0.1");
        }
        if (value.compareTo(max) > 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
