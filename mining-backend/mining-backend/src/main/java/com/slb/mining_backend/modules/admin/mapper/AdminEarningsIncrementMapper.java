package com.slb.mining_backend.modules.admin.mapper;

import com.slb.mining_backend.modules.admin.vo.AdminEarningsIncrementRow;
import com.slb.mining_backend.modules.admin.vo.EarningsGrantDetailVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminEarningsIncrementMapper {
    long countDistinctDays(@Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime);

    List<LocalDate> listDistinctDays(@Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    List<AdminEarningsIncrementRow> listF2PoolDailyByDays(@Param("days") List<LocalDate> days);

    List<AdminEarningsIncrementRow> listAntpoolDailyByDays(@Param("days") List<LocalDate> days);

    /**
     * 统计发放明细记录总数（基于 asset_ledger）
     */
    long countPayoutsByTxHash(@Param("startTime") LocalDateTime startTime,
                              @Param("endTime") LocalDateTime endTime,
                              @Param("coin") String coin,
                              @Param("poolSource") String poolSource,
                              @Param("adminUserId") Long adminUserId);

    /**
     * 查询发放明细列表（基于 asset_ledger，按 txHash 聚合）
     */
    List<EarningsGrantDetailVo> listPayoutsByTxHash(@Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime,
                                                     @Param("coin") String coin,
                                                     @Param("poolSource") String poolSource,
                                                     @Param("adminUserId") Long adminUserId,
                                                     @Param("offset") int offset,
                                                     @Param("size") int size);
}
