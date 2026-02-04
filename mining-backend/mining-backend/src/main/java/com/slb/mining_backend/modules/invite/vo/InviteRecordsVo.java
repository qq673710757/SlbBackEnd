package com.slb.mining_backend.modules.invite.vo;

import com.slb.mining_backend.common.vo.PageVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 邀请记录视图对象 (包含列表和摘要)
 */
@Data
@Schema(description = "邀请记录视图对象，包含记录列表与汇总信息 / Invite records view object including list and summary")
public class InviteRecordsVo {

    @Schema(description = "分页的邀请记录列表。/ Paged invite record list.")
    private PageVo<RecordItem> records;

    @Schema(description = "邀请记录摘要信息。/ Summary for invite records.")
    private Summary summary;

    /**
     * 列表中的单条记录
     */
    @Data
    @Schema(description = "单条邀请记录 / Single invite record item")
    public static class RecordItem{

        @Schema(description = "被邀请用户 ID。为避免前端精度丢失，建议按字符串处理。/ Invitee user ID; JS clients should handle as string.", example = "10002")
        private Long inviteeUid;

        @Schema(description = "被邀请用户名称或标识。/ Invitee user name or identifier.", example = "friend001")
        private String inviteeName;

        @Schema(description = "邀请关系创建时间。/ Time when invite relationship was created.", example = "2025-11-18T09:00:00")
        private LocalDateTime createTime;

        @Schema(description = "该被邀请用户绑定的设备数量。/ Number of devices bound by this invitee.", example = "3")
        private Integer deviceCount;

        @Schema(description = "平台已向当前用户发放的佣金总额（来自该被邀请人）。/ Total commission already granted from this invitee.", example = "12.34")
        private BigDecimal commissionEarned;
    }

    /**
     * 摘要信息
     */
    @Data
    @NoArgsConstructor
    @Schema(description = "邀请数据摘要 / Aggregated invite statistics")
    public static class Summary{

        @Schema(description = "累计邀请人数。/ Total number of invited users.", example = "15")
        private Long totalInvites;

        @Schema(description = "累计佣金总额。/ Total commission amount.", example = "123.45")
        private BigDecimal totalCommission;

        @Schema(description = "佣金比例，如 0.1 表示 10%。/ Commission rate, e.g. 0.1 means 10%.", example = "0.1")
        private BigDecimal commissionRate;

        public Summary(Long totalInvites, BigDecimal totalCommission, BigDecimal commissionRate){
            this.totalInvites = totalInvites;
            this.totalCommission = totalCommission;
            this.commissionRate = commissionRate;
        }

    }
}
