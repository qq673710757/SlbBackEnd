package com.slb.mining_backend.modules.invite.mapper;

import com.slb.mining_backend.modules.invite.entity.PlatformCommission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface PlatformCommissionMapper {
    void insert(PlatformCommission platformCommission);

    BigDecimal sumCommissionByDateRange(@Param("startTime") String startTime, @Param("endTime") String endTime);

    /**
     * 按币种汇总平台佣金收入（避免把 CAL/CNY 混在一起相加导致口径错误）。
     */
    BigDecimal sumCommissionByDateRangeAndCurrency(@Param("startTime") String startTime,
                                                   @Param("endTime") String endTime,
                                                   @Param("currency") String currency);
}