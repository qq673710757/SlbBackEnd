package com.slb.mining_backend.modules.xmr.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.xmr.service.XmrPoolStatsService;
import com.slb.mining_backend.modules.xmr.vo.WorkerHashrateVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/xmr")
@Tag(name = "矿池/XMR算力", description = "基于 C3Pool 的实时算力查询接口")
public class XmrStatsController {

    private final XmrPoolStatsService xmrPoolStatsService;

    @Autowired
    public XmrStatsController(XmrPoolStatsService xmrPoolStatsService) {
        this.xmrPoolStatsService = xmrPoolStatsService;
    }

    @GetMapping("/worker/hashrate")
    @Operation(
            summary = "获取实时算力与汇率",
            description = """
                    通过调用 C3Pool API 获取当前登录用户（其 worker_id 对应的子地址）的实时总算力，并返回最新的 XMR/CNY 汇率。
                    """
    )
    public ApiResponse<WorkerHashrateVo> getWorkerHashrate(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WorkerHashrateVo vo = xmrPoolStatsService.getRealtimeHashrate(userDetails.getUser().getId());
        return ApiResponse.ok(vo);
    }
}

