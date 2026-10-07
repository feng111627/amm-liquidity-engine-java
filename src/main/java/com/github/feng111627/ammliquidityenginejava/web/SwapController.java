package com.github.feng111627.ammliquidityenginejava.web;

import com.github.feng111627.ammliquidityenginejava.pool.PoolService;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兑换接口。
 */
@RestController
@RequestMapping("/api/swap")
public class SwapController {

    private final PoolService poolService;

    public SwapController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping
    public SwapResponse swap(@RequestBody SwapRequest request) {
        return poolService.swap(request);
    }
}
