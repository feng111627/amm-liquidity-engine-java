package com.github.feng111627.ammliquidityenginejava.web;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import com.github.feng111627.ammliquidityenginejava.ledger.Ledger;
import com.github.feng111627.ammliquidityenginejava.web.dto.AccountView;
import com.github.feng111627.ammliquidityenginejava.web.dto.MintRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.TreeMap;

/**
 * 账户查询与本地测试水龙头。
 * 水龙头仅用于本地跑通流程，没有真实发行含义。
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final Ledger ledger;

    public AccountController(Ledger ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/mint")
    public AccountView mint(@RequestBody MintRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "userId 不能为空");
        }
        if (request.token() == null || request.token().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "token 不能为空");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "铸造数量必须为正数：" + amount);
        }
        ledger.credit(request.userId(), request.token().toUpperCase(), amount);
        return get(request.userId());
    }

    @GetMapping("/{userId}")
    public AccountView get(@PathVariable String userId) {
        // TreeMap 让输出按代币符号排序，方便肉眼核对
        return new AccountView(userId, new TreeMap<>(ledger.balancesOf(userId)));
    }
}
