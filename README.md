# 国际四方支付系统

> 基于 JeePay v3.2.0 改造的国际四方支付系统
> 主要面向：反三方风控 + 代理商分润 + 多币种 + 国际通道（Stripe / PayPal）

---

## 一、项目结构

```
my-international-payment/
├── jeepay-core/         基础工具与实体类（已扩展国际化字段）
├── jeepay-service/      业务服务层（已加反风控、代理商、汇率服务）
├── jeepay-payment/      支付网关（已接入 Stripe 通道）
├── jeepay-manager/      运营平台后端
├── jeepay-merchant/     商户平台后端
├── jeepay-components/   公共组件（MQ / OSS）
├── jeepay-z-codegen/    代码生成器
├── conf/                配置文件模板
├── docker/              Docker 部署脚本
├── libs/                第三方依赖
├── docs/                项目文档
│   ├── 01-需求调研.md
│   ├── 02-技术改造方案.md
│   ├── 03-Stripe通道接入说明.md
│   ├── 04-业务需求问卷-V2.md
│   ├── 05-完整技术方案-V2.md
│   ├── 06-业务需求V3-反风控.md
│   └── 07-完整技术方案V3-反风控.md
└── sql/                 数据库脚本
    ├── jeepay-origin/   JeePay 原始 SQL（init / patch）
    ├── international_payment_patch.sql   国际化补丁
    └── risk_control_patch.sql            反风控补丁
```

---

## 二、技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.7 |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0+ |
| 缓存 | Redis 3.2.8+ |
| 消息队列 | RocketMQ（可换 RabbitMQ） |
| 前端 | Vue 3 + Ant Design Vue 4.2.6（独立仓库 jeepay-ui） |

---

## 三、新增能力（相对 JeePay）

### 国际化与多币种
- 多币种订单（USD / EUR / JPY / CNY 等 10 种）
- 实时与历史汇率管理（多汇率源：API / 手动 / 通道汇率）
- 订单创建时冻结汇率快照，保证退款一致性

### 代理商体系
- 3 级代理商层级
- 多级分润计算（事务保证原子性）
- 分润记录保留费率快照

### 国际支付通道
- Stripe 信用卡支付（PaymentIntent + 3DS）
- Stripe Webhook 签名校验
- 退款支持（全额/部分）
- 待补：PayPal / Alipay International

### 反风控核心模块
- 通道账号池（多账号路由，稀释流量）
- 通道健康度引擎（拒付率/投诉率/退款率/成功率/3DS 比例）
- 商户风险评分系统（6 项加权评分）
- 订单风险评估（黑名单 + 多维评分 + 智能 3DS）
- 拒付管理（证据快照 + 申诉跟踪）
- 熔断引擎（运营配置阈值与动作）
- 多通道通知（Telegram + 邮件）

### 阈值与动作可配
- 23 项默认风控阈值（运营后台可改）
- 每项动作可独立启停（仅记录 / 实际执行）
- 系统不内置硬编码红线

---

## 四、数据库初始化

### 4.1 JeePay 基础表
```bash
mysql -u root -p jeepaydb < sql/jeepay-origin/init.sql
mysql -u root -p jeepaydb < sql/jeepay-origin/patch.sql
```

### 4.2 国际化扩展表
```bash
mysql -u root -p jeepaydb < sql/international_payment_patch.sql
```

### 4.3 反风控扩展表
```bash
mysql -u root -p jeepaydb < sql/risk_control_patch.sql
```

---

## 五、本地开发

### 5.1 编译
```bash
mvn clean install -DskipTests
```

### 5.2 启动子模块
```bash
# 支付网关（端口 9216）
cd jeepay-payment && mvn spring-boot:run

# 运营平台（端口 9217）
cd jeepay-manager && mvn spring-boot:run

# 商户平台（端口 9218）
cd jeepay-merchant && mvn spring-boot:run
```

### 5.3 配置文件
本地开发请新建 `application-local.yml`（已 .gitignore），覆盖以下配置：
- `spring.datasource.url` / `username` / `password`
- `spring.data.redis.host` / `port`
- `isys.mq.vender`

---

## 六、协作规范

参见 [CLAUDE.md](./CLAUDE.md)：
- 提交信息中文
- 注释中文优先
- 优先写"为什么"而非"是什么"

---

## 七、开发进度

| 模块 | 状态 |
|------|------|
| 代理商体系（3 级） | ✅ 已完成 |
| 多币种 + 汇率服务 | ✅ 已完成 |
| Stripe 通道（信用卡） | ✅ 已完成 |
| 反风控核心模块 | ✅ 已完成 |
| ChannelHealth 聚合 SQL | ⏳ 占位待补 |
| 数据看板 UI | ⏳ 待开发 |
| 订单流程接入风控 | ⏳ 待开发 |
| PayPal / Alipay 通道 | ⏳ 待开发 |

---

## 八、文档索引

- [需求调研](docs/01-需求调研.md)
- [Stripe 接入说明](docs/03-Stripe通道接入说明.md)
- [反风控业务需求](docs/06-业务需求V3-反风控.md)
- [反风控技术方案](docs/07-完整技术方案V3-反风控.md)
