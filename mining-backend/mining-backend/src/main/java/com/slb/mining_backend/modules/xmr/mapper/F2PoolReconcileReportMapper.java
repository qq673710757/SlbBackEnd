package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolReconcileReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface F2PoolReconcileReportMapper {

    int insert(F2PoolReconcileReport report);

    List<F2PoolReconcileReport> findLatest(@Param("account") String account,
                                           @Param("coin") String coin,
                                           @Param("limit") int limit);
}
