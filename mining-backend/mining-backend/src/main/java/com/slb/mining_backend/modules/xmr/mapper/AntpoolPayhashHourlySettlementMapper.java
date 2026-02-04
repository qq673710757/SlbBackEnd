package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.AntpoolPayhashHourlySettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AntpoolPayhashHourlySettlementMapper {

    int insertIgnore(AntpoolPayhashHourlySettlement settlement);

    Optional<AntpoolPayhashHourlySettlement> selectByAccountAndWindowStart(@Param("account") String account,
                                                                           @Param("coin") String coin,
                                                                           @Param("windowStart") LocalDateTime windowStart);

    BigDecimal selectAverageTotalCoin(@Param("account") String account,
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

    List<AntpoolPayhashHourlySettlement> findByFiltersPaginated(@Param("account") String account,
                                                                @Param("coin") String coin,
                                                                @Param("startTime") LocalDateTime startTime,
                                                                @Param("endTime") LocalDateTime endTime,
                                                                @Param("offset") int offset,
                                                                @Param("size") int size);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("updatedTime") LocalDateTime updatedTime);
}
