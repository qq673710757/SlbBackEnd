package com.slb.mining_backend.modules.withdraw.mapper;

import com.slb.mining_backend.modules.withdraw.entity.Withdrawal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WithdrawalMapper {
    void insert(Withdrawal withdrawal);
    Optional<Withdrawal> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
    int update(Withdrawal withdrawal);
    long countByUserIdAndStatus(@Param("userId") Long userId, @Param("status") Integer status);

    /**
     * 统计用户“未完成”的提现申请数量（用于互斥：同一用户同一时间只能有一笔提现在处理）。
     * 当前状态定义：0=待审核（未完成）；1=通过；2=拒绝。
     */
    long countActiveByUserId(@Param("userId") Long userId);
    List<Withdrawal> findByUserIdAndStatusPaginated(
            @Param("userId") Long userId,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("size") int size
    );
    long countTodayWithdrawalsByUserId(@Param("userId") Long userId);

    Optional<Withdrawal> findById(Long withdrawalId);

    List<Withdrawal> findByStatusPaginated(@Param("status") int status, @Param("offset") int offset, @Param("size") int size);
    long countByStatus(@Param("status") int status);

    long countByFilters(@Param("status") Integer status,
                        @Param("userId") Long userId,
                        @Param("startTime") String startTime,
                        @Param("endTime") String endTime);

    List<Withdrawal> findByFiltersPaginated(@Param("status") Integer status,
                                            @Param("userId") Long userId,
                                            @Param("startTime") String startTime,
                                            @Param("endTime") String endTime,
                                            @Param("offset") int offset,
                                            @Param("size") int size);
}
