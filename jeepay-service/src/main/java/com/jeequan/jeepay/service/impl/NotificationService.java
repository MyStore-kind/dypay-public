/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.service.notify.EmailChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 通知服务
 *
 * 支持渠道：
 * - Telegram Bot（运营首选，免费）
 * - 邮件（SMTP，委托给 {@link EmailChannel}，避免双份实现）
 * - 企业微信机器人（待扩展）
 *
 * 设计：
 * - 失败不抛错，避免影响业务
 * - 渠道开关由 RiskThresholdConfig 控制
 *
 * 历史说明：
 * - 原 sendEmail 留有 TODO 占位（K6），实际邮件能力已落在 EmailChannel.send 中。
 *   本类只是更轻量的"标题/正文"型入口，因此 sendEmail 直接复用 EmailChannel，避免 SMTP 配置维护两套。
 *
 * @author 反风控改造组
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

    /**
     * 邮件渠道：复用既有的 SMTP 实现。
     * 为什么注入而不是新建：EmailChannel 已实现 JavaMailSender 动态构造 + 配置降级，
     *   重复实现会形成两套 SMTP 配置入口，运营后台改一处不生效。
     */
    @Autowired
    private EmailChannel emailChannel;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 发送告警
     * 自动按配置选择启用的渠道并发送
     */
    public void notify(String title, String content) {
        String fullMsg = "【" + title + "】\n" + content;

        if (thresholdConfig.getBoolean("notify.telegram.enabled", false)) {
            sendTelegram(fullMsg);
        }
        if (thresholdConfig.getBoolean("notify.email.enabled", false)) {
            sendEmail(title, content);
        }
    }

    /**
     * 发送 Telegram
     * 注意事项：Bot Token 需运营在后台配置
     */
    public void sendTelegram(String message) {
        try {
            String token = thresholdConfig.getString("notify.telegram.bot_token", "");
            String chatId = thresholdConfig.getString("notify.telegram.chat_id", "");
            if (token.isEmpty() || chatId.isEmpty()) {
                logger.warn("[Notify] Telegram 配置不完整，跳过发送");
                return;
            }
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
                    token, chatId,
                    URLEncoder.encode(message, StandardCharsets.UTF_8));
            restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            logger.error("[Notify] Telegram 发送失败", e);
        }
    }

    /**
     * 发送邮件
     * 为什么委托给 EmailChannel：复用 SMTP 配置 + JavaMailSender 实现，避免两份代码维护两套行为。
     * 注意事项：本入口走的是"全局收件人"配置 notify.email.recipients；
     *           若要按告警类型分发，请直接调用 RiskAlertNotifier，由其分发到 EmailChannel。
     */
    public void sendEmail(String title, String content) {
        try {
            String recipients = thresholdConfig.getString("notify.email.recipients", "");
            if (recipients.isEmpty()) {
                logger.debug("[Notify] 邮件收件人未配置，跳过");
                return;
            }
            // 复用 EmailChannel 的 send：以 SYSTEM 类型走默认收件人（fallback 路径）
            // 失败不抛错（业务约定），仅记日志
            String to = emailChannel.send(RiskAlertType.SYSTEM, title, content);
            if (to == null || to.isEmpty()) {
                logger.warn("[Notify] 邮件发送返回空 to，可能 SMTP 配置缺失：{}", title);
            } else {
                logger.info("[Notify] 邮件发送成功: {} -> {}", title, Arrays.asList(to.split(",")));
            }
        } catch (Exception e) {
            logger.error("[Notify] 邮件发送失败", e);
        }
    }
}

