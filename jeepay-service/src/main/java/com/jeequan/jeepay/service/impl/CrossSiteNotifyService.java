/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.CrossSiteClient;
import com.jeequan.jeepay.core.entity.CrossSiteNotifyRecord;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.service.mapper.CrossSiteClientMapper;
import com.jeequan.jeepay.service.mapper.CrossSiteNotifyRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Date;
import java.util.List;

/**
 * 跨站异步通知服务（b-1）
 *
 * 业务流：
 *   1. 订单状态变化（paid / failed / expired / refunded）调用 enqueue() 入队
 *   2. 调度器定时扫 state=1 且 next_notify_time<=NOW() 的记录
 *   3. POST 到 B 站 notify_url（带 HMAC 签名）
 *   4. B 站返回 "success" 文本 → 标记成功
 *   5. 否则按 BACKOFF_SECONDS 排下次重试时间
 *
 * 设计要点：
 *   - 通知内容（含签名）在 enqueue 时就计算并存储，避免重试时 ts 漂移导致签名失效
 *   - okhttp 默认超时 10s，B 站 hang 不应阻塞调度器
 *   - HTTP 200 + body="success" 才算成功，对齐 JeePay 商户通知约定
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Service
public class CrossSiteNotifyService extends ServiceImpl<CrossSiteNotifyRecordMapper, CrossSiteNotifyRecord> {

    private static final Logger logger = LoggerFactory.getLogger(CrossSiteNotifyService.class);

    /** 复用一个 RestTemplate；超时 connect 5s / read 10s */
    private final RestTemplate restTemplate;

    public CrossSiteNotifyService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(f);
    }

    @Autowired private CrossSiteClientMapper clientMapper;

    /**
     * 入队（订单终态时调用）
     * 同一 push_record + event 已存在则不重复入队
     */
    @Transactional(rollbackFor = Exception.class)
    public CrossSiteNotifyRecord enqueue(CrossSitePushRecord push, String eventType) {
        if (push == null || push.getNotifyUrl() == null || push.getNotifyUrl().isEmpty()) {
            logger.info("[CrossSiteNotify] 跳过：无 notify_url pushId={}",
                    push == null ? null : push.getId());
            return null;
        }
        // 幂等
        CrossSiteNotifyRecord existed = getOne(CrossSiteNotifyRecord.gw()
                .eq(CrossSiteNotifyRecord::getPushRecordId, push.getId())
                .eq(CrossSiteNotifyRecord::getEventType, eventType)
                .last("LIMIT 1"), false);
        if (existed != null) {
            logger.info("[CrossSiteNotify] 已入队过 pushId={} event={}", push.getId(), eventType);
            return existed;
        }

        CrossSiteClient client = clientMapper.selectById(push.getClientId());
        if (client == null) {
            logger.error("[CrossSiteNotify] 客户端缺失，无法签名 clientId={}", push.getClientId());
            return null;
        }

        // 构造通知 payload（含签名）
        String payload = buildSignedPayload(push, eventType, client.getClientSecret());

        CrossSiteNotifyRecord rec = new CrossSiteNotifyRecord()
                .setPushRecordId(push.getId())
                .setClientId(push.getClientId())
                .setOrderId(push.getOrderId())
                .setNotifyUrl(push.getNotifyUrl())
                .setPayload(payload)
                .setEventType(eventType)
                .setNotifyCount(0)
                .setNotifyCountLimit(CrossSiteNotifyRecord.BACKOFF_SECONDS.length)
                .setNextNotifyTime(new Date())  // 立即触发首次
                .setState(CrossSiteNotifyRecord.STATE_ING);
        save(rec);
        logger.info("[CrossSiteNotify] 入队 pushId={} event={} recordId={}",
                push.getId(), eventType, rec.getId());
        return rec;
    }

    /**
     * 调度器调用：取一批待通知记录
     */
    public List<CrossSiteNotifyRecord> fetchDuePending(int batchSize) {
        return list(CrossSiteNotifyRecord.gw()
                .eq(CrossSiteNotifyRecord::getState, CrossSiteNotifyRecord.STATE_ING)
                .le(CrossSiteNotifyRecord::getNextNotifyTime, new Date())
                .orderByAsc(CrossSiteNotifyRecord::getNextNotifyTime)
                .last("LIMIT " + Math.max(1, batchSize)));
    }

    /**
     * 单条投递：POST notify_url，按结果更新状态与下次重试时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendOne(CrossSiteNotifyRecord rec) {
        int attempt = rec.getNotifyCount() == null ? 0 : rec.getNotifyCount();
        String resp = null; Integer code = null; boolean ok = false;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-DYPAY-Event", rec.getEventType());
        headers.set("X-DYPAY-Attempt", String.valueOf(attempt + 1));
        HttpEntity<String> req = new HttpEntity<>(rec.getPayload(), headers);
        try {
            ResponseEntity<String> r = restTemplate.postForEntity(rec.getNotifyUrl(), req, String.class);
            code = r.getStatusCodeValue();
            resp = r.getBody() == null ? "" : r.getBody();
            if (resp.length() > 2048) resp = resp.substring(0, 2048);
            ok = r.getStatusCode().is2xxSuccessful() && "success".equalsIgnoreCase(resp.trim());
        } catch (Exception e) {
            resp = "[exception] " + e.getMessage();
            if (resp != null && resp.length() > 2048) resp = resp.substring(0, 2048);
            logger.warn("[CrossSiteNotify] 投递异常 recordId={} attempt={} err={}",
                    rec.getId(), attempt + 1, e.getMessage());
        }

        rec.setNotifyCount(attempt + 1);
        rec.setLastNotifyTime(new Date());
        rec.setLastResponse(resp);
        rec.setLastHttpCode(code);

        if (ok) {
            rec.setState(CrossSiteNotifyRecord.STATE_SUCCESS);
            rec.setNextNotifyTime(null);
            logger.info("[CrossSiteNotify] 投递成功 recordId={} attempt={}", rec.getId(), attempt + 1);
        } else if (attempt + 1 >= rec.getNotifyCountLimit()) {
            rec.setState(CrossSiteNotifyRecord.STATE_FAIL);
            rec.setNextNotifyTime(null);
            logger.warn("[CrossSiteNotify] 投递最终失败 recordId={} code={}", rec.getId(), code);
        } else {
            // 排下一次
            int delay = CrossSiteNotifyRecord.BACKOFF_SECONDS[attempt + 1 - 1];
            rec.setNextNotifyTime(new Date(System.currentTimeMillis() + delay * 1000L));
            logger.info("[CrossSiteNotify] 投递失败将重试 recordId={} attempt={} nextIn={}s",
                    rec.getId(), attempt + 1, delay);
        }
        updateById(rec);
    }

    // ============================================
    // 构造签名 payload（与 §3.2 文档约定一致）
    // 字段：client_id, order_id, pay_order_id, state, amount, currency, ts
    // ============================================
    private String buildSignedPayload(CrossSitePushRecord push, String eventType, String secret) {
        JSONObject p = new JSONObject(true); // 保留插入顺序
        p.put("client_id", push.getClientId());
        p.put("order_id", push.getOrderId());
        p.put("pay_order_id", push.getPayOrderId());
        p.put("event", eventType);
        p.put("state", push.getState());
        p.put("amount", push.getAmount());
        p.put("currency", push.getCurrency());
        p.put("ts", System.currentTimeMillis());

        // 签名字段集合：与字段集一致（除了 sign 自己）
        String[] fields = {"amount","client_id","currency","event","order_id","pay_order_id","state","ts"};
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (String f : fields) {
            Object v = p.get(f);
            if (v != null) sorted.put(f, String.valueOf(v));
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        p.put("sign", UpstreamCallbackService.hmacSha256Hex(secret, sb.toString()));
        return p.toJSONString();
    }
}
