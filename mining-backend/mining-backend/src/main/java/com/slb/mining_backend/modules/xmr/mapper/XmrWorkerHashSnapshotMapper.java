package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrWorkerHashSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XmrWorkerHashSnapshotMapper {

    int deleteByUserId(@Param("userId") Long userId);

    int insertBatch(@Param("records") List<XmrWorkerHashSnapshot> records);

    List<XmrWorkerHashSnapshot> selectByUserId(@Param("userId") Long userId,
                                               @Param("since") LocalDateTime since);
}