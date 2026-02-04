package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolPayoutDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Optional;

@Mapper
public interface F2PoolPayoutDailyMapper {

    int insertIgnore(F2PoolPayoutDaily payout);

    Optional<F2PoolPayoutDaily> selectEarliestUnsettled(@Param("account") String account,
                                                        @Param("coin") String coin);

    Optional<F2PoolPayoutDaily> selectByAccountAndDate(@Param("account") String account,
                                                       @Param("coin") String coin,
                                                       @Param("payoutDate") LocalDate payoutDate);

    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
