# 全链路压测套件（任务 #13）

## 目录结构
```
load-test/
├── gatling/                    # Gatling 主力压测脚本（推荐）
│   ├── pom.xml
│   └── src/test/
│       ├── scala/
│       │   ├── UnifiedOrderSimulation.scala
│       │   └── RefundSimulation.scala
│       └── resources/test-data/
│           ├── merchants.csv
│           └── cards.csv
├── jmeter/                     # JMeter 备用脚本
│   ├── unified-order.jmx
│   ├── mixed-scenario.jmx
│   └── README.md
├── webhook-simulator/          # Stripe Webhook 模拟器
│   ├── simulator.py
│   ├── requirements.txt
│   └── README.md
├── REPORT_TEMPLATE.md          # 压测报告模板
└── README.md
```

## 一键运行 Gatling
```bash
cd gatling
export API_BASE_URL=http://localhost:8080
export STRIPE_TEST_KEY=${STRIPE_TEST_KEY}

# 主链路（默认 UnifiedOrderSimulation）
mvn gatling:test -Dgatling.simulationClass=UnifiedOrderSimulation

# 退款链路
mvn gatling:test -Dgatling.simulationClass=RefundSimulation
```

## 压测目标
- **TPS**：500，持续 10 分钟
- **TP95** < 1s ｜ **TP99** < 2s ｜ **错误率** < 1%
- **风控钩子** < 50ms

## 报告
完成后基于 `REPORT_TEMPLATE.md` 填写基线数据，提交至 `docs/perf-baselines/`。
