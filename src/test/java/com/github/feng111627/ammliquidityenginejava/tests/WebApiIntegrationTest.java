package com.github.feng111627.ammliquidityenginejava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层端到端集成测试：HTTP 状态码、错误码、JSON 字段。
 * 使用随机端口 + MockMvc，不占用固定端口。
 */
@SpringBootTest
@DisplayName("业务五：HTTP 接口端到端（建池/流动性/兑换/拒绝码/幂等回放）")
class WebApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    @Test
    @DisplayName("完整链路：水龙头 -> 建池 -> 注流动性 -> 兑换 -> 幂等重试 -> 滑点拒绝")
    void fullFlowOverHttp() throws Exception {
        // 1. 铸币
        mvc().perform(post("/api/accounts/mint").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-alice","token":"TKA","amount":1000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances.TKA").value("1000.000000000000000000"));
        mvc().perform(post("/api/accounts/mint").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-alice","token":"TKB","amount":1000000}"""))
                .andExpect(status().isOk());
        mvc().perform(post("/api/accounts/mint").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-bob","token":"TKB","amount":10000}"""))
                .andExpect(status().isOk());

        // 2. 建池（重复建第二次应 409 PAIR_ALREADY_EXISTS）
        mvc().perform(post("/api/pairs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"base":"TKA","quote":"TKB"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeRate").value(0.003));
        mvc().perform(post("/api/pairs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"base":"TKA","quote":"TKB"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_ALREADY_EXISTS"));

        // 3. 初始流动性
        mvc().perform(post("/api/liquidity/add").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-alice","base":"TKA","quote":"TKB",
                                 "baseAmount":100,"quoteAmount":200000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ADD"))
                .andExpect(jsonPath("$.shares").exists());

        // 4. 兑换
        mvc().perform(post("/api/swap").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-bob","base":"TKA","quote":"TKB",
                                 "side":"BUY_BASE","amountIn":1000,"requestId":"http-req-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.feePaid").value("3.000000000000000000"));

        // 5. 幂等回放
        mvc().perform(post("/api/swap").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-bob","base":"TKA","quote":"TKB",
                                 "side":"BUY_BASE","amountIn":1000,"requestId":"http-req-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 6. 滑点拒绝 -> 409 SLIPPAGE_PROTECTION
        mvc().perform(post("/api/swap").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-bob","base":"TKA","quote":"TKB",
                                 "side":"BUY_BASE","amountIn":1000,"minAmountOut":10}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLIPPAGE_PROTECTION"));

        // 7. 储备不足的语义：交易对不存在 -> 404
        mvc().perform(post("/api/swap").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-bob","base":"NOPE","quote":"TKB",
                                 "side":"BUY_BASE","amountIn":10}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAIR_NOT_FOUND"));

        // 8. 余额不足 -> 422
        mvc().perform(post("/api/swap").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"http-poor","base":"TKA","quote":"TKB",
                                 "side":"BUY_BASE","amountIn":10}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // 9. quote 预览
        mvc().perform(get("/api/pairs/TKA/TKB/quote").param("side", "SELL_BASE").param("amount", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenIn").value("TKA"))
                .andExpect(jsonPath("$.amountOut").exists());
    }
}
