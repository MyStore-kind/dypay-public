/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.notify;

import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot 告警渠道
 *
 * 注意事项：
 *  - 全局 token 从 t_risk_threshold_config 的 notify.telegram.bot_token 读取
 *  - 触达目标（chat_id 列表）从 notify.targets.{type 小写} 读取，格式：tgIds|emails
 *  - 任一目标发送失败即抛 RuntimeException，由 Notifier 统一重试
 *  - 配置缺失时降级为日志，不抛错（避免阻塞流程，符合需求）
 *
 * @author 反风控改造组
 */
@Component
public class TelegramBotChannel implements IRiskNotifyChannel {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotChannel.class);

    /** Telegram sendMessage 接口模板 */
    private static final String SEND_API = "https://api.telegram.org/bot%s/sendMessage";

    @Autowired
    private RiskThresholdConfigService cfg;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String channelName() {
        return "telegram";
    }

    @Override
    public boolean isEnabled(RiskAlertType type) {
        if (!cfg.getBoolean("notify.telegram.enabled", false)) {
            return false;
        }
        List<String> chatIds = extractChatIds(type);
        return !chatIds.isEmpty()
                && StringUtils.isNotBlank(cfg.getString("notify.telegram.bot_token", ""));
    }

    @Override
    public String send(RiskAlertType type, String title, String body) {
        String token = cfg.getString("notify.telegram.bot_token", "");
        // 兼容场景：若该类型没有专属目标，则 fallback 到全局 chat_id
        List<String> chatIds = extractChatIds(type);
        if (chatIds.isEmpty()) {
            String fallback = cfg.getString("notify.telegram.chat_id", "");
            if (StringUtils.isNotBlank(fallback)) {
                chatIds = Arrays.asList(fallback.split(","));
            }
        }
        if (StringUtils.isBlank(token) || chatIds.isEmpty()) {
            // 降级：不抛异常，直接返回空目标（Notifier 会按"无目标"处理，避免阻塞）
            logger.warn("[TelegramChannel] 配置缺失，跳过：type={}", type);
            return "";
        }

        // 完整 Markdown 文案：标题加粗 + 空行 + 正文
        String text = "*" + escapeMarkdown(title) + "*\n\n" + body;

        String url = String.format(SEND_API, token);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        StringBuilder delivered = new StringBuilder();
        for (String chatId : chatIds) {
            String id = chatId.trim();
            if (id.isEmpty()) continue;
            Map<String, Object> req = new HashMap<>();
            req.put("chat_id", id);
            req.put("text", text);
            req.put("parse_mode", "Markdown");
            // 这里若网络/HTTP 异常会抛出，由 Notifier 重试
            restTemplate.postForObject(url, new HttpEntity<>(req, headers), String.class);
            if (delivered.length() > 0) delivered.append(",");
            delivered.append(id);
        }
        return delivered.toString();
    }

    /** 解析 notify.targets.{type}，取 '|' 前段为 telegram chat id 列表 */
    private List<String> extractChatIds(RiskAlertType type) {
        String raw = cfg.getString("notify.targets." + type.configSuffix(), "");
        if (StringUtils.isBlank(raw)) return java.util.Collections.emptyList();
        String tgPart = raw.contains("|") ? raw.substring(0, raw.indexOf('|')) : raw;
        if (StringUtils.isBlank(tgPart)) return java.util.Collections.emptyList();
        return Arrays.asList(tgPart.split(","));
    }

    /** 转义 Telegram Markdown V1 保留字符，避免标题中出现 _ * [ ] 时被识别为格式 */
    private String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_").replace("*", "\\*")
                .replace("[", "\\[").replace("`", "\\`");
    }
}
