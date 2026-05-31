/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.service.impl.UpstreamCallbackService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上游关停回执 Webhook 入口（P1）
 *
 * URL: POST /api/anon/upstream/callback?accountId=xxx
 * Header: X-Upstream-Signature: <HMAC-SHA256(rawBody) hex>
 * Body: 上游推送的原始 JSON
 *
 * 注意：
 * - 本接口落在 /api/anon/** 通配下，**无需运营 Token**，靠 HMAC 验签兜底
 * - 必须以 raw String 接收 body，否则签名会因为反序列化 / 重格式化而失效
 * - 安全审查（M7）：本路径专做上游 webhook，请勿改作他用
 *
 * @author 反风控改造组
 */
@Tag(name = "上游关停回执")
@RestController
@RequestMapping("/api/anon/upstream")
public class UpstreamCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamCallbackController.class);

    @Autowired private UpstreamCallbackService upstreamCallbackService;

    @PostMapping("/callback")
    public ApiRes callback(
            @RequestParam("accountId") String accountId,
            @RequestHeader(value = "X-Upstream-Signature", required = false) String signature,
            @RequestBody String rawPayload) {
        boolean ok = upstreamCallbackService.handle(accountId, signature, rawPayload);
        // 永远返回 200，避免上游重试风暴。失败靠日志 + 告警发现
        if (!ok) logger.warn("[UpstreamCB] 处理失败 accountId={}", accountId);
        return ApiRes.ok();
    }
}
