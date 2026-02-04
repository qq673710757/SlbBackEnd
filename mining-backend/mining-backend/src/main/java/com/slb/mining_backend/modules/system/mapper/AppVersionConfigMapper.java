package com.slb.mining_backend.modules.system.mapper;

import com.slb.mining_backend.modules.system.entity.AppVersionConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface AppVersionConfigMapper {

    /**
     * 幂等 upsert：同一 platform+channel 只保留一条记录（由 DB 唯一键保证）。
     */
    int upsert(AppVersionConfig config);

    Optional<AppVersionConfig> findByPlatformChannel(@Param("platform") String platform,
                                                     @Param("channel") String channel);

    Optional<AppVersionConfig> findActiveByPlatformChannel(@Param("platform") String platform,
                                                           @Param("channel") String channel);
}


