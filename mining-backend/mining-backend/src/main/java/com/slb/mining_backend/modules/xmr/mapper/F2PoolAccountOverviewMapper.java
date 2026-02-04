package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolAccountOverview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface F2PoolAccountOverviewMapper {

    int insert(F2PoolAccountOverview overview);

    Optional<F2PoolAccountOverview> selectLatest(@Param("account") String account,
                                                 @Param("coin") String coin);

    Optional<F2PoolAccountOverview> selectLatestBefore(@Param("account") String account,
                                                       @Param("coin") String coin,
                                                       @Param("fetchedAt") java.time.LocalDateTime fetchedAt);
}
