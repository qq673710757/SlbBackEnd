package com.slb.mining_backend.modules.system.mapper;

import com.slb.mining_backend.modules.system.entity.PlatformCommissionRate;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface PlatformCommissionRateMapper {
    Optional<PlatformCommissionRate> selectCurrent();
    int upsert(PlatformCommissionRate rate);
}
