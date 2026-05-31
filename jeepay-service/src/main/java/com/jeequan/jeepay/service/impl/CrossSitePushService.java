/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.entity.CrossSiteClient;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.service.mapper.CrossSiteClientMapper;
import com.jeequan.jeepay.service.mapper.CrossSitePushRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;

/**
 * A/B 站联动服务（P2）
 *
 * 业务模式（按需求）：
 *   B 站：商户方（客户在 B 下单）
 *   A 站：收款方（本系统）
 *   B → A 推送：order_id / ip / device_fingerprint / amount / currency / ts / nonce / sign
 *
 * 安全：
 *   - HMAC-SHA256 验签（client_secret 颁发给 B）
 *   - 时间戳偏移 ≤ 5 分钟（防回放）
 *   - nonce 在 Redis 中 10 分钟去重（防回放）
 *   - IP 白名单（可选）
 *   - UNIQUE(client_id, order_id) DB 层兜底幂等
 *
 * 签名算法：
 *   按字段名 ASCII 升序 → key=value 用 & 连接 → 末尾追加 &key={client_secret}
 *   字段集合：client_id, order_id, amount, currency, ip, device_fingerprint, ts, nonce
 *   sign = HMAC-SHA256(secret, signString).hex().toLowerCase()
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Service
public class CrossSitePushService extends ServiceImpl<CrossSitePushRecordMapper, CrossSitePushRecord> {

    private static final Logger logger = LoggerFactory.getLogger(CrossSitePushService.class);

    /** 时间戳偏移容忍（毫秒）。5 分钟。 */
    private static final long TS_TOLERANCE_MS = 5L * 60 * 1000;
    /** nonce 去重 TTL（秒）。10 分钟，必须大于 TS_TOLERANCE_MS 对应秒数。 */
    private static final long NONCE_TTL_SECONDS = 10L * 60;
    /** Redis nonce key 前缀 */
    private static final String NONCE_KEY = "crosssite:nonce:";

    @Autowired private CrossSiteClientMapper clientMapper;

    /** 参与签名的字段（按 ASCII 升序） */
    private static final List<String> SIGN_FIELDS = Arrays.asList(
            "amount", "client_id", "currency", "device_fingerprint",
            "ip", "nonce", "order_id", "ts");

    /** 创单接口的签名字段（不含 ip/指纹，因为创单时不需要） */
    private static final List<String> CREATE_SIGN_FIELDS = Arrays.asList(
            "amount", "client_id", "currency", "nonce",
            "notify_url", "order_id", "return_url", "subject", "ts");

    /** 收银台 token 失效时间：30 分钟 */
    private static final long PAY_TOKEN_TTL_MS = 30L * 60 * 1000;
    private static final SecureRandom RNG = new SecureRandom();

    // ============================================
    // 方案 E：方法 1 — B 站后端创单（推荐主流程）
    // ============================================

    /**
     * B 站后端调用本接口创建一笔跨站订单
     * 返回 pay_url，B 把客户重定向过来；指纹在 A 站收银台采集
     *
     * @return 已落库的记录（含 pay_token）
     */
    @Transactional(rollbackFor = Exception.class)
    public CrossSitePushRecord createOrder(JSONObject payload, String clientIp) {
        String clientId = payload.getString("client_id");
        String orderId = payload.getString("order_id");
        Long amount = payload.getLong("amount");
        String currency = payload.getString("currency");
        Long ts = payload.getLong("ts");
        String nonce = payload.getString("nonce");
        String sign = payload.getString("sign");

        CrossSitePushRecord rec = new CrossSitePushRecord()
                .setClientId(clientId)
                .setOrderId(orderId)
                .setAmount(amount == null ? 0 : amount)
                .setCurrency(currency == null ? "USD" : currency)
                .setNonce(nonce)
                .setTs(ts == null ? 0 : ts)
                .setSign(sign)
                .setReturnUrl(payload.getString("return_url"))
                .setNotifyUrl(payload.getString("notify_url"))
                .setSubject(payload.getString("subject"))
                .setCustomerEmail(payload.getString("customer_email"))
                .setRawPayload(payload.toJSONString())
                .setState(CrossSitePushRecord.STATE_RECEIVED);

        // 1. 校验必填
        if (clientId == null || orderId == null || amount == null || amount <= 0
                || ts == null || nonce == null || sign == null) {
            return reject(rec, "缺少必填字段或金额非法");
        }

        // 2. 时间戳偏移
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > TS_TOLERANCE_MS) {
            return reject(rec, "时间戳偏移过大（>5min）");
        }

        // 3. 客户端 + IP 白名单
        CrossSiteClient client = clientMapper.selectById(clientId);
        if (client == null || client.getEnabled() == null || client.getEnabled() != 1) {
            return reject(rec, "客户端不存在或未启用");
        }
        if (!ipAllowed(client.getIpWhitelist(), clientIp)) {
            return reject(rec, "调用方 IP 不在白名单：" + clientIp);
        }

        // 4. 验签（创单字段集与推送字段集不同）
        String expected = signWith(CREATE_SIGN_FIELDS, payload, client.getClientSecret());
        if (!expected.equalsIgnoreCase(sign)) {
            logger.error("[CrossSite#create] 验签失败 clientId={} orderId={}", clientId, orderId);
            return reject(rec, "签名校验失败");
        }

        // 5. nonce 防重放
        String nonceKey = NONCE_KEY + clientId + ":" + nonce;
        try {
            if (RedisUtil.hasKey(nonceKey)) {
                return reject(rec, "nonce 已被使用，可能为重放攻击");
            }
            RedisUtil.setString(nonceKey, "1", NONCE_TTL_SECONDS);
        } catch (Exception e) {
            logger.warn("[CrossSite#create] nonce 校验降级（Redis 异常）clientId={}", clientId, e);
        }

        // 6. 生成 pay_token + 过期时间
        rec.setPayToken(generatePayToken());
        rec.setExpireAt(new Date(now + PAY_TOKEN_TTL_MS));
        rec.setState(CrossSitePushRecord.STATE_VERIFIED);

        // 7. 落库
        try {
            save(rec);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 同 client+order 已存在 → 返回已有记录（幂等）
            CrossSitePushRecord existed = getOne(CrossSitePushRecord.gw()
                    .eq(CrossSitePushRecord::getClientId, clientId)
                    .eq(CrossSitePushRecord::getOrderId, orderId)
                    .last("LIMIT 1"), false);
            if (existed != null) return existed;
            throw dup;
        }

        logger.info("[CrossSite#create] 创单成功 clientId={} orderId={} payToken={} amount={}",
                clientId, orderId, rec.getPayToken(), amount);
        return rec;
    }

    /**
     * 收银台加载：根据 pay_token 取记录
     * 校验：状态有效 + 未过期
     */
    public CrossSitePushRecord loadByPayToken(String payToken) {
        if (payToken == null || payToken.isEmpty()) return null;
        CrossSitePushRecord rec = getOne(CrossSitePushRecord.gw()
                .eq(CrossSitePushRecord::getPayToken, payToken).last("LIMIT 1"), false);
        if (rec == null) return null;
        // 过期检查
        if (rec.getExpireAt() != null && rec.getExpireAt().before(new Date())
                && !CrossSitePushRecord.STATE_PAID.equals(rec.getState())) {
            if (!CrossSitePushRecord.STATE_EXPIRED.equals(rec.getState())) {
                rec.setState(CrossSitePushRecord.STATE_EXPIRED);
                try { updateById(rec); } catch (Exception ignored) {}
            }
        }
        return rec;
    }

    /**
     * 收银台前端回传指纹
     * 落库 + 调用风控钩子（如有），更新决策
     */
    @Transactional(rollbackFor = Exception.class)
    public CrossSitePushRecord collectFingerprint(String payToken, String ip,
                                                  String userAgent, JSONObject fingerprint) {
        CrossSitePushRecord rec = loadByPayToken(payToken);
        if (rec == null) return null;
        if (CrossSitePushRecord.STATE_EXPIRED.equals(rec.getState())) return rec;

        rec.setIp(ip);
        rec.setUserAgent(userAgent);
        rec.setBrowserFingerprint(fingerprint == null ? "{}" : fingerprint.toJSONString());
        rec.setCollectedAt(new Date());

        // 简单评分：可后续接 OrderRiskHookService 做更细致评估
        int score = quickRiskScore(rec, fingerprint);
        String decision;
        if (score >= 80) decision = CrossSitePushRecord.DECISION_REJECT;
        else if (score >= 50) decision = CrossSitePushRecord.DECISION_3DS;
        else decision = CrossSitePushRecord.DECISION_PASS;

        rec.setRiskScoreSnapshot(score);
        rec.setRiskDecision(decision);
        if (CrossSitePushRecord.STATE_VERIFIED.equals(rec.getState())) {
            rec.setState(CrossSitePushRecord.STATE_AWAITING_PAY);
        }
        updateById(rec);

        logger.info("[CrossSite#collect] payToken={} ip={} score={} decision={}",
                payToken, ip, score, decision);
        return rec;
    }

    /**
     * 简单评分（占位实现）：
     * - 无指纹 +30
     * - IP 与 B 站推送时 ts 时差大 +10
     * - UA 是常见 bot +50
     * 真实风控逻辑后续可注入 OrderRiskHookService
     */
    private int quickRiskScore(CrossSitePushRecord rec, JSONObject fp) {
        int score = 0;
        if (fp == null || fp.isEmpty()) score += 30;
        else {
            if (fp.getString("canvas") == null) score += 10;
            if (fp.getString("webgl") == null) score += 10;
            if (fp.getString("timezone") == null) score += 5;
        }
        String ua = rec.getUserAgent() == null ? "" : rec.getUserAgent().toLowerCase();
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")
                || ua.contains("headless")) score += 50;
        return Math.min(score, 100);
    }

    // ============================================
    // 方案 A（保留）：B 站推送已含 IP/指纹
    // ============================================

    /**
     * 接收 B 站推送
     * @param payload 完整请求体 JSON
     * @param clientIp 调用方 IP（A 站从 HttpServletRequest 取）
     * @return 已落库的流水记录
     */
    @Transactional(rollbackFor = Exception.class)
    public CrossSitePushRecord receive(JSONObject payload, String clientIp) {
        // ===== 1. 参数校验 =====
        String clientId = payload.getString("client_id");
        String orderId = payload.getString("order_id");
        Long amount = payload.getLong("amount");
        String currency = payload.getString("currency");
        Long ts = payload.getLong("ts");
        String nonce = payload.getString("nonce");
        String sign = payload.getString("sign");

        CrossSitePushRecord rec = new CrossSitePushRecord()
                .setClientId(clientId)
                .setOrderId(orderId)
                .setAmount(amount == null ? 0 : amount)
                .setCurrency(currency == null ? "USD" : currency)
                .setIp(payload.getString("ip"))
                .setDeviceFingerprint(payload.getString("device_fingerprint"))
                .setUserAgent(payload.getString("user_agent"))
                .setNonce(nonce)
                .setTs(ts == null ? 0 : ts)
                .setSign(sign)
                .setRawPayload(payload.toJSONString())
                .setState(CrossSitePushRecord.STATE_RECEIVED);

        // 必填字段
        if (clientId == null || orderId == null || amount == null
                || ts == null || nonce == null || sign == null) {
            return reject(rec, "缺少必填字段（client_id/order_id/amount/ts/nonce/sign）");
        }

        // ===== 2. 时间戳偏移 =====
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > TS_TOLERANCE_MS) {
            return reject(rec, "时间戳偏移过大（>5min）");
        }

        // ===== 3. 客户端凭据 =====
        CrossSiteClient client = clientMapper.selectById(clientId);
        if (client == null || client.getEnabled() == null || client.getEnabled() != 1) {
            return reject(rec, "客户端不存在或未启用");
        }
        // IP 白名单（可选）
        if (!ipAllowed(client.getIpWhitelist(), clientIp)) {
            return reject(rec, "调用方 IP 不在白名单：" + clientIp);
        }

        // ===== 4. 验签 =====
        String expected = sign(payload, client.getClientSecret());
        if (!expected.equalsIgnoreCase(sign)) {
            logger.error("[CrossSite] 验签失败 clientId={} orderId={} expected={} got={}",
                    clientId, orderId, expected, sign);
            return reject(rec, "签名校验失败");
        }

        // ===== 5. nonce 去重（Redis） =====
        String nonceKey = NONCE_KEY + clientId + ":" + nonce;
        try {
            if (RedisUtil.hasKey(nonceKey)) {
                return reject(rec, "nonce 已被使用，可能为重放攻击");
            }
            RedisUtil.setString(nonceKey, "1", NONCE_TTL_SECONDS);
        } catch (Exception e) {
            // Redis 失败不应阻塞业务，但记 warn
            logger.warn("[CrossSite] nonce 校验降级（Redis 异常）clientId={}", clientId, e);
        }

        // ===== 6. 落库（DB UNIQUE 兜底幂等） =====
        rec.setState(CrossSitePushRecord.STATE_VERIFIED);
        try {
            save(rec);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 同 client+order 已存在 → 直接返回已有的，幂等
            CrossSitePushRecord existed = getOne(CrossSitePushRecord.gw()
                    .eq(CrossSitePushRecord::getClientId, clientId)
                    .eq(CrossSitePushRecord::getOrderId, orderId)
                    .last("LIMIT 1"), false);
            if (existed != null) return existed;
            throw dup;
        }

        logger.info("[CrossSite] 接收成功 clientId={} orderId={} amount={} ip={} fp={}",
                clientId, orderId, amount, rec.getIp(), rec.getDeviceFingerprint());
        return rec;
    }

    /**
     * 计算签名（同算法可供 B 站参考）
     *
     * 算法：
     * 1. 把参与签名字段按字段名 ASCII 升序排列
     * 2. 拼成 k1=v1&k2=v2&...
     * 3. HMAC-SHA256(secret, signString) → hex 小写
     *
     * 注意：value 为 null 的字段跳过（不参与签名）
     */
    public static String sign(JSONObject payload, String secret) {
        return signWith(SIGN_FIELDS, payload, secret);
    }

    /** 创单接口签名（不含 ip/指纹） */
    public static String signForCreate(JSONObject payload, String secret) {
        return signWith(CREATE_SIGN_FIELDS, payload, secret);
    }

    /** 内部：按指定字段集合签名 */
    private static String signWith(List<String> fields, JSONObject payload, String secret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String f : fields) {
            Object v = payload.get(f);
            if (v != null) sorted.put(f, String.valueOf(v));
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return UpstreamCallbackService.hmacSha256Hex(secret, sb.toString());
    }

    /** IP 白名单匹配；列表为空时放行 */
    private static boolean ipAllowed(String whitelist, String clientIp) {
        if (whitelist == null || whitelist.isEmpty() || clientIp == null) return true;
        for (String ip : whitelist.split(",")) {
            if (ip.trim().equals(clientIp)) return true;
        }
        return false;
    }

    /** 32 字节随机 → URL-safe base64 → 截 32 字符 */
    private static String generatePayToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private CrossSitePushRecord reject(CrossSitePushRecord rec, String reason) {
        rec.setState(CrossSitePushRecord.STATE_REJECTED);
        rec.setRejectReason(reason);
        try { save(rec); } catch (Exception ignored) {}
        logger.warn("[CrossSite] 推送被拒 clientId={} orderId={} reason={}",
                rec.getClientId(), rec.getOrderId(), reason);
        return rec;
    }
}
