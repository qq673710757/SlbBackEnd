package com.slb.mining_backend.modules.admin.vo;

import com.slb.mining_backend.common.vo.PageVo;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AdminUserDetailVo {
    private Long uid;
    private String username;
    private LocalDateTime registerTime;
    private String registerChannel;
    private BigDecimal cpuKh;
    private BigDecimal cfxMh;
    private BigDecimal rvnMh;
    private BigDecimal calBalance;
    private BigDecimal cnyBalance;
    private BigDecimal commissionTotal;
    private PageVo<InvitedItem> invited;

    @Data
    public static class InvitedItem {
        private Long uid;
        private String username;
        private LocalDateTime registerTime;
        private BigDecimal currentHashrate;
        private BigDecimal commissionEarned;
    }
}
