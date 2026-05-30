/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.notify;

import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import com.jeequan.jeepay.service.impl.SysConfigService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 邮件告警渠道
 *
 * 设计要点：
 *  - SMTP 配置从 t_sys_config 读取：smtp.host / smtp.port / smtp.username / smtp.password / smtp.from
 *  - 每次发送动态构造 JavaMailSenderImpl：让运营改配置后无需重启即可生效
 *  - 失败抛 RuntimeException，由 Notifier 统一重试
 *  - 配置缺失时降级为日志，不抛错
 *
 * @author 反风控改造组
 */
@Component
public class EmailChannel implements IRiskNotifyChannel {

    private static final Logger logger = LoggerFactory.getLogger(EmailChannel.class);

    @Autowired
    private RiskThresholdConfigService riskCfg;

    @Autowired
    private SysConfigService sysConfigService;

    @Override
    public String channelName() {
        return "email";
    }

    @Override
    public boolean isEnabled(RiskAlertType type) {
        if (!riskCfg.getBoolean("notify.email.enabled", false)) {
            return false;
        }
        if (extractEmails(type).isEmpty()) {
            return false;
        }
        return StringUtils.isNotBlank(getSmtpConfig("smtp.host"))
                && StringUtils.isNotBlank(getSmtpConfig("smtp.from"));
    }

    @Override
    public String send(RiskAlertType type, String title, String body) {
        List<String> emails = extractEmails(type);
        if (emails.isEmpty()) {
            // fallback：使用全局 recipients 配置
            String fallback = riskCfg.getString("notify.email.recipients", "");
            if (StringUtils.isNotBlank(fallback)) {
                emails = Arrays.asList(fallback.split(","));
            }
        }
        if (emails.isEmpty()) {
            logger.warn("[EmailChannel] 未配置告警目标，跳过：type={}", type);
            return "";
        }

        JavaMailSenderImpl sender;
        try {
            sender = buildSender();
        } catch (Exception e) {
            // 降级：配置异常时仅落日志，不阻塞流程
            logger.error("[EmailChannel] SMTP 配置异常，跳过：{}", e.getMessage());
            return "";
        }

        String from = getSmtpConfig("smtp.from");
        String[] to = emails.stream().map(String::trim)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("[风险告警] " + title);
        msg.setText(body);

        // 失败抛 MailException -> 上层 Notifier 捕获并重试
        sender.send(msg);
        return String.join(",", to);
    }

    /**
     * 按配置动态构造 JavaMailSender
     * 为什么不做单例：运营调整 SMTP 后立即生效，避免重启服务
     */
    private JavaMailSenderImpl buildSender() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(getSmtpConfig("smtp.host"));
        String port = getSmtpConfig("smtp.port");
        if (StringUtils.isNotBlank(port)) {
            impl.setPort(Integer.parseInt(port));
        }
        impl.setUsername(getSmtpConfig("smtp.username"));
        impl.setPassword(getSmtpConfig("smtp.password"));
        impl.setDefaultEncoding("UTF-8");

        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        // 25/465/587 各自启用 SSL 策略不同，运营可在 sys_config 自行调整
        return impl;
    }

    private String getSmtpConfig(String key) {
        com.jeequan.jeepay.core.entity.SysConfig c = sysConfigService.getById(key);
        return c == null ? "" : StringUtils.defaultString(c.getConfigVal());
    }

    /** 解析 notify.targets.{type}，取 '|' 后段为邮箱列表 */
    private List<String> extractEmails(RiskAlertType type) {
        String raw = riskCfg.getString("notify.targets." + type.configSuffix(), "");
        if (StringUtils.isBlank(raw) || !raw.contains("|")) {
            return java.util.Collections.emptyList();
        }
        String emailPart = raw.substring(raw.indexOf('|') + 1);
        if (StringUtils.isBlank(emailPart)) return java.util.Collections.emptyList();
        return Arrays.asList(emailPart.split(","));
    }
}
