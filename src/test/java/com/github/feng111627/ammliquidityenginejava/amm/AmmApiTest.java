package com.github.feng111627.ammliquidityenginejava.amm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 接口：端点连通性与业务异常的 HTTP 状态码映射 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(BusinessCaseLogger.class)
class AmmApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("接口-全链路：建对 -> 注入 -> 兑换 -> 赎回，各端点返回预期字段")
    void fullFlowOverHttp() throws Exception {
        mvc.perform(post("/api/amm/pairs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairId\":\"ETH-USDC\",\"tokenA\":\"ETH\",\"tokenB\":\"USDC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairId").value("ETH-USDC"));

        mvc.perform(post("/api/amm/pairs/ETH-USDC/liquidity/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"alice\",\"amountA\":1000000,"
                                + "\"amountB\":1000000000,\"idempotencyKey\":\"add-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharesMinted").value(31622776));

        mvc.perform(post("/api/amm/pairs/ETH-USDC/swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenIn\":\"ETH\",\"amountIn\":10000,"
                                + "\"minAmountOut\":1,\"idempotencyKey\":\"swap-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenOut").value("USDC"))
                .andExpect(jsonPath("$.amountOut").isNumber());

        mvc.perform(post("/api/amm/pairs/ETH-USDC/liquidity/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"alice\",\"shares\":1000,"
                                + "\"idempotencyKey\":\"rm-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountAOut").isNumber());

        mvc.perform(get("/api/amm/pairs/ETH-USDC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairId").value("ETH-USDC"));
    }

    @Test
    @DisplayName("接口-错误映射：未知交易对 404、重复建对 409、滑点/储备不足 422")
    void errorStatusMapping() throws Exception {
        mvc.perform(get("/api/amm/pairs/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAIR_NOT_FOUND"));

        mvc.perform(post("/api/amm/pairs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairId\":\"A-B\",\"tokenA\":\"A\",\"tokenB\":\"B\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/amm/pairs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairId\":\"A-B\",\"tokenA\":\"A\",\"tokenB\":\"B\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAIR_ALREADY_EXISTS"));

        // 空池兑换 -> 储备不足 422
        mvc.perform(post("/api/amm/pairs/A-B/swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenIn\":\"A\",\"amountIn\":100,"
                                + "\"minAmountOut\":0,\"idempotencyKey\":\"s1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_LIQUIDITY"));

        // 有池子但滑点下限过高 -> 滑点 422
        mvc.perform(post("/api/amm/pairs/A-B/liquidity/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"lp\",\"amountA\":1000000,"
                                + "\"amountB\":1000000,\"idempotencyKey\":\"a1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/amm/pairs/A-B/swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenIn\":\"A\",\"amountIn\":100,"
                                + "\"minAmountOut\":999999999,\"idempotencyKey\":\"s2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SLIPPAGE_EXCEEDED"));
    }
}
