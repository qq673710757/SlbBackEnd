package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolRawPayload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface F2PoolRawPayloadMapper {

    int insertIgnore(F2PoolRawPayload payload);

    Optional<F2PoolRawPayload> selectByFingerprint(@Param("account") String account,
                                                   @Param("endpoint") String endpoint,
                                                   @Param("fingerprint") String fingerprint);
}
