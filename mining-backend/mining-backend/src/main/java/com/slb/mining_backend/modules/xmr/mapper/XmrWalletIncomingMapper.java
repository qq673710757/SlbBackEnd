package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrWalletIncoming;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper：处理主钱包入账流水 xmr_wallet_incoming。
 */
@Mapper
public interface XmrWalletIncomingMapper {

    /**
     * 插入一条入账记录。
     */
    int insert(XmrWalletIncoming record);

    /**
     * 插入一条入账记录，若 tx_hash 已存在则忽略（依赖唯一键或数据库自身的 IGNORE 语义）。
     */
    int insertIgnore(XmrWalletIncoming record);

    /**
     * 根据交易哈希查询入账记录。
     */
    Optional<XmrWalletIncoming> selectByTxHash(@Param("txHash") String txHash);

    /**
     * 按用户 ID 查询该用户的所有入账记录。
     */
    List<XmrWalletIncoming> selectByUserId(@Param("userId") Long userId);

    /**
     * 统计某个用户已记录的入账额。
     */
    BigDecimal sumAmountByUserId(@Param("userId") Long userId);

    /**
     * 按子地址与时间区间统计入账额（左闭右开）。
     */
    BigDecimal sumAmountBySubaddressInRange(@Param("subaddress") String subaddress,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    /**
     * 查询尚未结算的钱包入账记录。
     */
    List<XmrWalletIncoming> selectUnsettled(@Param("limit") int limit);

    /**
     * 查询最早的未结算且 ts 非空的入账记录（用于窗口推进，避免异常 NULL ts 卡住整点任务）。
     */
    List<XmrWalletIncoming> selectOldestUnsettledWithTs(@Param("limit") int limit);

    /**
     * 查询指定时间区间内（左闭右开）的未结算入账记录（按 ts 升序）。
     *
     * 用于“按小时批处理结算”：例如在 22:00 结算 [21:00, 22:00) 的入账。
     */
    List<XmrWalletIncoming> selectUnsettledInRange(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("limit") int limit);

    /**
     * 标记入账记录已结算。
     */
    int markSettled(@Param("id") Long id);

    /**
     * 按交易哈希标记已结算（用于清理同 tx_hash 的重复入账行，避免重复结算）。
     */
    int markSettledByTxHash(@Param("txHash") String txHash);

    /**
     * 查询当前记录中的最高区块高度，用于增量同步。
     */
    Optional<Long> selectMaxBlockHeight();
}
