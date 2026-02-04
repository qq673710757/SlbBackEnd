package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface F2PoolSettlementMapper {

    int insert(F2PoolSettlement settlement);

    Optional<F2PoolSettlement> selectById(@Param("id") Long id);

    Optional<F2PoolSettlement> selectByAccountAndDate(@Param("account") String account,
                                                      @Param("coin") String coin,
                                                      @Param("payoutDate") LocalDate payoutDate);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("updatedTime") java.time.LocalDateTime updatedTime);

    int updateReconcileStatus(@Param("id") Long id,
                              @Param("reconcileStatus") String reconcileStatus,
                              @Param("updatedTime") java.time.LocalDateTime updatedTime);

    long countByStatus(@Param("status") String status);

    List<F2PoolSettlement> findByStatusPaginated(@Param("status") String status,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    long countDistinctPayoutDates(@Param("status") String status,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    List<LocalDate> listDistinctPayoutDates(@Param("status") String status,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    List<com.slb.mining_backend.modules.admin.vo.EarningsGrantRow> listGrantRowsByPayoutDates(
            @Param("status") String status,
            @Param("dates") List<LocalDate> dates
    );
}
