# AMM 流动性引擎（本地内存版）

一个本地运行的自动做市（AMM）交易所后端：内存账本模拟用户余额，每个交易对有独立资金池，
支持恒定乘积定价的兑换、固定比例手续费、流动性注入/赎回、滑点保护、并发安全与幂等重试。
不依赖数据库，重启即清空。

- Java 21 / Spring Boot 3.4 / Spring Web
- 全部金额为 18 位小数 `BigDecimal`，除法一律向下取整（FLOOR），池子永远不会被“多发”

---

## 1. 定价与收费规则

### 1.1 恒定乘积

资金池持有两种代币储备 `x`（base）、`y`（quote），满足：

```
x * y = k
```

兑换时，输入侧收取 **0.3%** 手续费后再参与乘积计算：

```
amountInWithFee = amountIn * (1 - feeRate)          // feeRate = 0.003
amountOut       = floor( reserveOut * amountInWithFee
                         / (reserveIn + amountInWithFee) )
```

特点：

- 买入越多，价格越差（滑点自然产生于公式）；
- 手续费 **不单独提走**，而是留在池内，使 `k` 只增不减，长期体现在 LP 份额价值上涨；
- 除法 FLOOR 到 18 位小数，`amountOut` 恒严格小于输出侧储备，池不可能被单笔抽空。

### 1.2 滑点保护与拒绝原因

兑换请求可带 `minAmountOut`：

| 情况 | 错误码 | HTTP |
|---|---|---|
| 交易对不存在 | `PAIR_NOT_FOUND` | 404 |
| 池尚无初始流动性 | `INSUFFICIENT_LIQUIDITY` | 422 |
| 池输出侧储备不足以给出正输出 | `INSUFFICIENT_POOL_RESERVE` | 422 |
| `amountOut < minAmountOut`（滑点保护） | `SLIPPAGE_PROTECTION` | 409 |
| 用户输入代币余额不足 | `INSUFFICIENT_BALANCE` | 422 |
| 金额非正 / side 非法 | `INVALID_AMOUNT` | 400 |
| 同 requestId 但参数不同 | `IDEMPOTENCY_KEY_CONFLICT` | 409 |

“储备不够”和“到账低于下限”是两个**独立错误码**，调用方可以据此区分处理。

### 1.3 兑换方向

- `side=BUY_BASE`：用 `quote` 买入 `base`；
- `side=SELL_BASE`：卖出 `base` 换 `quote`。

---

## 2. 流动性与份额结算

### 2.1 首次注入（建池）

```
totalShares = floor(sqrt(baseIn * quoteIn))
lpShares    = totalShares - MINIMUM_LIQUIDITY        // MINIMUM_LIQUIDITY = 1e-15
```

与 Uniswap V2 一致：`1e-15` 份永久锁死在零地址 `0x0-locked`，不可赎回，
用来消除首笔流动性的份额操纵，也保证总份额永不为零。

### 2.2 后续注入

按当前池价格取“更吃亏”的一侧发份额，保证不稀释既有 LP：

```
shares = min( baseIn / reserveBase, quoteIn / reserveQuote ) * totalShares
```

用户提交的两种代币中，超出当前池价比例的部分会**原路退回**（内部先全额划转再退差）。
可带 `minBase` / `minQuote` 做注入滑点保护，低于下限整笔拒绝，用户资金不动。

### 2.3 赎回

```
baseOut  = floor( shares * reserveBase  / totalShares )
quoteOut = floor( shares * reserveQuote / totalShares )
```

- 销毁 LP 份额，按比例从两种储备中取回；
- FLOOR 保证池不会被多取；两次赎回（先一半再一半）合计不超过原储备；
- 赎回后 剩余储备 / 剩余总份额 不下降，剩余 LP 价值不受损；
- 手续费沉淀：发生过兑换后，LP 等份额赎回能取回比注入时更多的综合价值。

### 2.4 记账与不变量

所有资金流动都经过内存 `Ledger.transfer` 划转（用户账户 ⇄ 池账户），
资金池对象里的储备只是账本镜像。每笔业务结束都会断言：

```
pool.reserveBase  == ledger[poolAccount][base]
pool.reserveQuote == ledger[poolAccount][quote]
```

---

## 3. 并发与幂等

### 3.1 并发

- 每个资金池持有一把互斥锁，**同一交易对**的兑换/注入/赎回完全串行，
  因此不会出现并发超额提取、储备与份额对不上的情况；
- 余额用 `ConcurrentHashMap` + `compute/merge` 原子更新，划转在账本内同步完成，借贷相等；
- 不同交易对之间互不阻塞。

### 3.2 幂等

写操作（兑换、注入、赎回）都可以带 `requestId`：

- 同 `requestId` + 同参数：首次执行并缓存结果；重试直接回放首次结果，
  响应中 `replayed=true`，**不重复扣款、不重复铸份额**；
- 同 `requestId` + 不同参数：返回 `IDEMPOTENCY_KEY_CONFLICT`；
- 首次请求业务失败（如余额不足）不会占用该键，充值后可用同一键同参数重试成功。

锁顺序固定为 `requestId 锁 -> 池锁`，避免死锁。

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/accounts/mint` | 本地水龙头：给用户铸造代币 |
| GET  | `/api/accounts/{userId}` | 查询用户全部代币余额 |
| POST | `/api/pairs` | 创建交易对（重复创建 409） |
| GET  | `/api/pairs` | 列出所有资金池 |
| GET  | `/api/pairs/{base}/{quote}` | 查询池状态（储备、总份额、费率） |
| GET  | `/api/pairs/{base}/{quote}/quote?side=&amount=` | 行情预览（不落账） |
| POST | `/api/liquidity/add` | 注入流动性 |
| POST | `/api/liquidity/remove` | 赎回流动性 |
| POST | `/api/swap` | 兑换 |

错误响应统一为：

```json
{ "code": "SLIPPAGE_PROTECTION", "message": "预计到账 ... 低于下限 ..." }
```

### 4.1 兑换请求示例

```json
{
  "userId": "bob",
  "base": "ETH",
  "quote": "USDC",
  "side": "BUY_BASE",
  "amountIn": 1000,
  "minAmountOut": 0.47,
  "requestId": "tx-20261007-0001"
}
```

### 4.2 注入请求示例

```json
{
  "userId": "alice",
  "base": "ETH",
  "quote": "USDC",
  "baseAmount": 10,
  "quoteAmount": 20000,
  "minBase": 9.9,
  "minQuote": 19800,
  "requestId": "add-0001"
}
```

### 4.3 赎回请求示例

```json
{
  "userId": "alice",
  "base": "ETH",
  "quote": "USDC",
  "shares": 223.606797749978969640
}
```

---

## 5. 本地验证

### 5.1 跑单元 / 集成测试

```bash
mvn test
```

共 36 个用例，日志按业务分组、逐条打印中文名和 ✓/✗：

- 业务一：AMM 定价数学（恒定乘积、手续费、份额数学）
- 业务二：兑换（双向兑换、滑点边界、6 类拒绝原因区分）
- 业务三：流动性（首次/比例/失衡注入、滑点拒绝、部分与全额赎回、手续费沉淀）
- 业务四：并发安全与幂等（200 用户并发兑换、并发注入/赎回不超发、
  同 requestId 重试只扣一次、键冲突、失败可重试）
- 业务五：HTTP 端到端（状态码与错误响应体）

结尾有汇总行：`================ 汇总：通过 36 / 失败 0 ================`。

### 5.2 启动应用手工验证

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8181
```

另开终端执行 `scripts/local-demo.sh`（或按下面顺序手动 curl）：

```bash
# 1) 铸币
curl -s -X POST localhost:8181/api/accounts/mint -H 'Content-Type: application/json' \
  -d '{"userId":"alice","token":"ETH","amount":100}'
curl -s -X POST localhost:8181/api/accounts/mint -H 'Content-Type: application/json' \
  -d '{"userId":"alice","token":"USDC","amount":100000}'
curl -s -X POST localhost:8181/api/accounts/mint -H 'Content-Type: application/json' \
  -d '{"userId":"bob","token":"USDC","amount":10000}'

# 2) 建池 + 初始流动性
curl -s -X POST localhost:8181/api/pairs -H 'Content-Type: application/json' \
  -d '{"base":"ETH","quote":"USDC"}'
curl -s -X POST localhost:8181/api/liquidity/add -H 'Content-Type: application/json' \
  -d '{"userId":"alice","base":"ETH","quote":"USDC","baseAmount":10,"quoteAmount":20000}'

# 3) 兑换
curl -s -X POST localhost:8181/api/swap -H 'Content-Type: application/json' \
  -d '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":1000}'

# 4) 幂等重试（连按两次，第二次 replayed=true，USDC 只扣一次）
curl -s -X POST localhost:8181/api/swap -H 'Content-Type: application/json' \
  -d '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":500,"requestId":"demo-1"}'
curl -s -X POST localhost:8181/api/swap -H 'Content-Type: application/json' \
  -d '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":500,"requestId":"demo-1"}'

# 5) 滑点保护（应返回 SLIPPAGE_PROTECTION）
curl -s -X POST localhost:8181/api/swap -H 'Content-Type: application/json' \
  -d '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":1000,"minAmountOut":1}'

# 6) 赎回 / 查池 / 查余额
curl -s -X POST localhost:8181/api/liquidity/remove -H 'Content-Type: application/json' \
  -d '{"userId":"alice","base":"ETH","quote":"USDC","shares":223.606797749978969640}'
curl -s localhost:8181/api/pairs/ETH/USDC
curl -s localhost:8181/api/accounts/alice
```

> 说明：`/api/accounts/mint` 仅是没有鉴权的本地测试水龙头，用于给账户充值；
> 生产场景应替换为真实资金入口。
