package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrDailyValuation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for xmr_daily_valuation snapshots.
 */
@Mapper
public interface XmrDailyValuationMapper {

    int insert(XmrDailyValuation record);
}
