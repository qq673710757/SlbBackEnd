package com.slb.mining_backend.modules.earnings.mapper;

import com.slb.mining_backend.modules.earnings.entity.EarningsHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EarningsHistoryMapper {

    /**
     * 插入一条新的收益历史记录。
     * 关键：此方法会返回自增ID到传入的 earningsHistory 对象的 id 属性中。
     * @param earningsHistory 收益历史对象
     */
    void insert(EarningsHistory earningsHistory);
}