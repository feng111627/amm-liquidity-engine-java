package com.github.feng111627.ammliquidityenginejava.amm.api;

import com.github.feng111627.ammliquidityenginejava.amm.model.*;
import com.github.feng111627.ammliquidityenginejava.amm.service.AmmExchangeService;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;

/** AMM 交易所 REST 接口 */
@RestController
@RequestMapping("/api/amm")
public class AmmController {

    private final AmmExchangeService service;

    public AmmController(AmmExchangeService service) {
        this.service = service;
    }

    public record CreatePairRequest(String pairId, String tokenA, String tokenB) {}
    public record SwapRequest(String tokenIn, BigInteger amountIn,
                              BigInteger minAmountOut, String idempotencyKey) {}
    public record AddLiquidityRequest(String provider, BigInteger amountA,
                                      BigInteger amountB, String idempotencyKey) {}
    public record RemoveLiquidityRequest(String provider, BigInteger shares,
                                         String idempotencyKey) {}

    @PostMapping("/pairs")
    public TradingPair createPair(@RequestBody CreatePairRequest req) {
        return service.createPair(req.pairId(), req.tokenA(), req.tokenB());
    }

    @GetMapping("/pairs")
    public List<TradingPair> listPairs() {
        return service.listPairs();
    }

    @GetMapping("/pairs/{pairId}")
    public TradingPair getPair(@PathVariable String pairId) {
        return service.getPair(pairId);
    }

    @PostMapping("/pairs/{pairId}/swap")
    public SwapResult swap(@PathVariable String pairId, @RequestBody SwapRequest req) {
        return service.swap(pairId, req.tokenIn(), req.amountIn(),
                req.minAmountOut(), req.idempotencyKey());
    }

    @PostMapping("/pairs/{pairId}/liquidity/add")
    public AddLiquidityResult addLiquidity(@PathVariable String pairId,
                                           @RequestBody AddLiquidityRequest req) {
        return service.addLiquidity(pairId, req.provider(), req.amountA(),
                req.amountB(), req.idempotencyKey());
    }

    @PostMapping("/pairs/{pairId}/liquidity/remove")
    public RemoveLiquidityResult removeLiquidity(@PathVariable String pairId,
                                                 @RequestBody RemoveLiquidityRequest req) {
        return service.removeLiquidity(pairId, req.provider(), req.shares(),
                req.idempotencyKey());
    }
}
