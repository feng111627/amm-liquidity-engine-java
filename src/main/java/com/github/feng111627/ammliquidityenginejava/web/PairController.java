package com.github.feng111627.ammliquidityenginejava.web;

import com.github.feng111627.ammliquidityenginejava.pool.PoolService;
import com.github.feng111627.ammliquidityenginejava.web.dto.CreatePairRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.PoolView;
import com.github.feng111627.ammliquidityenginejava.web.dto.QuoteResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 交易对与资金池查询接口。
 */
@RestController
@RequestMapping("/api/pairs")
public class PairController {

    private final PoolService poolService;

    public PairController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping
    public PoolView create(@RequestBody CreatePairRequest request) {
        return poolService.createPair(request);
    }

    @GetMapping
    public List<PoolView> list() {
        return poolService.listPools();
    }

    @GetMapping("/{base}/{quote}")
    public PoolView get(@PathVariable String base, @PathVariable String quote) {
        return poolService.getPool(base, quote);
    }

    @GetMapping("/{base}/{quote}/quote")
    public QuoteResponse quote(@PathVariable String base,
                               @PathVariable String quote,
                               @RequestParam String side,
                               @RequestParam String amount) {
        return poolService.quote(base, quote, side, amount);
    }
}
