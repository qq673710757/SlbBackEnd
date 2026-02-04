package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrPoolStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper 接口：用于操作 xmr_pool_stats 表。
 */
@Mapper
public interface XmrPoolStatsMapper {

    /**
     * 根据用户 ID 查询挖矿统计记录
     */
    Optional<XmrPoolStats> selectByUserId(@Param("userId") Long userId);

    /**
     * 根据 workerId 查询挖矿统计记录
     */
    Optional<XmrPoolStats> selectByWorkerId(@Param("workerId") String workerId);

    /**
     * 查询所有记录
     */
    List<XmrPoolStats> selectAll();

    /**
     * 插入一条新的挖矿统计记录
     */
    int insert(XmrPoolStats stats);

    /**
     * 更新已有的挖矿统计记录
     */
    int update(XmrPoolStats stats);
}