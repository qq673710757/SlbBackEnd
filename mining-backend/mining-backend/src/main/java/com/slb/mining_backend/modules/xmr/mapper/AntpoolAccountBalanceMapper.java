package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.AntpoolAccountBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface AntpoolAccountBalanceMapper {

    int insert(AntpoolAccountBalance record);

    Optional<AntpoolAccountBalance> selectLatest(@Param("account") String account,
                                                 @Param("coin") String coin);

    Optional<AntpoolAccountBalance> selectLatestBefore(@Param("account") String account,
                                                       @Param("coin") String coin,
                                                       @Param("fetchedAt") LocalDateTime fetchedAt);
}
