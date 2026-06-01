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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件告警渠道
 *
 * 设计要点：
 *  - SMTP 配置从 t_sys_config 读取：smtp.host / smtp.port / smtp.username / smtp.password / smtp.from
 *  - SMTP 配置加 60s 本地缓存：避免每次 send 触发 5-6 次 DB 读（高频告警放大问题）。
 *    运营改配置后最长 60s 内自动生效，无需重启。
 *  - 失败抛 RuntimeException，由 Notifier 统一重试
 *  - 配置缺失时降级为日志，不抛错
 *  - 标题前缀：风险类告警自动加 "[风险告警] "；通用入口（type=SYSTEM）不加，避免语义错乱。
 *
 * @author 反风控改造组
 */
@Component
public class EmailChannel implements IRiskNotifyChannel {

    private static final Logger logger = LoggerFactory.getLogger(EmailChannel.class);

    /**
     * 风险告警标题前缀。
     * 为什么用常量：避免散落字面量；通用类型（SYSTEM）走另一个分支，不加此前缀。
     */
    private static final String SUBJECT_PREFIX_RISK = "[风险告警] ";

    /** SMTP 配置本地缓存 TTL（毫秒） */
    private static final long SMTP_CACHE_TTL_MS = 60_000L;

    @Autowired
    private RiskThresholdConfigService riskCfg;

    @Autowired
    private SysConfigService sysConfigService;

    /**
     * SMTP 配置缓存：避免单次 send 6 次 DB 读。
     * 为什么用 volatile + Map：单 JVM 内的 best-effort 缓存即可，无需引入 Caffeine；60s TTL 满足"改配置后短延迟生效"语义。
     */
    private volatile long smtpCacheTs = 0L;
    private final Map<String, String> smtpCache = new ConcurrentHashMap<>();

    @Override
    public String channelName() {
        return "email";
    }

    @Override
    public boolean isEnabled(RiskAlertType type) {
        if (!riskCfg.getBoolean("notify.email.enabled", false)) {
            return false;
        }
        if (extractEmails(type).isEmpty()
                && StringUtils.isBlank(riskCfg.getString("notify.email.recipients", ""))) {
            // 既无 per-type 配置，也无全局 fallback，认定未启用
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
        msg.setSubject(buildSubject(type, title));
        msg.setText(body);

        // 失败抛 MailException -> 上层 Notifier 捕获并重试
        sender.send(msg);
        return String.join(",", to);
    }

    /**
     * 拼接邮件标题。
     * 为什么按 type 分支：SYSTEM 是通用入口（NotificationService.sendEmail 委托过来的），
     * 不应被强行加 "[风险告警]" 前缀——否则注册通知/对账提醒都会变成风险语义。
     * 其余类型属于明确的风险告警，统一前缀方便运营在邮箱里筛选。
     */
    private String buildSubject(RiskAlertType type, String title) {
        if (type == RiskAlertType.SYSTEM) {
            return title;
        }
        return SUBJECT_PREFIX_RISK + title;
    }

    /**
     * 按配置动态构造 JavaMailSender。
     * 注意：每次 send 都 new 一个 sender 是有意的（SMTP 配置改后立即生效），
     *      性能负担在 getSmtpConfig 的 DB 读上——通过 60s 本地缓存平衡。
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

    /**
     * 读取 SMTP 配置项，带 60s 本地缓存。
     * 为什么不在 SysConfigService 层加：避免影响其他调用方的语义；本类是高频读热点。
     */
    private String getSmtpConfig(String key) {
        long now = System.currentTimeMillis();
        if (now - smtpCacheTs > SMTP_CACHE_TTL_MS) {
            // 过期清空，等待重新加载（best-effort，多线程同时进入只会多读几次）
            smtpCache.clear();
            smtpCacheTs = now;
        }
        return smtpCache.computeIfAbsent(key, k -> {
            com.jeequan.jeepay.core.entity.SysConfig c = sysConfigService.getById(k);
            return c == null ? "" : StringUtils.defaultString(c.getConfigVal());
        });
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
