package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrWorkerEarnings;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface XmrWorkerEarningsMapper {

    int upsert(@Param("record") XmrWorkerEarnings record);

    List<XmrWorkerEarnings> selectByUserId(@Param("userId") Long userId);
}