#!/usr/bin/env bash
# 本地 AMM 演示脚本：要求应用已在 localhost:8181 启动
#   mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8181
set -euo pipefail
BASE=${BASE:-http://localhost:8181}

call() { # call METHOD PATH [JSON_BODY]
  local method=$1 path=$2 body=${3:-}
  if [[ -n "$body" ]]; then
    curl -s -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE$path"
  fi
  echo
}

echo "== 1. 铸币：alice 100 ETH / 100000 USDC；bob 10000 USDC =="
call POST /api/accounts/mint '{"userId":"alice","token":"ETH","amount":100}'
call POST /api/accounts/mint '{"userId":"alice","token":"USDC","amount":100000}'
call POST /api/accounts/mint '{"userId":"bob","token":"USDC","amount":10000}'

echo "== 2. 创建交易对 ETH/USDC =="
call POST /api/pairs '{"base":"ETH","quote":"USDC"}'

echo "== 3. alice 注入初始流动性 10 ETH / 20000 USDC =="
call POST /api/liquidity/add '{"userId":"alice","base":"ETH","quote":"USDC","baseAmount":10,"quoteAmount":20000}'

echo "== 4. bob 用 1000 USDC 买 ETH =="
call POST /api/swap '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":1000}'

echo "== 5. 幂等：同一 requestId 提交两次（第二次 replayed=true，只扣一次款） =="
call POST /api/swap '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":500,"requestId":"demo-1"}'
call POST /api/swap '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":500,"requestId":"demo-1"}'

echo "== 6. 滑点保护：下限 1 ETH 应被拒绝（SLIPPAGE_PROTECTION） =="
call POST /api/swap '{"userId":"bob","base":"ETH","quote":"USDC","side":"BUY_BASE","amountIn":1000,"minAmountOut":1}'

echo "== 7. bob 余额（500 只扣一次） =="
call GET /api/accounts/bob

echo "== 8. alice 赎回一半 LP 份额 =="
call POST /api/liquidity/remove '{"userId":"alice","base":"ETH","quote":"USDC","shares":223.606797749978969640}'

echo "== 9. 池状态 / 行情预览 =="
call GET /api/pairs/ETH/USDC
call GET "/api/pairs/ETH/USDC/quote?side=SELL_BASE&amount=1"
