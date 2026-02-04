package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.F2PoolAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface F2PoolAlertMapper {

    int insertIgnore(F2PoolAlert alert);

    long countOpenByUserId(@Param("userId") Long userId);

    long countByStatus(@Param("status") String status);

    List<F2PoolAlert> findByStatusPaginated(@Param("status") String status,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    int resolveById(@Param("id") Long id,
                    @Param("resolvedTime") LocalDateTime resolvedTime,
                    @Param("resolvedBy") Long resolvedBy);
}
