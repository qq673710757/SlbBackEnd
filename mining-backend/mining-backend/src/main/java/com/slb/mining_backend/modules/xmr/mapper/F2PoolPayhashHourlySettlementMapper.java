package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolPayhashHourlySettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface F2PoolPayhashHourlySettlementMapper {

    int insertIgnore(F2PoolPayhashHourlySettlement settlement);

    Optional<F2PoolPayhashHourlySettlement> selectByAccountAndWindowStart(@Param("account") String account,
                                                                          @Param("coin") String coin,
                                                                          @Param("windowStart") LocalDateTime windowStart);

    Optional<F2PoolPayhashHourlySettlement> selectByAccountAndWindowEnd(@Param("account") String account,
                                                                        @Param("coin") String coin,
                                                                        @Param("windowEnd") LocalDateTime windowEnd);

    BigDecimal selectAverageTotalCoin(@Param("account") String account,
                                      @Param("coin") String coin,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);

    BigDecimal sumTotalCoinByWindowStartRange(@Param("account") String account,
                                              @Param("coin") String coin,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    int countByAccountAndWindowStartRange(@Param("account") String account,
                                          @Param("coin") String coin,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    long countByFilters(@Param("account") String account,
                        @Param("coin") String coin,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

    List<F2PoolPayhashHourlySettlement> findByFiltersPaginated(@Param("account") String account,
                                                               @Param("coin") String coin,
                                                               @Param("startTime") LocalDateTime startTime,
                                                               @Param("endTime") LocalDateTime endTime,
                                                               @Param("offset") int offset,
                                                               @Param("size") int size);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("updatedTime") LocalDateTime updatedTime);

    int updateAllocationInfo(@Param("id") Long id,
                             @Param("allocationSource") String allocationSource,
                             @Param("fallbackReason") String fallbackReason,
                             @Param("updatedTime") LocalDateTime updatedTime);
}
