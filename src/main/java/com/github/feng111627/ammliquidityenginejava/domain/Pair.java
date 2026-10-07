package com.github.feng111627.ammliquidityenginejava.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * 一个交易对：base（基准代币）/ quote（计价代币）。
 * 代币符号统一大写规范化；base 与 quote 不允许相同。
 */
public final class Pair {

    private final String base;
    private final String quote;

    public Pair(String base, String quote) {
        if (base == null || base.isBlank() || quote == null || quote.isBlank()) {
            throw new IllegalArgumentException("交易对代币符号不能为空");
        }
        this.base = base.trim().toUpperCase(Locale.ROOT);
        this.quote = quote.trim().toUpperCase(Locale.ROOT);
        if (this.base.equals(this.quote)) {
            throw new IllegalArgumentException("交易对两种代币不能相同：" + this.base);
        }
    }

    public String getBase() {
        return base;
    }

    public String getQuote() {
        return quote;
    }

    /** 注册表使用的稳定 key。 */
    public String key() {
        return base + "/" + quote;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair other)) {
            return false;
        }
        return base.equals(other.base) && quote.equals(other.quote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, quote);
    }

    @Override
    public String toString() {
        return key();
    }
}
