# JMeter 备用压测脚本

## 前置依赖
- JMeter 5.6+
- JDK 17+

## 运行命令

### 1. 统一下单基础压测
```bash
jmeter -n -t unified-order.jmx \
  -Jthreads=500 \
  -JbaseUrl=http://localhost:8080 \
  -JstripeKey=${STRIPE_TEST_KEY} \
  -l result/unified.jtl \
  -e -o report/unified
```

### 2. 混合场景压测（下单 60% + 查询 30% + 退款 10%）
```bash
jmeter -n -t mixed-scenario.jmx \
  -JbaseUrl=http://localhost:8080 \
  -l result/mixed.jtl \
  -e -o report/mixed
```

## 参数说明
| 参数 | 含义 | 默认值 |
|------|------|--------|
| `threads` | 并发线程数 | 500 |
| `baseUrl` | API 基础地址 | http://localhost:8080 |
| `stripeKey` | Stripe 测试密钥（占位符） | `${STRIPE_TEST_KEY}` |
| `duration` | 持续时间（秒） | 600 |
| `ramp_time` | 加压时间（秒） | 120 |

## 报告查看
执行后查看 `report/unified/index.html` 即可看到 TPS、响应时间分布、错误率等可视化图表。

## 注意事项
- 推荐使用 Gatling（同目录 `../gatling/`），表达力与报告精度更高。
- JMeter 仅用于无 Scala 环境的备用方案。
- 生产环境压测前需获得 Stripe 沙箱配额上限的确认。
