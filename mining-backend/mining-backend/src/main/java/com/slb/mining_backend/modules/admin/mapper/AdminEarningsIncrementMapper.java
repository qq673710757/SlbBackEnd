package com.slb.mining_backend.modules.admin.mapper;

import com.slb.mining_backend.modules.admin.vo.AdminEarningsIncrementRow;
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
}
