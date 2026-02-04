package com.slb.mining_backend.modules.withdraw.controller;


import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.withdraw.dto.WithdrawApplyDto;
import com.slb.mining_backend.modules.withdraw.service.WithdrawalService;
import com.slb.mining_backend.modules.withdraw.vo.WithdrawVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("api/v1/withdraw")
@Tag(name = "用户端/提现", description = "用于管理用户提现申请、查询提现记录的接口（不支持取消提现；同一用户同一时间仅允许一笔待审核提现）")
@Slf4j
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping("/apply")
    @Operation(
            summary = "申请提现",
            description = """
                    当前登录用户发起提现申请，系统会校验余额、最小提现金额等规则。
                    
                    - 当 accountType = "alipay" 时：
                      - amountUnit 不传时（兼容旧调用）默认按 CNY 解释 amount；
                      - amountUnit = "CNY"：amount 表示 CNY 总扣金额（含手续费计费基数）；
                        - currency = "CNY"（默认）：从用户现金余额（cash_balance）中扣减并冻结；
                        - currency = "CAL"（兼容旧模式）：amount 仍按 CNY 输入，但从 CAL 余额折算扣减；
                      - amountUnit = "CAL"：amount 表示 CAL 扣款数量；系统按 1CAL=calXmrRatio*XMR 与 XMR/CNY 汇率折算等价 CNY（cnyGross）作为最小金额校验与手续费计费基数，并从用户 cal_balance 扣减该 CAL 数量；落库仍以 CNY 金额存储以兼容管理端审核逻辑。
                    
                    最小提现金额规则：以“等价 CNY 总扣金额（cnyGross）”与 app.withdraw.min-amount（默认 3 CNY）进行比较。
                    注意：后端严禁根据 amount 数值大小猜测单位，必须由 amountUnit（或默认）决定。
                    
                    示例请求 (cURL):
                    # 支付宝 CNY 提现示例（兼容旧调用：不传 amountUnit，默认 CNY）
                    curl -X POST "http://localhost:8080/api/v1/withdraw/apply" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "amount": 100.50,
                        "accountType": "alipay",
                      "currency": "CNY",
                      "account": "13800000000"
                    }'
                    
                    # 支付宝 CAL 提现示例（新契约：amountUnit=CAL，amount 表示 CAL 数量；按汇率折算等价 CNY 校验与计费）
                    curl -X POST "http://localhost:8080/api/v1/withdraw/apply" \
                    -H "Authorization: Bearer <token>" \
                    -H "Content-Type: application/json" \
                    -d '{
                      "amount": 2.50,
                      "amountUnit": "CAL",
                      "accountType": "alipay",
                      "account": "13800000000"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "withdrawId": 10001,
                        "amount": 100.50,
                        "accountType": "alipay",
                        "account": "13800000000",
                        "currency": "CNY",
                        "status": 0
                        // ... 其他字段略
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<WithdrawVo> applyForWithdrawal(
            @Parameter(description = "提现申请请求体，包含提现金额、目标地址等信息", required = true)
            @Valid @RequestBody WithdrawApplyDto dto,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        log.info("Withdraw apply Authorization header present={}, length={}",
                authHeader != null,
                authHeader != null ? authHeader.length() : 0);
        WithdrawVo result = withdrawalService.applyForWithdrawal(userDetails.getUser().getId(), dto);
        return ApiResponse.ok(result);
    }

    @GetMapping("/history")
    @Operation(
            summary = "获取提现记录",
            description = """
                    分页查询当前用户的提现记录，可按状态过滤。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/withdraw/history?page=1&size=10" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "total": 1,
                        "page": 1,
                        "size": 10,
                        "list": [
                          {
                            "withdrawId": 10001,
                            "amount": 100.50,
                            "accountType": "alipay",
                            "status": 0,
                            "currency": "CNY"
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<WithdrawVo>> getWithdrawalHistory(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "提现状态，可选，示例：0=待审核，1=通过，2=拒绝等", required = false)
            @RequestParam(required = false) Integer status,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<WithdrawVo> result = withdrawalService.getWithdrawalHistory(userDetails.getUser().getId(), status, page, size);
        return ApiResponse.ok(result);
    }

}
