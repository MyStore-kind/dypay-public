/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.notify;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.core.entity.RiskAlertLog;
import com.jeequan.jeepay.core.entity.SysConfig;
import com.jeequan.jeepay.service.impl.SysConfigService;
import com.jeequan.jeepay.service.mapper.RiskAlertLogMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风险告警分发器（任务 #16 核心入口）
 *
 * 工作流：
 *   1. send(type, ctx) 收到事件
 *   2. 从 t_sys_config 取模板（key=risk_alert_template_{type}），用 ctx 渲染 {{var}}
 *   3. 异步线程池中按订阅渠道分发；任一渠道失败重试 3 次
 *   4. 重试仍失败 -> 落 t_risk_alert_log（state=0）便于人工排查
 *
 * 设计要点：
 *   - 异步：避免阻塞业务主流程
 *   - 配置/网络异常都不向上抛，符合"降级到日志"的要求
 *   - 模板渲染采用 Mustache 风格 {{key}}，运营在后台可任意改文案
 *
 * @author 反风控改造组
 */
@Service
public class RiskAlertNotifier extends ServiceImpl<RiskAlertLogMapper, RiskAlertLog> {

    private static final Logger logger = LoggerFactory.getLogger(RiskAlertNotifier.class);

    /** 最大重试次数（含首次） */
    private static final int MAX_ATTEMPTS = 3;

    /** 占位符正则：{{ var }} 允许两侧空格 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w\\.]+)\\s*}}");

    @Autowired
    private List<IRiskNotifyChannel> channels;

    @Autowired
    private SysConfigService sysConfigService;

    /** 独立线程池：与业务无关，告警慢不会影响支付链路 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "risk-alert-notifier-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * 对外唯一入口：发送告警
     * 注意：不会抛任何异常，配置/网络问题均降级为日志或落 t_risk_alert_log
     *
     * @param type       告警类型
     * @param contextMap 模板变量，例如 {"mchNo":"M001","rate":"0.85%"}
     */
    public void send(RiskAlertType type, Map<String, Object> contextMap) {
        if (type == null) return;

        // 1. 渲染模板（同步完成，避免后续上下文丢失）
        Template tpl = loadTemplate(type);
        final String title = render(tpl.title, contextMap);
        final String body = render(tpl.body, contextMap);

        // 2. 异步分发各渠道
        executor.submit(() -> {
            for (IRiskNotifyChannel ch : channels) {
                if (!safeIsEnabled(ch, type)) {
                    continue;
                }
                dispatchWithRetry(ch, type, title, body);
            }
        });
    }

    /**
     * 单渠道带重试发送
     * 重试间隔采用线性退避（1s/2s），合计最长不超过 3s，避免拖延后续告警
     */
    private void dispatchWithRetry(IRiskNotifyChannel ch, RiskAlertType type, String title, String body) {
        Exception lastError = null;
        String delivered = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                delivered = ch.send(type, title, body);
                // 成功：写一条成功日志便于运营追溯
                writeLog(type, ch.channelName(), delivered, title, body,
                        RiskAlertLog.STATE_SUCCESS, attempt, null);
                return;
            } catch (Exception e) {
                lastError = e;
                logger.warn("[RiskAlertNotifier] 第 {} 次发送失败 channel={} type={} msg={}",
                        attempt, ch.channelName(), type, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // 3 次仍失败：落库 + 错误日志
        String errMsg = lastError == null ? "unknown" : StringUtils.substring(lastError.getMessage(), 0, 900);
        writeLog(type, ch.channelName(), delivered, title, body,
                RiskAlertLog.STATE_FAIL, MAX_ATTEMPTS, errMsg);
        logger.error("[RiskAlertNotifier] 告警重试 {} 次仍失败 channel={} type={}",
                MAX_ATTEMPTS, ch.channelName(), type, lastError);
    }

    /** 写日志（吞掉异常，确保不阻塞告警链路） */
    private void writeLog(RiskAlertType type, String channel, String target,
                          String title, String body,
                          byte state, int retryCount, String errMsg) {
        try {
            RiskAlertLog log = new RiskAlertLog()
                    .setAlertType(type.name())
                    .setChannel(channel)
                    .setTarget(StringUtils.substring(target, 0, 250))
                    .setTitle(StringUtils.substring(title, 0, 250))
                    .setContent(body)
                    .setState(state)
                    .setRetryCount(retryCount)
                    .setErrorMsg(errMsg);
            save(log);
        } catch (Exception ex) {
            logger.error("[RiskAlertNotifier] 写告警日志失败（已忽略）", ex);
        }
    }

    /**
     * 加载模板：t_sys_config 的 config_val 存 JSON {"title":"...","body":"..."}
     * 缺失时使用兜底文案，保证告警永不丢失
     */
    private Template loadTemplate(RiskAlertType type) {
        String key = "risk_alert_template_" + type.configSuffix();
        try {
            SysConfig c = sysConfigService.getById(key);
            if (c != null && StringUtils.isNotBlank(c.getConfigVal())) {
                JSONObject json = JSONObject.parseObject(c.getConfigVal());
                return new Template(
                        StringUtils.defaultIfBlank(json.getString("title"), type.getDisplayName()),
                        StringUtils.defaultIfBlank(json.getString("body"), defaultBody(type)));
            }
        } catch (Exception e) {
            logger.warn("[RiskAlertNotifier] 模板解析失败，使用默认：type={} err={}", type, e.getMessage());
        }
        return new Template(type.getDisplayName(), defaultBody(type));
    }

    /** 默认正文：将所有上下文 key 平铺，方便排查 */
    private String defaultBody(RiskAlertType type) {
        return type.getDisplayName() + "\n详情：\n{{detail}}";
    }

    /** 简易模板渲染：{{key}} -> contextMap.get("key")，缺失保留原字面量便于排查 */
    private String render(String tpl, Map<String, Object> ctx) {
        if (StringUtils.isBlank(tpl)) return "";
        Map<String, Object> safe = ctx == null ? java.util.Collections.emptyMap() : ctx;
        Matcher m = PLACEHOLDER.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = safe.get(m.group(1));
            if (v == null && "detail".equals(m.group(1))) {
                // detail 占位符自动展开整个 context 便于兜底
                v = safe.toString();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 渠道 isEnabled 也不能让它把整个分发流程搞挂 */
    private boolean safeIsEnabled(IRiskNotifyChannel ch, RiskAlertType type) {
        try {
            return ch.isEnabled(type);
        } catch (Exception e) {
            logger.warn("[RiskAlertNotifier] 渠道 isEnabled 异常 channel={} err={}",
                    ch.channelName(), e.getMessage());
            return false;
        }
    }

    /** 内部模板载体 */
    private static class Template {
        final String title;
        final String body;
        Template(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }
}
