# AMM Liquidity Engine

本地运行的自动做市（AMM）交易所后端。基于 Spring Boot，全部状态保存在内存账本中，
覆盖交易对管理、恒定乘积定价兑换、固定比例手续费、流动性注入/赎回与 LP 份额结算。

## 运行

```bash
mvn spring-boot:run        # 默认监听 18080 端口（见 application.properties）
mvn test                   # 运行全部单元/并发/接口测试
```

测试日志中每个用例都会打印 `[PASS] 业务点-场景` 形式的行，
可以直接看出覆盖了哪些业务以及是否通过。

## 定价与收费规则

- **恒定乘积定价**：每个交易对维护两侧储备 `reserveA`、`reserveB`，兑换按
  `x * y = k` 不变式定价（与 Uniswap V2 的整数公式一致）。
- **手续费**：每笔兑换从入账中扣除 `0.3%`（`FEE_NUMERATOR / FEE_DENOMINATOR = 30 / 10000`）。
  手续费不单独记账，而是**沉淀在池子储备里**，因此兑换后 `k` 只增不减，
  手续费收益通过份额价值体现给全体 LP。
- **兑换公式**（A 换 B 为例）：

  ```
  amountInEffective = amountIn * (10000 - 30)
  amountOut = amountInEffective * reserveB / (reserveA * 10000 + amountInEffective)
  ```

  全部使用整数（BigInteger）运算，向下取整，不存在浮点误差。
- **滑点保护**：请求携带 `minAmountOut`（最少到账数量）。实际到账低于下限时
  拒绝该笔兑换，返回 `SLIPPAGE_EXCEEDED`（HTTP 422）。
- **储备不足**：池子为空、或可兑出数量取整为 0 / 顶到储备上限时拒绝，
  返回 `INSUFFICIENT_LIQUIDITY`（HTTP 422）。两种拒绝原因通过不同的
  `error` 字段区分。恒定乘积模型下储备不会被兑空，到账只会渐近逼近储备量。

## 流动性与份额结算

- **首次注入**：铸造份额 `shares = sqrt(amountA * amountB)`，同时确定初始价格。
- **追加注入**：按注入时池内实际比例计算，取两侧可铸份额的较小者：

  ```
  shares = min(amountA * totalShares / reserveA,
               amountB * totalShares / reserveB)
  ```

  保证后进入的 LP 不稀释存量份额价值。注入数量过小铸不出份额时拒绝。
- **赎回**：按份额占比等比例取回两侧储备，份额同步核销：

  ```
  amountAOut = shares * reserveA / totalShares
  amountBOut = shares * reserveB / totalShares
  ```

  赎回超出持有份额会被拒绝，不会凭空多发；份额账本（每个 LP 的余额之和）
  始终等于总份额。
- **手续费归属**：手续费沉淀在储备中而总份额不变，因此每份份额对应的
  储备随交易发生而增长，LP 赎回时自动获得累计手续费收益。

## 并发与幂等

- **并发**：同一交易对上的兑换、注入、赎回都在该交易对自己的锁内串行执行，
  不同交易对之间互不阻塞。并发下储备不会变负、份额不会超额赎回。
- **幂等**：兑换/注入/赎回都必须携带 `idempotencyKey`。同一交易对上同一
  key 只执行一次，重试（包括多线程同时重试）直接返回首次的执行结果，
  不会重复扣账或重复铸币。不同的 key 视为独立请求。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/amm/pairs` | 创建交易对 `{pairId, tokenA, tokenB}` |
| GET  | `/api/amm/pairs` | 列出全部交易对及储备/份额状态 |
| GET  | `/api/amm/pairs/{pairId}` | 查询单个交易对 |
| POST | `/api/amm/pairs/{pairId}/swap` | 兑换 `{tokenIn, amountIn, minAmountOut, idempotencyKey}` |
| POST | `/api/amm/pairs/{pairId}/liquidity/add` | 注入流动性 `{provider, amountA, amountB, idempotencyKey}` |
| POST | `/api/amm/pairs/{pairId}/liquidity/remove` | 赎回流动性 `{provider, shares, idempotencyKey}` |

错误映射：`PAIR_NOT_FOUND` 404、`PAIR_ALREADY_EXISTS` 409、
`INSUFFICIENT_LIQUIDITY` / `SLIPPAGE_EXCEEDED` 422、参数非法 400。

## 本地验证示例

```bash
# 创建交易对
curl -X POST localhost:18080/api/amm/pairs -H 'Content-Type: application/json' \
  -d '{"pairId":"ETH-USDC","tokenA":"ETH","tokenB":"USDC"}'

# 注入流动性（1 ETH = 1000 USDC 的初始价格）
curl -X POST localhost:18080/api/amm/pairs/ETH-USDC/liquidity/add \
  -H 'Content-Type: application/json' \
  -d '{"provider":"alice","amountA":1000000,"amountB":1000000000,"idempotencyKey":"add-1"}'

# 兑换 10000 ETH 单位，最少到账 1
curl -X POST localhost:18080/api/amm/pairs/ETH-USDC/swap \
  -H 'Content-Type: application/json' \
  -d '{"tokenIn":"ETH","amountIn":10000,"minAmountOut":1,"idempotencyKey":"swap-1"}'

# 用同一个 idempotencyKey 重试，返回与首次完全相同的结果，不会重复扣账
curl -X POST localhost:18080/api/amm/pairs/ETH-USDC/swap \
  -H 'Content-Type: application/json' \
  -d '{"tokenIn":"ETH","amountIn":10000,"minAmountOut":1,"idempotencyKey":"swap-1"}'

# 赎回部分份额
curl -X POST localhost:18080/api/amm/pairs/ETH-USDC/liquidity/remove \
  -H 'Content-Type: application/json' \
  -d '{"provider":"alice","shares":1000000,"idempotencyKey":"rm-1"}'
```

## 测试覆盖

| 测试类 | 覆盖业务 |
| --- | --- |
| `SwapTest` | 恒定乘积定价与手续费、滑点保护、储备不足（与滑点区分）、反向兑换、参数校验 |
| `LiquidityTest` | 首次/追加注入的份额计算、等比例赎回、手续费沉淀进份额价值、超份额赎回与粉尘注入拒绝 |
| `ConcurrencyTest` | 并发兑换储备不为负且 k 递增、并发赎回不超发、兑换与流动性混合并发下账目守恒 |
| `IdempotencyTest` | 三类操作的幂等重试、多线程同 key 并发只执行一次、不同 key 独立执行 |
| `AmmApiTest` | REST 全链路（建对→注入→兑换→赎回）与错误状态码映射 |
