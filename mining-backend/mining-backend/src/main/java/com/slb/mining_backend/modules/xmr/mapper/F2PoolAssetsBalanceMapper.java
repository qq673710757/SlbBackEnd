package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolAssetsBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface F2PoolAssetsBalanceMapper {

    int insert(F2PoolAssetsBalance record);

    Optional<F2PoolAssetsBalance> selectLatest(@Param("account") String account,
                                               @Param("coin") String coin);

    Optional<F2PoolAssetsBalance> selectLatestBefore(@Param("account") String account,
                                                     @Param("coin") String coin,
                                                     @Param("fetchedAt") LocalDateTime fetchedAt);

    Optional<F2PoolAssetsBalance> selectById(@Param("id") Long id);
}
