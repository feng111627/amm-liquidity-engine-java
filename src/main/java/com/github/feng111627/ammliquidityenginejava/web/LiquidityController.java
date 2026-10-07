package com.github.feng111627.ammliquidityenginejava.web;

import com.github.feng111627.ammliquidityenginejava.pool.PoolService;
import com.github.feng111627.ammliquidityenginejava.web.dto.AddLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.LiquidityResponse;
import com.github.feng111627.ammliquidityenginejava.web.dto.RemoveLiquidityRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流动性注入/赎回接口。
 */
@RestController
@RequestMapping("/api/liquidity")
public class LiquidityController {

    private final PoolService poolService;

    public LiquidityController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping("/add")
    public LiquidityResponse add(@RequestBody AddLiquidityRequest request) {
        return poolService.addLiquidity(request);
    }

    @PostMapping("/remove")
    public LiquidityResponse remove(@RequestBody RemoveLiquidityRequest request) {
        return poolService.removeLiquidity(request);
    }
}
