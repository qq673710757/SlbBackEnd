package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolWorkerSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface F2PoolWorkerSnapshotMapper {

    int insertBatch(@Param("records") List<F2PoolWorkerSnapshot> records);

    Optional<LocalDateTime> selectLatestBucketTime(@Param("account") String account,
                                                   @Param("coin") String coin);

    BigDecimal sumHashrateByBucket(@Param("account") String account,
                                   @Param("coin") String coin,
                                   @Param("bucketTime") LocalDateTime bucketTime);

    List<F2PoolWorkerSnapshot> selectLatestUnclaimed(@Param("account") String account,
                                                     @Param("coin") String coin,
                                                     @Param("bucketTime") LocalDateTime bucketTime,
                                                     @Param("limit") int limit);
}
