package com.slb.mining_backend.modules.invite.mapper;

import com.slb.mining_backend.modules.invite.entity.CommissionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface CommissionRecordMapper {

    /**
     * 插入一条新的邀请佣金记录。
     * @param commissionRecord 佣金记录对象
     */
    void insert(CommissionRecord commissionRecord);

    /**
     * 汇总指定用户作为邀请人获得的佣金总额（CAL）。
     */
    BigDecimal sumCommissionByUserId(@Param("userId") Long userId);

    /**
     * 汇总指定用户在时间范围内获得的佣金总额（CAL）。用于月封顶。
     */
    BigDecimal sumCommissionByUserIdAndDateRange(@Param("userId") Long userId,
                                                 @Param("start") String start,
                                                 @Param("end") String end);
}