/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

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
 * - 邮件（SMTP）
 * - 企业微信机器人（待扩展）
 *
 * 设计：
 * - 失败不抛错，避免影响业务
 * - 渠道开关由 RiskThresholdConfig 控制
 *
 * @author 反风控改造组
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

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
     * 发送邮件（占位实现）
     * 实际项目接入 spring-boot-starter-mail
     */
    public void sendEmail(String title, String content) {
        try {
            String recipients = thresholdConfig.getString("notify.email.recipients", "");
            if (recipients.isEmpty()) return;
            logger.info("[Notify] 邮件发送（占位）: {} -> {}", title, Arrays.asList(recipients.split(",")));
            // TODO 接入 JavaMailSender
        } catch (Exception e) {
            logger.error("[Notify] 邮件发送失败", e);
        }
    }
}
