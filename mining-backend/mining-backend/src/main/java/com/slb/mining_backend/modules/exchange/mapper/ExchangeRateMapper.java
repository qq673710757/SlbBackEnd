package com.slb.mining_backend.modules.exchange.mapper;

import com.slb.mining_backend.modules.exchange.entity.ExchangeRate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * MyBatis mapper：操作 exchange_rates 表。
 */
@Mapper
public interface ExchangeRateMapper {

    /**
     * 插入一条新的汇率快照
     */
    int insert(ExchangeRate rate);

    /**
     * 根据 symbol 查询最近一次的汇率记录
     */
    Optional<ExchangeRate> selectLatestBySymbol(@Param("symbol") String symbol);

    /**
     * 查询指定时间点之前（含）最近的一条记录。
     *
     * @param symbol 交易对，如 RVN/XMR
     * @param before 时间上限
     */
    Optional<ExchangeRate> selectLatestBeforeBySymbol(@Param("symbol") String symbol,
                                                      @Param("before") java.time.LocalDateTime before);
}