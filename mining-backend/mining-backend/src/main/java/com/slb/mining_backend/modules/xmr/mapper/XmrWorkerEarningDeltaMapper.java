package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.dto.UserAtomicShare;
import com.slb.mining_backend.modules.xmr.entity.XmrWorkerEarningDelta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XmrWorkerEarningDeltaMapper {

    int insert(XmrWorkerEarningDelta delta);

    /**
     * 查询最早的一条未结算记录的 window_end（该字段在库中以 UTC 语义存储的 DATETIME）。
     * 用于“追赶式”日结算：从最早未结算的业务日开始逐日结算，避免漏日。
     */
    LocalDateTime selectMinUnsettledWindowEnd();

    List<UserAtomicShare> sumUnsettledBefore(@Param("cutoff") LocalDateTime cutoff);

    int markSettledBefore(@Param("cutoff") LocalDateTime cutoff);

    int markSettledByUserIdBefore(@Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 按 window_end 的 UTC 窗口汇总未结算 delta（[start, end)）。
     */
    List<UserAtomicShare> sumUnsettledInRange(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * 将窗口内未结算 delta 置 settled=1（[start, end)），用于幂等日结算。
     */
    int markSettledInRange(@Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end);
}
