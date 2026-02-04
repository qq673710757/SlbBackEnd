package com.slb.mining_backend.modules.invite.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.invite.service.InviteService;
import com.slb.mining_backend.modules.invite.vo.InviteCodeVo;

import com.slb.mining_backend.modules.invite.vo.InviteLeaderboardVo;
import com.slb.mining_backend.modules.invite.vo.InviteRecordsVo;
import com.slb.mining_backend.modules.invite.vo.InviteStatsVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invite")
@Tag(name = "用户端/邀请", description = "用于管理邀请关系、获取邀请码、查看邀请记录和佣金统计的接口")
public class InviteController {

    private final InviteService inviteService;

    @Autowired
    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping("/code")
    @Operation(
            summary = "获取当前用户的邀请码",
            description = """
                    生成或查询当前用户的专属邀请码和邀请链接。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/invite/code" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "inviteCode": "INVITE123",
                        "inviteLink": "https://slb.xyz/register?code=INVITE123"
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<InviteCodeVo> getInviteCode(
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        InviteCodeVo result = inviteService.getInviteCode(userDetails.getUser());
        return ApiResponse.ok(result);
    }

    @GetMapping("/records")
    @Operation(
            summary = "获取邀请记录",
            description = """
                    分页查询当前用户邀请的下级用户列表及其贡献情况。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/invite/records?page=1&size=10" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "records": {
                          "total": 1,
                          "page": 1,
                          "size": 10,
                          "list": [
                            {
                              "inviteeUid": 10002,
                              "inviteeName": "friend001",
                              "deviceCount": 3,
                              "commissionEarned": 12.34
                            }
                          ]
                        },
                        "summary": {
                          "totalInvites": 15,
                          "totalCommission": 123.45,
                          "commissionRate": 0.1
                        }
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<InviteRecordsVo> getInviteRecords(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        InviteRecordsVo result = inviteService.getInviteRecords(userDetails.getUser().getId(), page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/stats")
    @Operation(
            summary = "获取邀请佣金统计",
            description = """
                    查询当前用户通过邀请获得的累计佣金、可用佣金等统计信息。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/invite/stats" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "totalInvites": 20,
                        "activeInvites": 8,
                        "totalCommission": 256.78,
                        "todayCommission": 3.21,
                        "yesterdayCommission": 2.34,
                        "thisMonthCommission": 45.67,
                        "commissionRate": 0.1
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<InviteStatsVo> getInviteStats(
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        InviteStatsVo result = inviteService.getInviteStats(userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @GetMapping("/leaderboard")
    @Operation(
            summary = "邀请收益排行榜（仅邀请收益）",
            description = """
                    按邀请人获得的“邀请佣金（commission_records.commission_amount）”汇总排行。
                    仅统计邀请收益，不计算被邀请人的收益。
                    
                    参数说明：
                    - range: all/today/yesterday/month（默认 all）
                    - limit: TopN（默认 20，最大 100）
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/invite/leaderboard?range=month&limit=20" \\
                      -H "Authorization: Bearer <token>"
                    """
    )
    public ApiResponse<InviteLeaderboardVo> getInviteLeaderboard(
            @Parameter(description = "榜单范围：all/today/yesterday/month", example = "all")
            @RequestParam(defaultValue = "all") String range,
            @Parameter(description = "返回 TopN，默认 20，最大 100", example = "20")
            @RequestParam(defaultValue = "20") int limit
    ) {
        InviteLeaderboardVo result = inviteService.getInviteLeaderboard(range, limit);
        return ApiResponse.ok(result);
    }
}
