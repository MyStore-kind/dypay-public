# 安全与 Bug 检查报告

> 扫描范围：`my-international-payment`（后端 Spring Boot）+ `jeepay-ui`（Vue 前端）
> 扫描方式：本地源码静态扫描；服务器未 SSH，仅按部署描述假设
> 扫描时间：2026-05-31
> 仅检查记录，未修改任何代码或配置

---

## 摘要

| 等级 | 数量 |
| --- | --- |
| 🔴 严重 | 9 |
| 🟡 中 | 10 |
| 🟢 低 | 6 |
| 合计 | **25** |

**头号风险**：JWT 密钥与数据库密码硬编码进仓库；`CorsFilter` 允许任意来源带 Cookie；运营/商户后台 Swagger/Knife4j 与 actuator 默认开放；CERT 业务类型允许"任意后缀"上传；默认账号 `jeepay` 仍存在于初始化 SQL。

---

## 一、🔴 严重风险（9）

| # | 类别 | 位置 | 证据 | 修复建议 |
| --- | --- | --- | --- | --- |
| S1 | 硬编码 JWT Secret | `jeepay-manager/src/main/resources/application.yml:13`、`jeepay-merchant/.../application.yml:13`、`conf/manager/application.yml:130`、`conf/merchant/application.yml:130` | `jwt-secret: t7w3P8X6472qWc3u`、`ARNXp4MzjOOQqxtv` 明文，已进 Git | 改用环境变量 `JEEPAY_JWT_SECRET` 注入（compose 已有变量，但 jar 内 yml 仍是默认值，本地配置会覆盖）；立即轮换上线 secret。 |
| S2 | DB Root 密码硬编码 | `conf/manager/application.yml:40`、`conf/merchant/application.yml:40`、`conf/payment/application.yml:40`、`docker-compose.yml:40`、`docker-compose.baota.yml:35,107` | `password: rootroot`、`MYSQL_ROOT_PASSWORD: jeepaydb123456` 默认值 | 移除 yml 中默认明文，强制启动前注入 `MYSQL_ROOT_PASSWORD`；生产数据库账号最小权限化（禁止 root 直接连 jar）。 |
| S3 | CORS 配置过松 | `jeepay-manager/.../WebSecurityConfig.java:144-158`、`jeepay-merchant/.../WebSecurityConfig.java` 同 | `addAllowedOriginPattern("*")` + `allowCredentials(true)` + 全方法/全 header；任何站点可带 cookie 跨域调用后台 | 改为白名单具体域名（运营/商户域名），生产禁用 `*`。 |
| S4 | CSRF 关闭 + 同时允许跨域带 Cookie | 同上 `WebSecurityConfig.java:63` `.csrf(disable)` | 配合 S3 等同于开放 CSRF 攻击面 | 若坚持纯 token+前后端分离，必须收紧 CORS（S3）；否则需开启 CSRF。 |
| S5 | 文件上传后缀绕过（CERT 类型） | `jeepay-components/.../OssFileConfig.java:59` | `BIZ_TYPE.CERT` 注册 `allowFileSuffix = ["*"]`，`OssFileController` 走的 `isAllowFileSuffix` 在 `*` 时直接放行 | 把 CERT 改成显式白名单（pdf/jpg/png 等），禁止 jsp/php/html/svg/exe。 |
| S6 | 默认账号写入初始 SQL | `sql/jeepay-origin/init.sql:695-696` | `t_sys_user_auth` 内置用户 `jeepay`（BCrypt 密码 [REDACTED]），盐 `testkey` | 部署后必须改密并修改 salt；或在生产初始化脚本里删除该 INSERT。 |
| S7 | 异常完整 message 透传给前端 | `MchInfoService.java:178`、`PayOrderController.java:205`、`MchTransferController.java:180`、`PaytestController.java:174`、`StripeKit.java:126/147/174`、`PayPalKit.java:142/145` | `throw new BizException(e.getMessage())` 直接把 SDK / SQL / 内部栈信息返回 ApiRes | 用统一错误码 + 内部 log，不把第三方/SQL 原始信息返给前端。 |
| S8 | actuator 暴露 prometheus/metrics 在业务端口 | `conf/manager/application.yml:173-193`（同 merchant、payment） | `exposure.include: health,info,prometheus,metrics`、`base-path: /actuator`，与业务同端口 | 改 `management.server.port` 独立内网端口或加 nginx 白名单（注释里已建议但未实施）。 |
| S9 | Swagger / Knife4j 生产端默认开放 | `jeepay-manager/.../application.yml:15-35`、`jeepay-merchant`、`jeepay-payment` 全部启用 `/swagger-ui.html` `/doc.html`；`WebSecurityConfig.java:104` 又匿名放行 `/v3/api-docs/**`、`/doc.html`、`/swagger-ui/**` | 生产可直接看接口、参数 | 生产 profile 关闭 springdoc/knife4j（`springdoc.api-docs.enabled=false` + `knife4j.enable=false`），并从 `ignoringCustomizer` 移除匿名放行。 |

---

## 二、🟡 中风险（10）

| # | 类别 | 位置 | 证据 | 修复建议 |
| --- | --- | --- | --- | --- |
| M1 | FastJSON 1.x | `pom.xml:46` | `fastjson.version = 1.2.83`（虽是 1.x 最新但仍有历史 RCE 阴影，autoType 风险） | 迁移到 `fastjson2` 或 jackson；至少确认 `ParserConfig.getGlobalInstance().setSafeMode(true)`。 |
| M2 | jjwt 0.9.1 旧版 | `pom.xml:49` | 已 EOL，存在 CVE-2022-3577 等 | 升级到 `jjwt-api 0.11.x/0.12.x`，并把 HS256 secret 长度 ≥ 256bit（S1 同步处理）。 |
| M3 | OssFile 上传无认证检查 | `jeepay-components/.../OssFileController.java:38-45` | `@RequestMapping("/api/ossFiles")`，未见鉴权注解；依赖外层 SecurityFilter，但若 controller 路径未在 `permitAll`/`authenticate` 显式限制，bizType 可被遍历 | 显式声明权限（`@PreAuthorize`）并对 bizType 做白名单校验。 |
| M4 | Docker compose 默认 Grafana 密码 | `docker-compose.yml:290` | `GF_SECURITY_ADMIN_PASSWORD: admin` 默认值 | 强制必须传入环境变量，不给默认值。 |
| M5 | 大量 `e.printStackTrace()` | `JeepayKit.java:104/107`、`*Params.java`、`CodeImgUtil.java`、`ysfpay/*`、`xxpay/*`、`alipay/*`、`wxpay/*` 共 18+ 处 | 写到 stderr，宝塔可能落盘到容器 stdout，敏感栈泄漏 | 替换为 `logger.error("msg", e)`。 |
| M6 | MyBatis `${ew.sqlSegment}` | `jeepay-service/.../mapper/PayOrderDivisionRecordMapper.xml:50` | 使用 `${}` 拼接 Wrapper SQL 段（mybatis-plus 内部机制，相对安全但需确保上游 Wrapper 参数不来自用户裸入参） | 审查所有调用入口，确认 `QueryWrapper` 字段名不直接接前端字符串；保留 `${ew.sqlSegment}` 是 MP 规范。 |
| M7 | `/api/anon/**` 匿名通配 | `jeepay-manager/.../WebSecurityConfig.java:103`、`jeepay-merchant/.../WebSecurityConfig.java:103` | 所有匿名接口需逐个审查；当前包含 webhook、ws、本地 OSS 预览、登录 captcha | 改为精确路径列表，避免误加 controller 进 `anon` 包就免鉴权。 |
| M8 | `/api/anon/localOssFiles/*/*.*` 任意文件读取风险 | `jeepay-manager/.../StaticController.java:46` | 通过路径变量返回本地文件；若 bizType 未限制可能跨目录 | 校验路径不含 `..`、bizType 在白名单内。 |
| M9 | Redis 无密码默认配置 | `conf/manager/application.yml:68`（payment/merchant 同） | `redis.password:` 留空；compose 中 `REDIS_PASSWORD` 仅 deploy 有，`docker-compose.yml` 主文件未强制 | 强制 redis 设密码，绑定 127.0.0.1 或加 docker 网络隔离。 |
| M10 | Telegram 出站调用 | `jeepay-service/.../TelegramBotChannel.java:40` | 直接 `https://api.telegram.org/bot%s/sendMessage`，bot_token + chat_id 从 DB 配置读取 | 非后门，但需限制管理员才能改 `t_risk_threshold_config` 中 token；建议出口防火墙白名单。 |

---

## 三、🟢 低风险（6）

| # | 类别 | 位置 | 证据 | 修复建议 |
| --- | --- | --- | --- | --- |
| L1 | AccessKeySecret 占位符 | `conf/{manager,merchant,payment}/application.yml:153/156` | `access-key-secret: SECRET_SECRET_SECRET` 是占位 | 文档说明 + 启动校验非空。 |
| L2 | 注释泄露默认账号信息 | `conf/.../application.yml:75-85`（rabbitmq admin/admin、activemq system/manager 注释行） | 注释内 | 删除注释或替换示例。 |
| L3 | 静态资源 source map 上线 | `jeepay-payment/src/main/resources/static/cashier/js/*.js.map` | 暴露源码结构 | 生产构建去掉 `.map`。 |
| L4 | PayPal SDK 版本曾导入不存在类 | `deploy/stage11_fix2.py:80-83` 修复痕迹 | 历史 `OrdersCreateInput / CapturesRefundInput` 不存在；现已注释掉。包仍是官方 `com.paypal.sdk:paypal-server-sdk`（非 fake） | 确认 pom 中 `paypal-server-sdk` 版本号锁定并验证签名。 |
| L5 | 第三方 jar 直接放 `libs/` | `libs/jeepay-sdk-java-pls-1.2.0.jar` | 未走中央仓库的 SDK jar | 上传内部 nexus 或公布 hash，避免供应链替换。 |
| L6 | Knife4j Cors 同样跨域 | swagger 路径在 `ignoringCustomizer` 内匿名 | 生产仍能访问 | 关闭 swagger 后此项随之消失。 |

---

## 四、容器与服务器（基于本地 compose 假设，未 SSH 验证）

| # | 项 | 现状 | 建议 |
| --- | --- | --- | --- |
| C1 | 8082/8083/9216/9217/9218 暴露宿主 | compose 文件 ports 映射 0.0.0.0 | manager 9217、merchant 9218、payment 9216 仅 9216 (webhook) 需对公网；9217/9218 应反代 + IP 白名单 |
| C2 | 3308 MySQL / 6380 Redis / 61616 MQ / 8161 ActiveMQ Web / 9876 RocketMQ | compose 多端口暴露 | 一律仅内网；ActiveMQ 8161 默认 admin/admin 必须关或改密 |
| C3 | 容器以 root 跑 | Dockerfile 未发现 `USER` | 改为非 root 用户 |
| C4 | 服务器密码 `123123`（已知） | 用户给出 | 立即换强密码 + 禁 root SSH + key 登录 |
| C5 | 宝塔 1212 端口 | 默认开放 | 限制源 IP，开启认证、二步验证 |
| C6 | ufw 防火墙 | 未知 | 默认 deny，仅放行 80/443 + 反代后内网端口 |

---

## 五、依赖版本汇总

| 组件 | 当前版本 | 评估 |
| --- | --- | --- |
| spring-boot-starter-parent | 3.3.7 | ✅ 较新 |
| fastjson | 1.2.83 | 🟡 1.x 终止维护，建议迁 fastjson2 |
| jjwt | 0.9.1 | 🟡 旧，建议升 0.12.x |
| mybatis-plus-spring-boot3-starter | 3.5.7 | ✅ |
| hutool | 5.8.26 | ✅ |
| mysql-connector-j | 9.1.0 | ✅ |
| jackson-databind | 由 spring-boot 3.3.7 BOM 锁定（≥2.17） | ✅ 不在 Log4Shell 范围 |
| log4j | 由 spring-boot BOM 用 logback，无 log4j-core 1.x/2.x 漏洞链路 | ✅ |
| paypal-server-sdk | `com.paypal.sdk` 官方 | ✅ 真实 |
| stripe-java | 文档引用 `com.stripe:stripe-java`（pom 未直接 grep 到，可能在 jeepay-payment 子 pom） | 需复核 |

---

## 六、后门嫌疑结论

- **未发现** 显式后门关键字：`BackDoor` / `Backdoor` / `admin_secret` / `master_key` / `bypass_auth` / `@TestApi` 全部 0 命中。
- **未发现** webshell（无 `.jsp` `.php` 在 uploads/static）。
- **未发现** 可疑外联（除 Telegram 告警、Stripe/PayPal/Fixer.io 等业务必需）。
- **存在** 历史默认账号 `jeepay`（S6），属于开源 JeePay 原始数据，非新增后门，但生产必须删除。

---

## 七、优先级修复路线

1. **本周必修**（S1/S2/S6/S3/S4/S5/S9）：JWT/DB 密码改环境变量并轮换、关闭生产 Swagger、收紧 CORS、CERT 上传白名单、删除/改密 `jeepay` 默认账号。
2. **2 周内**（S7/S8/M1/M2/M4）：异常脱敏、actuator 独立端口、fastjson/jjwt 升级、Grafana 默认密码。
3. **常态化**（M3-M10 + L1-L6 + C1-C6）：纳入上线 checklist。

---

> 报告完。所有发现均来自本地源码静态扫描，未对服务器做任何变更。
