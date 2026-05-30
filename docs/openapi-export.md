# OpenAPI 文档导出指南

> 任务 #10：将运行中的支付网关 OpenAPI 规范导出为静态 `openapi.yaml`，便于离线分发 / 接入 Postman / 接入 Apifox / 生成 SDK。

## 1. 前置条件

- 服务以 `dev` 或 `test` profile 启动（Springdoc 在生产 profile 下默认关闭，避免元数据外泄）
- 默认端口 `9216`（见 `application.yml` 中 `server.port`）

启动示例：

```bash
# 使用开发 profile 启动
java -jar -Dspring.profiles.active=dev jeepay-payment.jar
```

## 2. 导出 YAML

```bash
# 全量（含所有分组）
curl -s http://localhost:9216/v3/api-docs.yaml > openapi.yaml

# 仅商户对外接口分组（推荐对外发布）
curl -s "http://localhost:9216/v3/api-docs/merchant-api" -H "Accept: application/yaml" > openapi-merchant-api.yaml
```

## 3. 导出 JSON

```bash
curl -s http://localhost:9216/v3/api-docs > openapi.json
curl -s http://localhost:9216/v3/api-docs/merchant-api > openapi-merchant-api.json
```

## 4. 在线访问

| 入口         | 路径                                                |
| ------------ | --------------------------------------------------- |
| Swagger UI   | http://localhost:9216/swagger-ui.html               |
| Knife4j UI   | http://localhost:9216/doc.html                      |
| OpenAPI YAML | http://localhost:9216/v3/api-docs.yaml              |
| OpenAPI JSON | http://localhost:9216/v3/api-docs                   |

## 5. 关闭生产环境暴露

`application.yml` 已配置环境变量开关：

```bash
# 生产环境关闭
export SPRINGDOC_ENABLED=false
```

或将服务以 `prod` profile 启动（`OpenApiConfig` 仅在 `dev/test` 下生效，但 `springdoc-openapi-starter` 默认仍会扫描 Controller，因此必须通过 `SPRINGDOC_ENABLED=false` 关闭 `/v3/api-docs` 与 `/swagger-ui.html` 端点）。

## 6. 验证（可选）

使用 `openapi-cli` 校验：

```bash
npx @redocly/cli lint openapi.yaml
```

或使用 Swagger Editor (https://editor.swagger.io) 在线导入查看。
