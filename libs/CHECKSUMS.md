# 第三方 JAR 校验和（安全加固 L5）

为防止 libs 目录下的第三方依赖被篡改，记录 SHA256 校验值。CI/部署前可执行 `sha256sum -c` 比对。

| 文件 | SHA256 |
| --- | --- |
| jeepay-sdk-java-pls-1.2.0.jar | `72233b06f7a304878522d30f027ca65a328c7284a67d4d7fa24e1c06d373e188` |

## 校验方法

```bash
# Linux / macOS
sha256sum -c <<'EOF'
72233b06f7a304878522d30f027ca65a328c7284a67d4d7fa24e1c06d373e188  jeepay-sdk-java-pls-1.2.0.jar
EOF

# Windows
certutil -hashfile jeepay-sdk-java-pls-1.2.0.jar SHA256
```

## 维护说明

- 新增/升级 libs 内的 jar 时必须更新本文件。
- 若发现哈希不一致，立即停止构建并溯源。
