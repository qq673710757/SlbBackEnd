package com.slb.mining_backend.modules.earnings.mapper;

import com.slb.mining_backend.modules.earnings.vo.DailyStatsVo;
import com.slb.mining_backend.modules.earnings.vo.EarningsHistoryItemVo;
import com.slb.mining_backend.modules.earnings.vo.EarningsHistoryHourlyItemVo;
import com.slb.mining_backend.modules.earnings.vo.LeaderboardVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface EarningsMapper {

    /**
     * 分页查询收益历史记录
     */
    List<EarningsHistoryItemVo> findHistoryPaginated(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("earningType") String earningType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 查询收益历史记录总数
     */
    long countHistory(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("earningType") String earningType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 按小时汇总收益历史（分页）：一小时一条，仅用于展示。
     */
    List<EarningsHistoryHourlyItemVo> findHistoryHourlyPaginated(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("earningType") String earningType,
            @Param("groupByEarningType") boolean groupByEarningType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 按小时汇总收益历史总数（小时桶数量）。
     */
    long countHistoryHourly(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("earningType") String earningType,
            @Param("groupByEarningType") boolean groupByEarningType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 查询每日收益统计
     */
    List<DailyStatsVo> findDailyStats(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 分页查询每日收益统计（用户维度）。
     */
    List<DailyStatsVo> findDailyStatsPaginated(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 查询每日收益统计总数（用户维度）。
     */
    long countDailyStats(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 分页查询设备维度的每日收益统计。
     */
    List<DailyStatsVo> findDailyStatsByDevicePaginated(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 查询设备维度的每日收益统计总数。
     */
    long countDailyStatsByDevice(
            @Param("userId") Long userId,
            @Param("deviceId") String deviceId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 插入或更新每日统计
     */
    int upsertDailyStats(
            @Param("userId") Long userId,
            @Param("date") LocalDate date,
            @Param("amountCal") java.math.BigDecimal amountCal,
            @Param("amountCny") java.math.BigDecimal amountCny,
            @Param("type") String type
    );

    /**
     * 累计 CPU 挖矿 CAL 收益（基于 daily_earnings_stats.cpu_cal_earnings 汇总）。
     */
    java.math.BigDecimal sumCpuCalEarnings(@Param("userId") Long userId);

    /**
     * 累计 GPU 挖矿 CAL 收益（基于 daily_earnings_stats.gpu_cal_earnings 汇总）。
     */
    java.math.BigDecimal sumGpuCalEarnings(@Param("userId") Long userId);

    /**
     * 按 earning_type 汇总 earnings_history 中的 CAL 金额。
     */
    java.math.BigDecimal sumAmountCalByType(@Param("userId") Long userId,
                                            @Param("earningType") String earningType);

    /**
     * 按 earning_type 汇总 earnings_history 中的 CNY 金额。
     */
    java.math.BigDecimal sumAmountCnyByType(@Param("userId") Long userId,
                                            @Param("earningType") String earningType);

    /**
     * 按“结算币种”汇总 earnings_history 中的 CNY 金额（通过 platform_commissions.currency 推断；无记录默认 CAL）。
     * 用于统计“实际以 CNY 结算入账”的累计金额（非折合口径）。
     */
    java.math.BigDecimal sumAmountCnyBySettleCurrency(@Param("userId") Long userId,
                                                      @Param("settleCurrency") String settleCurrency);

    /**
     * 按 earning_type 列表 + 结算币种 汇总 earnings_history 中的 CNY 金额。
     */
    java.math.BigDecimal sumAmountCnyByTypesAndSettleCurrency(@Param("userId") Long userId,
                                                              @Param("earningTypes") List<String> earningTypes,
                                                              @Param("settleCurrency") String settleCurrency);

    /**
     * 查询收益排行榜
     */
    List<LeaderboardVo.RankItem> findLeaderboard(
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 查询指定用户在排行榜中的名次
     */
    LeaderboardVo.RankItem findUserRank(
            @Param("userId") Long userId,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /**
     * 查询排行榜总人数
     */
    long countLeaderboardUsers(
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );
}
