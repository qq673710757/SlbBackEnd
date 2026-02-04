package com.slb.mining_backend.modules.exchange.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.exchange.vo.ExchangeRateVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 汇率信息接口
 * 用于展示各币种汇率，不需要认证
 */
@RestController
@RequestMapping("/api/v1/exchange-rate")
@Tag(name = "公共/汇率", description = "提供 XMR、USDT、CAL 等币种汇率查询接口，均为公开接口无需登录")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final MarketDataService marketDataService;

    public ExchangeRateController(ExchangeRateService exchangeRateService, MarketDataService marketDataService) {
        this.exchangeRateService = exchangeRateService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/all")
    @Operation(
            summary = "获取全部汇率信息",
            description = """
                    一次性返回 XMR/CNY、XMR/USDT、USDT/CNY、CAL/CNY 等多个币种汇率及数据来源。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/exchange-rate/all"
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "xmrToCny": 1200.50,
                        "xmrToUsdt": 150.25,
                        "usdtToCny": 8.0,
                        "calToCny": 0.85,
                        "source": "CoinGecko",
                        "lastUpdatedTime": 1731916800000
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<ExchangeRateVo> getAllExchangeRates() {
        ExchangeRateVo vo = ExchangeRateVo.builder()
                .xmrToCny(exchangeRateService.getXmrToCnyRate())
                .xmrToUsdt(exchangeRateService.getXmrToUsdtRate())
                .usdtToCny(exchangeRateService.getUsdtToCnyRate())
                .calToCny(marketDataService.getCalToCnyRate())
                .cfxToCny(exchangeRateService.getCfxToCnyRate())
                .cfxToUsdt(exchangeRateService.getCfxToUsdtRate())
                .cfxToXmr(exchangeRateService.getCfxToXmrRate())
                .source("CoinGecko")
                .lastUpdatedTime(System.currentTimeMillis())
                .build();
        return ApiResponse.ok(vo);
    }
}
