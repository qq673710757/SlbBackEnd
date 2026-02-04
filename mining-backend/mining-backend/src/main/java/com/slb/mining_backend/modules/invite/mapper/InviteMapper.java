package com.slb.mining_backend.modules.invite.mapper;

import com.slb.mining_backend.modules.invite.vo.InviteLeaderboardVo;
import com.slb.mining_backend.modules.invite.vo.InviteRecordsVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface InviteMapper {

    /**
     * 获取用户的总邀请人数
     */
    long countInviteesByUserId(@Param("userId") Long userId);

    /**
     * 获取用户的活跃邀请人数 (定义为名下有在线设备的用户)
     */
    long countActiveInviteesByUserId(@Param("userId") Long userId);

    /**
     * 获取用户总佣金
     */
    BigDecimal sumTotalCommissionByUserId(@Param("userId") Long userId);

    /**
     * 获取用户在指定时间范围内的佣金总和
     */
    BigDecimal sumCommissionByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /**
     * 分页查询邀请记录
     */
    List<InviteRecordsVo.RecordItem> findInviteRecordsPaginated(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 佣金收益
     */
    BigDecimal sumTotalInvitationCommissionByDateRange(@Param("startTime") String startTime, @Param("endTime") String endTime);

    /**
     * 邀请收益排行榜（仅统计邀请人获得的佣金：commission_records.user_id 的 commission_amount 汇总）
     */
    List<InviteLeaderboardVo.Item> findInviteCommissionLeaderboard(
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("limit") int limit
    );

}
