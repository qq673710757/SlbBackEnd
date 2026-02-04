package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlementItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface F2PoolSettlementItemMapper {

    int insertBatch(@Param("records") List<F2PoolSettlementItem> records);

    List<F2PoolSettlementItem> findBySettlementId(@Param("settlementId") Long settlementId);

    int updateStatusBySettlementId(@Param("settlementId") Long settlementId,
                                   @Param("status") String status);
}
