package com.slb.mining_backend.modules.earnings.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.earnings.service.EarningsService;
import com.slb.mining_backend.modules.earnings.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/earnings")
@Tag(name = "用户端/收益", description = "用于查询账户余额、收益历史、每日收益统计、排行榜以及收益预估的接口")
public class EarningsController {

    private final EarningsService earningsService;
    private final MarketDataService marketDataService;

    @Autowired
    public EarningsController(EarningsService earningsService, MarketDataService marketDataService) {
        this.earningsService = earningsService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/balance")
    @Operation(
            summary = "查询账户余额",
            description = """
                    查询当前登录用户的可用余额、累计收益、累计提现等信息。需要在请求头中携带有效的访问令牌。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/balance" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "calBalance": 123.456,
                        "cashBalance": 789.01,
                        "calToCnyRate": 0.85,
                        "totalEarnings": 1024.50,
                        "totalWithdrawn": 512.25
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<BalanceVo> getBalance(
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BalanceVo result = earningsService.getBalance(userDetails.getUser().getId());
        return ApiResponse.ok(result);
    }

    @GetMapping("/history")
    @Operation(
            summary = "收益历史记录",
            description = """
                    分页查询当前用户的历史收益记录，可根据设备及日期范围进行过滤。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/history?page=1&size=10" \
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
                            "id": 10001,
                            "deviceId": "device-123456",
                            "deviceName": "My Mining Rig #1",
                            "amountCal": 1.2345,
                            "amountCny": 10.50,
                            "earningTime": "2025-11-18T10:15:00",
                            "earningType": "block_reward"
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<EarningsHistoryItemVo>> getEarningsHistory(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "设备 ID，可选，不填则查询所有设备", required = false)
            @RequestParam(required = false) String deviceId,
            @Parameter(
                    description = """
                            收益类型筛选（可选，不区分大小写）：
                            - CPU：CPU 挖矿收益
                            - GPU：GPU 挖矿收益
                            - INVITE：邀请返佣/邀请收益（当前口径：不再区分 CPU/GPU；历史数据可能存在 INVITE_CPU/INVITE_GPU）
                            - INVITE_CPU：历史兼容：邀请返佣（来自被邀请者 CPU 收益贡献）
                            - INVITE_GPU：历史兼容：邀请返佣（来自被邀请者 GPU 收益贡献）
                            - INVITED：被邀请者奖励（例如新手折扣/平台让利，按 CAL 等值记账）
                            - COMPENSATION：系统补偿
                            - INCENTIVE：激励类（运营/活动）
                            - SYSTEM_INCENTIVE：系统激励
                            不传或传 ALL 表示不过滤。
                            """,
                    required = false,
                    example = "GPU"
            )
            @RequestParam(required = false) String earningType,
            @Parameter(description = "开始日期 (yyyy-MM-dd)", example = "2024-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)", example = "2024-01-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<EarningsHistoryItemVo> result = earningsService.getEarningsHistory(
                userDetails.getUser().getId(),
                deviceId,
                earningType,
                startDate,
                endDate,
                page,
                size
        );
        return ApiResponse.ok(result);
    }

    @GetMapping("/history-hourly")
    @Operation(
            summary = "收益历史记录（按小时汇总）",
            description = """
                    将收益明细按小时聚合后分页返回，用于列表展示“一小时一条”。
                    说明：
                    - 本接口只做“展示层聚合”，不改变底层收益明细与账本写入逻辑；
                    - 小时桶以 earningTime 表示该小时的起始时间（HH:00:00）；
                    - recordCount 表示该小时内被合并的明细条数；
                    - settleCurrency 若该小时内存在多种结算币种则返回 "MIXED"。
                    
                    earningType 参数说明：
                    - 传入 earningType（如 CPU/GPU/INVITE/...）时，仅返回该类型的小时聚合数据，并在返回中回显 earningType；
                    - 不传入 earningType（或传 ALL）时，返回所有类型的小时聚合数据，并在返回中返回 earningType=ALL。
                    
                    groupBy 参数说明：
                    - 不传入（默认 hour）：按“小时”聚合（1小时=1条，earningType=ALL 或指定值）；
                    - groupBy=earningType：按“小时 + earningType”聚合（同一小时会返回多条，分别对应 CPU/GPU/INVITE/...），此时返回中的 earningType 为实际类型值。
                    
                    settleCurrency 说明：
                    - settleCurrency 来自明细记录的“结算币种”推断（通过关联 platform_commissions.currency；若无记录则默认 CAL）；
                    - 若同一聚合桶内出现多种币种，则返回 MIXED（例如同一小时内同时存在 CNY 与 CAL 的入账记录）。
                    
                    分页 total 说明：
                    - groupBy=hour：total 代表“小时桶”的数量；
                    - groupBy=earningType：total 代表“小时桶 × 类型”的数量（例如同一小时 CPU/GPU 各一条则计为 2）。
                    
                    明细仍可通过 /history 接口查询（用于审计/溯源）。
                    
                    示例请求 1（默认：按小时聚合，全部类型）(cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/history-hourly?page=1&size=10&startDate=2025-12-01&endDate=2025-12-31" \\
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例请求 2（按小时+类型聚合，一次拿到各类型）(cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/history-hourly?page=1&size=10&groupBy=earningType&startDate=2025-12-01&endDate=2025-12-31" \\
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "total": 72,
                        "page": 1,
                        "size": 10,
                        "list": [
                          {
                            "earningTime": "2025-12-12T22:00:00",
                            "earningType": "CPU",
                            "amountCal": 0.01234567,
                            "amountCny": 0.03,
                            "recordCount": 4,
                            "settleCurrency": "CNY"
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<EarningsHistoryHourlyItemVo>> getEarningsHistoryHourly(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "设备 ID，可选。不填则汇总所有设备。", required = false, example = "device-123456")
            @RequestParam(required = false) String deviceId,
            @Parameter(
                    description = """
                            聚合维度（可选）：
                            - hour：按小时聚合（合并收益类型）
                            - earningType：按小时 + 收益类型聚合（默认）
                            """,
                    required = false,
                    example = "earningType"
            )
            @RequestParam(required = false) String groupBy,
            @Parameter(
                    description = """
                            收益类型筛选（可选，不区分大小写）：
                            - CPU：CPU 挖矿收益
                            - GPU：GPU 挖矿收益
                            - INVITE：邀请返佣/邀请收益（当前口径：不再区分 CPU/GPU；历史数据可能存在 INVITE_CPU/INVITE_GPU）
                            - INVITE_CPU：历史兼容：邀请返佣（来自被邀请者 CPU 收益贡献）
                            - INVITE_GPU：历史兼容：邀请返佣（来自被邀请者 GPU 收益贡献）
                            - INVITED：被邀请者奖励（例如新手折扣/平台让利，按 CAL 等值记账）
                            - COMPENSATION：系统补偿
                            - INCENTIVE：激励类（运营/活动）
                            - SYSTEM_INCENTIVE：系统激励
                            不传或传 ALL 表示不过滤（返回 earningType=ALL）。
                            """,
                    required = false,
                    example = "INVITE"
            )
            @RequestParam(required = false) String earningType,
            @Parameter(description = "开始日期 (yyyy-MM-dd)，按 earning_time 过滤", example = "2025-12-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)，按 earning_time 过滤", example = "2025-12-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<EarningsHistoryHourlyItemVo> result = earningsService.getEarningsHistoryHourly(
                userDetails.getUser().getId(),
                deviceId,
                groupBy,
                earningType,
                startDate,
                endDate,
                page,
                size
        );
        return ApiResponse.ok(result);
    }

    @GetMapping("/daily")
    @Operation(
            summary = "每日收益统计（用户维度，分页）",
            description = """
                    返回当前用户“全部设备”的每日收益统计（CPU/GPU 同时返回），支持分页。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/daily?startDate=2025-11-01&endDate=2025-11-18&page=1&size=20" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "total": 18,
                        "page": 1,
                        "size": 20,
                        "list": [
                          {
                            "date": "2025-11-18",
                            "calAmount": 12.345,
                            "cnyAmount": 100.56,
                            "cpuEarnings": 7.89,
                            "gpuEarnings": 4.45
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<DailyStatsVo>> getDailyStats(
            @Parameter(description = "开始日期 (yyyy-MM-dd)，默认 30 天前", example = "2024-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)，默认今天", example = "2024-01-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<DailyStatsVo> result = earningsService.getDailyStatsPage(
                userDetails.getUser().getId(), startDate, endDate, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/dailyAmount")
    @Operation(
            summary = "每日收益统计（用户维度，分页）",
            description = """
                    返回当前用户“全部设备”的每日收益统计（CPU/GPU 同时返回），支持分页。
                    """
    )
    public ApiResponse<PageVo<DailyStatsVo>> getDailyStatsPage(
            @Parameter(description = "开始日期 (yyyy-MM-dd)，默认 30 天前", example = "2024-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)，默认今天", example = "2024-01-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<DailyStatsVo> result = earningsService.getDailyStatsPage(
                userDetails.getUser().getId(), startDate, endDate, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/dailyByDevice")
    @Operation(
            summary = "每日收益统计（设备维度，分页）",
            description = """
                    返回当前用户某个设备的每日收益统计（CPU/GPU 同时返回），支持分页。
                    """
    )
    public ApiResponse<PageVo<DailyStatsVo>> getDailyStatsByDevice(
            @Parameter(description = "设备 ID", required = true, example = "device-123456")
            @RequestParam String deviceId,
            @Parameter(description = "开始日期 (yyyy-MM-dd)，默认 30 天前", example = "2024-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)，默认今天", example = "2024-01-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PageVo<DailyStatsVo> result = earningsService.getDailyStatsByDevice(
                userDetails.getUser().getId(), deviceId, startDate, endDate, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/leaderboard")
    @Operation(
            summary = "获取收益排行榜",
            description = """
                    按周或月统计收益排行榜，同时返回当前用户在排行榜中的位置。
                    
                    说明：
                    - 本接口在返回中同时提供 CAL 与 CNY 口径的汇总金额（calAmount / cnyAmount），以避免前端误解；
                    - 返回会包含本次统计周期的 startTime/endTime/type，避免口径歧义；
                    - 为保证排行榜在缓存周期内口径一致，endTime 会按 30 分钟窗口对齐（例如 10:00/10:30）；
                    - 同额时按 user_id 作为稳定排序的 tie-break，避免分页/排名列表抖动；
                    - 当用户在统计周期内无收益时，myRank 可能为 null。
                    
                    入参限制：page >= 1，size 取值范围 1-100，非法参数会返回业务异常（BizException）。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/leaderboard?type=month&page=1&size=10" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "type": "month",
                        "startTime": "2025-12-01T00:00:00",
                        "endTime": "2025-12-24T10:30:00",
                        "leaderboard": {
                          "total": 100,
                          "page": 1,
                          "size": 10,
                          "list": [
                            {
                              "rank": 1,
                              "userName": "topUser",
                              "calAmount": 123.45,
                              "cnyAmount": 100.50,
                              "deviceCount": 5
                            }
                          ]
                        },
                        "myRank": {
                          "rank": 8,
                          "userName": "hyperion",
                          "calAmount": 56.78,
                          "cnyAmount": 45.67,
                          "deviceCount": 3
                        }
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<LeaderboardVo> getLeaderboard(
            @Parameter(description = "排行类型，week=周排行，month=月排行", example = "month")
            @RequestParam(defaultValue = "month") String type,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量（1-100）", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        LeaderboardVo result = earningsService.getLeaderboard(userDetails.getUser().getId(), type, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/estimate")
    @Operation(
            summary = "收益预估",
            description = """
                    根据 CPU / GPU 算力预估未来一段时间的收益情况。此接口无需登录即可使用。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/estimate?cpuHashrate=5000&gpuHashrate=20000"
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "hourly": {
                          "calAmount": 0.5,
                          "cnyAmount": 4.00
                        },
                        "daily": {
                          "calAmount": 12.0,
                          "cnyAmount": 96.0
                        },
                        "monthly": {
                          "calAmount": 360.0,
                          "cnyAmount": 2880.0
                        },
                        "cpuContribution": 0.4,
                        "gpuContribution": 0.6,
                        "currentXmrPrice": 1200.5,
                        "calToCnyRate": 0.85
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<EstimateVo> getEstimate(
            @Parameter(description = "CPU 算力，单位 H/s，可选", example = "5000000")
            @RequestParam(required = false) Double cpuHashrate,
            @Parameter(description = "GPU 算力，单位 MH/s，可选", example = "20")
            @RequestParam(required = false) Double gpuHashrate) {
        EstimateVo result = earningsService.getEstimate(cpuHashrate, gpuHashrate);
        return ApiResponse.ok(result);
    }

    @GetMapping("/unit-income")
    @Operation(
            summary = "获取单位收益（人民币）",
            description = """
                    返回“单位算力每日收益（CNY）”，供客户端/网页自行按算力线性放大计算：
                    - CPU：每 0.001 MH/s · 天 的收益（CNY）（等价 1000 H/s）
                    - GPU：每 1 MH/s · 天 的收益（CNY）

                    计算口径与原预估一致：优先使用矿池 activePortProfit（若可用），否则回退到块奖励估算。

                    前端示例：设备 CPU=10 MH/s，则日收益≈(10/0.001)*cpuDailyIncomeCnyPer1000H。
                    """
    )
    public ApiResponse<UnitIncomeVo> getUnitIncome() {
        return ApiResponse.ok(earningsService.getUnitIncomeCny());
    }

    @GetMapping("/unit-income/gpu")
    @Operation(
            summary = "获取 GPU 单位收益（人民币）",
            description = """
                    返回“GPU 单位算力每日收益（CNY）”，供客户端/网页自行按算力线性放大计算：
                    - GPU：每 1 MH/s · 天 的收益（CNY）

                    计算口径与 /unit-income 保持一致，仅返回 GPU 部分字段。
                    """
    )
    public ApiResponse<GpuUnitIncomeVo> getGpuUnitIncome() {
        return ApiResponse.ok(earningsService.getGpuUnitIncomeCny());
    }

    @GetMapping("/gpu-income")
    @Operation(
            summary = "GPU 日收益估算（CFX / XMR）",
            description = """
                    输入 GPU 算力（MH/s），返回“日收益估算”：
                    - CFX：估算日收益（CFX）
                    - XMR：估算日收益（XMR）

                    说明：优先使用币种“每 MH 日产币”口径；若不可用则回退到 activePortProfit（XMR 口径）或块奖励估算。
                    """
    )
    public ApiResponse<GpuDailyIncomeVo> getGpuDailyIncome(
            @Parameter(description = "GPU 算力，单位 MH/s", example = "100.0")
            @RequestParam(required = false) Double gpuHashrateMh) {
        return ApiResponse.ok(earningsService.getGpuDailyIncome(gpuHashrateMh));
    }

    @GetMapping("/gpu-income-rvn")
    @Operation(
            summary = "GPU 日收益估算（RVN / XMR）",
            description = """
                    输入 GPU 算力（MH/s），返回“日收益估算”：
                    - RVN：估算日收益（RVN）
                    - XMR：估算日收益（XMR）

                    说明：优先使用币种“每 MH 日产币”口径；若不可用则回退到 activePortProfit（XMR 口径）或块奖励估算。
                    """
    )
    public ApiResponse<GpuRvnDailyIncomeVo> getGpuRvnDailyIncome(
            @Parameter(description = "GPU 算力，单位 MH/s", example = "100.0")
            @RequestParam(required = false) Double gpuHashrateMh) {
        return ApiResponse.ok(earningsService.getGpuRvnDailyIncome(gpuHashrateMh));
    }

    @GetMapping("/market-data/pool-stats")
    @Operation(
            summary = "获取矿池统计快照（验收/排查用）",
            description = """
                    返回当前用于收益预估的关键市场数据快照：
                    - 外部矿池总算力（若已成功刷新）
                    - 当前生效的矿池总算力（外部优先，失败退化为平台在线设备算力之和）
                    - activePortProfit（若外部返回）
                    - CAL/XMR ratio 与 XMR/CNY、CAL/CNY 汇率

                    说明：该接口需要登录（用于内部验收与问题排查）。
                    """
    )
    public ApiResponse<PoolStatsVo> getPoolStatsSnapshot() {
        return ApiResponse.ok(buildPoolStatsVo());
    }

    @GetMapping("/market-data/cfx-diagnostics")
    @Operation(
            summary = "获取 CFX 口径诊断快照（验收/排查用）",
            description = """
                    返回 CFX 口径的关键数据，用于核对 Octopus 预估收益计算：
                    - 网络算力（MH/s）
                    - 出块时间（秒）
                    - 每 MH/s 日产币（CFX/MH/day）
                    - CFX 对 CNY 汇率

                    说明：该接口需要登录（用于内部验收与问题排查）。
                    """
    )
    public ApiResponse<CfxDiagnosticsVo> getCfxDiagnosticsSnapshot() {
        CfxDiagnosticsVo vo = new CfxDiagnosticsVo();
        vo.setNetworkHashrateMh(marketDataService.getCfxNetworkHashrateMh());
        vo.setBlockTimeSeconds(marketDataService.getCfxBlockTimeSeconds());
        vo.setDailyCoinPerMh(marketDataService.getCfxDailyCoinPerMh());
        vo.setCfxToCny(marketDataService.getCfxToCnyRate());
        vo.setLastRefreshedAt(marketDataService.getCfxStatsLastRefreshAt());
        vo.setLastError(marketDataService.getCfxStatsLastError());
        return ApiResponse.ok(vo);
    }

    @org.springframework.web.bind.annotation.PostMapping("/market-data/pool-stats/refresh")
    @Operation(
            summary = "手动刷新矿池统计（验收/排查用）",
            description = """
                    立即触发一次外部矿池 /pool/stats 拉取并更新缓存，然后返回最新快照。
                    说明：该接口需要登录（用于内部验收与问题排查）。
                    """
    )
    public ApiResponse<PoolStatsVo> refreshPoolStatsNow() {
        marketDataService.refreshExternalPoolHashrate();
        return ApiResponse.ok(buildPoolStatsVo());
    }

    private PoolStatsVo buildPoolStatsVo() {
        PoolStatsVo vo = new PoolStatsVo();
        vo.setExternalPoolHashrateHps(marketDataService.getExternalPoolHashrateHps());
        vo.setPoolTotalHashrateHps(marketDataService.getPoolTotalHashrate());
        vo.setActivePortProfitXmrPerHashDay(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay());
        vo.setCfxDailyCoinPerMh(marketDataService.getCfxDailyCoinPerMh());
        vo.setRvnDailyCoinPerMh(marketDataService.getRvnDailyCoinPerMh());
        vo.setCalXmrRatio(marketDataService.getCalXmrRatio());
        vo.setXmrToCnyRate(marketDataService.getXmrToCnyRate());
        vo.setCalToCnyRate(marketDataService.getCalToCnyRate());
        vo.setPoolTotalHashrateSource(
                marketDataService.getExternalPoolHashrateHps().compareTo(java.math.BigDecimal.ZERO) > 0
                        ? "external"
                        : "platform_fallback"
        );
        return vo;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "累计收益总览",
            description = """
                    按来源维度（GPU 挖矿 / CPU 挖矿 / 邀请者返佣 / 被邀请者奖励 / 系统补偿 / 系统激励）
                    以及 CAL / CNY 两个维度返回当前登录用户的累计收益汇总数据。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/earnings/summary" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "gpuCalTotal": 100.00000000,
                        "gpuCnyTotal": 800.00,
                        "gpuCnySettledTotal": 0.00,
                        "cpuCalTotal": 20.00000000,
                        "cpuCnyTotal": 160.00,
                        "cpuCnySettledTotal": 0.00,
                        "inviteCalTotal": 10.00000000,
                        "inviteCnyTotal": 80.00,
                        "inviteCnySettledTotal": 0.00,
                        "invitedCalTotal": 1.00000000,
                        "invitedCnyTotal": 8.00,
                        "invitedCnySettledTotal": 0.00,
                        "compensationCalTotal": 5.00000000,
                        "compensationCnyTotal": 40.00,
                        "compensationCnySettledTotal": 0.00,
                        "incentiveCalTotal": 3.00000000,
                        "incentiveCnyTotal": 24.00,
                        "incentiveCnySettledTotal": 0.00,
                        "totalCal": 138.00000000,
                        "totalCny": 1104.00,
                        "totalCnySettled": 0.00,
                        "totalCalCredited": 138.00000000
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<EarningsSummaryVo> getEarningsSummary(
            @Parameter(description = "当前登录用户信息，由系统自动注入")
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EarningsSummaryVo result = earningsService.getEarningsSummary(
                userDetails.getUser().getId(),
                userDetails.getUser().getSettlementCurrency()
        );
        return ApiResponse.ok(result);
    }
}
