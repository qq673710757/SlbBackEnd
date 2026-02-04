package com.slb.mining_backend.modules.asset.mapper;

import com.slb.mining_backend.modules.asset.entity.AssetLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MyBatis mapper：用于 asset_ledger 表的持久化操作。
 */
@Mapper
public interface AssetLedgerMapper {
    /**
     * 插入一条资产流水记录
     *
     * @param ledger 流水实体
     * @return 影响行数
     */
    int insert(AssetLedger ledger);

    /**
     * 幂等插入：若命中唯一键冲突则忽略（返回 0）。
     * 说明：用于定时任务/重试场景，避免重复入账。
     */
    int insertIgnore(AssetLedger ledger);

    /**
     * 汇总某用户在指定 refType 且时间范围内的 amount_cal（用于统计新手折扣等）。
     */
    BigDecimal sumAmountCalByUserIdAndRefTypeAndRemarkPrefixBetween(@Param("userId") Long userId,
                                                                    @Param("refType") String refType,
                                                                    @Param("remarkPrefix") String remarkPrefix,
                                                                    @Param("start") LocalDateTime start,
                                                                    @Param("end") LocalDateTime end);

    /**
     * 判断某用户是否存在指定 refType 的记录（用于激活标记等）。
     */
    int countByUserIdAndRefTypeAndRemarkPrefix(@Param("userId") Long userId,
                                               @Param("refType") String refType,
                                               @Param("remarkPrefix") String remarkPrefix);
}