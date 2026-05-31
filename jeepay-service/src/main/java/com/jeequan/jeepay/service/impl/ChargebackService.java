/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.ChargebackRecord;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.service.mapper.ChargebackRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 拒付管理服务
 *
 * 核心职责：
 * 1. 接收通道方拒付通知（Webhook 已解析）
 * 2. 从订单数据中自动提取证据快照
 * 3. 跟踪申诉状态（受理/已应诉/胜诉/败诉）
 * 4. 为运营人员提供数据，不自动决策
 *
 * 注意：拒付证据收集是申诉成功率的关键
 *
 * @author 反风控改造组
 */
@Service
public class ChargebackService extends ServiceImpl<ChargebackRecordMapper, ChargebackRecord> {

    private static final Logger logger = LoggerFactory.getLogger(ChargebackService.class);

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private OrderRiskRecordService orderRiskRecordService;

    /**
     * 拒付惩罚扣款引擎（P0 地基）
     * 注意：本字段以 @Autowired(required=false) 注入是为了让旧测试 / 旧上下文
     * 在未引入扣款引擎时仍能加载本服务。生产环境一定要有这个 Bean。
     */
    @Autowired(required = false)
    private ChargebackPenaltyService chargebackPenaltyService;

    /**
     * 接收拒付通知（由通道 Webhook 调用）
     * 自动从订单与风控记录中收集证据快照
     *
     * 注意：此方法不修改原订单状态，仅创建拒付记录
     * 退款/资金冻结由后续流程处理
     */
    @Transactional(rollbackFor = Exception.class)
    public ChargebackRecord receiveChargeback(ChargebackRecord chargeback) {
        // 1. 关联原订单
        PayOrder payOrder = payOrderService.getById(chargeback.getPayOrderId());
        if (payOrder == null) {
            // 订单不存在，仍然保存拒付记录（供人工排查）
            chargeback.setRemark("原订单未找到，需人工核对");
            save(chargeback);
            return chargeback;
        }

        // 2. 从订单风控记录中提取证据快照
        orderRiskRecordService.findByPayOrderId(chargeback.getPayOrderId())
                .ifPresent(risk -> {
                    chargeback.setCustomerIp(risk.getIp());
                    chargeback.setCustomerEmail(risk.getBuyerEmail());
                    chargeback.setCustomerName(risk.getBuyerName());
                });

        // 3. 默认状态：已受理
        chargeback.setState(ChargebackRecord.STATE_RECEIVED);

        save(chargeback);

        // 4. 触发拒付惩罚扣款（P0 地基）
        // 设计：扣款引擎用 REQUIRES_NEW 独立事务，失败仅记日志，绝不影响本拒付落库
        if (chargebackPenaltyService != null) {
            try {
                chargebackPenaltyService.applyPenalty(chargeback);
            } catch (Exception e) {
                // 双重兜底：引擎内部已经 catch；这里再兜一层保证主流程不抛
                logger.error("[Chargeback] 拒付惩罚扣款失败 chargebackId={}", chargeback.getId(), e);
            }
        }
        return chargeback;
    }

    /**
     * 标记证据已提交
     */
    public void markEvidenceSubmitted(Long recordId, String submittedBy) {
        ChargebackRecord r = getById(recordId);
        if (r == null) return;
        r.setState(ChargebackRecord.STATE_RESPONDED);
        r.setEvidenceSubmittedAt(new Date());
        r.setRemark((r.getRemark() == null ? "" : r.getRemark() + " | ") + "证据已提交 by " + submittedBy);
        updateById(r);
    }

    /**
     * 标记最终结果（胜诉/败诉）
     */
    public void markResolved(Long recordId, String result) {
        ChargebackRecord r = getById(recordId);
        if (r == null) return;
        r.setState(result);
        r.setResolvedAt(new Date());
        updateById(r);
    }

    /**
     * 查询即将超时的拒付（用于提醒运营）
     * 返回数据由调用方判断如何展示，本方法不做决策
     */
    public List<ChargebackRecord> listExpiringSoon(int hoursAhead) {
        Date deadline = new Date(System.currentTimeMillis() + hoursAhead * 3600_000L);
        return list(ChargebackRecord.gw()
                .eq(ChargebackRecord::getState, ChargebackRecord.STATE_RECEIVED)
                .le(ChargebackRecord::getEvidenceDueAt, deadline)
                .orderByAsc(ChargebackRecord::getEvidenceDueAt));
    }

    /**
     * 通道方拒付事件统一入口（由 Stripe / PayPal Webhook 调用）
     *
     * 设计思路：
     * - 不让每个通道各自拼装 ChargebackRecord，统一在此处解析通道事件
     * - 自动幂等：根据 channel_chargebackId 去重，避免重复入库
     * - 不抛异常：拒付通知必须立即落库，签名/解析失败也只记日志
     *
     * @param channel 通道编码（stripe / paypal）
     * @param payload 通道侧 webhook 原始 JSON
     * @return 已落库的拒付记录（可能为 null：无法识别的事件）
     */
    @Transactional(rollbackFor = Exception.class)
    public ChargebackRecord onChargebackEvent(String channel, JSONObject payload) {
        if (payload == null) return null;
        try {
            ChargebackRecord rec = parseChargeback(channel, payload);
            if (rec == null) {
                logger.warn("[Chargeback] 无法解析事件 channel={}, payload={}", channel, payload.toJSONString());
                return null;
            }

            // 幂等：相同的 channel_chargebackId 已存在则更新状态而非新建
            // 为什么：通道方可能多次推送同一拒付的更新（如 dispute.updated）
            ChargebackRecord exists = getOne(ChargebackRecord.gw()
                    .eq(ChargebackRecord::getChannelChargebackId, rec.getChannelChargebackId())
                    .eq(ChargebackRecord::getIfCode, channel)
                    .last("LIMIT 1"), false);
            if (exists != null) {
                exists.setChargebackReasonCode(rec.getChargebackReasonCode());
                exists.setChargebackReasonDesc(rec.getChargebackReasonDesc());
                // 通道方推送了最终结果时同步状态（如 funds_withdrawn -> lost, won）
                if (rec.getState() != null) {
                    exists.setState(rec.getState());
                    if (ChargebackRecord.STATE_WON.equals(rec.getState())
                            || ChargebackRecord.STATE_LOST.equals(rec.getState())) {
                        exists.setResolvedAt(new Date());
                    }
                }
                updateById(exists);
                return exists;
            }

            return receiveChargeback(rec);
        } catch (Exception e) {
            // 不抛异常：避免 Webhook 处理失败导致通道方重试风暴
            logger.error("[Chargeback] 处理拒付事件异常 channel={}", channel, e);
            return null;
        }
    }

    /**
     * 解析通道侧事件 -> ChargebackRecord
     * 注意：每个通道字段路径不同，需分别适配
     */
    private ChargebackRecord parseChargeback(String channel, JSONObject payload) {
        ChargebackRecord rec = new ChargebackRecord();
        rec.setIfCode(channel);

        if ("stripe".equals(channel)) {
            // Stripe charge.dispute.* 事件结构：data.object 为 Dispute / EarlyFraudWarning
            JSONObject data = payload.getJSONObject("data");
            JSONObject obj = data == null ? null : data.getJSONObject("object");
            if (obj == null) return null;

            rec.setChannelChargebackId(obj.getString("id"));
            // dispute 上有 amount/currency/reason；early_fraud_warning 没有 amount
            Long amount = obj.getLong("amount");
            if (amount != null) rec.setChargebackAmount(amount);
            rec.setChargebackCurrency(obj.getString("currency"));
            rec.setChargebackReasonCode(obj.getString("reason"));
            rec.setChargebackReasonDesc(obj.getString("reason"));
            rec.setChargebackType(payload.getString("type"));

            // metadata.jeepay_order_id 在 dispute 自身的 metadata 里没有，需通过 charge.metadata 反查
            JSONObject metadata = obj.getJSONObject("metadata");
            if (metadata != null && metadata.containsKey("jeepay_order_id")) {
                rec.setPayOrderId(metadata.getString("jeepay_order_id"));
            } else {
                // 通过 charge / payment_intent 反查
                String chargeId = obj.getString("charge");
                String paymentIntentId = obj.getString("payment_intent");
                if (chargeId != null || paymentIntentId != null) {
                    PayOrder po = payOrderService.getOne(PayOrder.gw()
                            .eq(PayOrder::getChannelOrderNo, paymentIntentId != null ? paymentIntentId : chargeId)
                            .last("LIMIT 1"), false);
                    if (po != null) {
                        rec.setPayOrderId(po.getPayOrderId());
                        rec.setMchNo(po.getMchNo());
                    }
                }
            }

            // funds_withdrawn 表示资金已扣回，记为败诉
            if ("charge.dispute.funds_withdrawn".equals(payload.getString("type"))) {
                rec.setState(ChargebackRecord.STATE_LOST);
            }
            // dispute.updated 中 status=won/lost 时回写
            String dispStatus = obj.getString("status");
            if ("won".equals(dispStatus)) rec.setState(ChargebackRecord.STATE_WON);
            else if ("lost".equals(dispStatus)) rec.setState(ChargebackRecord.STATE_LOST);

            // 证据截止时间
            Long dueBy = obj.getJSONObject("evidence_details") == null ? null
                    : obj.getJSONObject("evidence_details").getLong("due_by");
            if (dueBy != null) rec.setEvidenceDueAt(new Date(dueBy * 1000L));

        } else if ("paypal".equals(channel)) {
            // PayPal CUSTOMER.DISPUTE.* 事件
            JSONObject resource = payload.getJSONObject("resource");
            if (resource == null) return null;

            rec.setChannelChargebackId(resource.getString("dispute_id"));
            JSONObject amount = resource.getJSONObject("dispute_amount");
            if (amount != null) {
                rec.setChargebackCurrency(amount.getString("currency_code"));
                String value = amount.getString("value");
                if (value != null) {
                    // PayPal 主单位 -> 分
                    rec.setChargebackAmount(new BigDecimal(value).multiply(new BigDecimal(100)).longValue());
                }
            }
            rec.setChargebackReasonCode(resource.getString("reason"));
            rec.setChargebackReasonDesc(resource.getString("reason"));
            rec.setChargebackType(payload.getString("event_type"));

            // PayPal 通过 disputed_transactions[0].custom 找 jeepay_order_id
            try {
                JSONObject tx0 = resource.getJSONArray("disputed_transactions").getJSONObject(0);
                String customId = tx0.getString("custom");
                if (customId == null) customId = tx0.getString("seller_transaction_id");
                if (customId != null) rec.setPayOrderId(customId);
            } catch (Exception ignored) {}

            // 状态映射
            String status = resource.getString("status");
            if ("RESOLVED".equalsIgnoreCase(status)) {
                String outcome = resource.getString("dispute_outcome") == null ? "" :
                        resource.getJSONObject("dispute_outcome").getString("outcome_code");
                if ("RESOLVED_BUYER_FAVOUR".equalsIgnoreCase(outcome)) {
                    rec.setState(ChargebackRecord.STATE_LOST);
                } else if ("RESOLVED_SELLER_FAVOUR".equalsIgnoreCase(outcome)) {
                    rec.setState(ChargebackRecord.STATE_WON);
                }
            }

            // 证据截止时间
            String sellerResponseDueDate = resource.getString("seller_response_due_date");
            if (sellerResponseDueDate != null) {
                try {
                    // PayPal 时间格式 ISO 8601
                    rec.setEvidenceDueAt(new Date(java.time.Instant.parse(sellerResponseDueDate).toEpochMilli()));
                } catch (Exception ignored) {}
            }
        } else {
            return null;
        }

        return rec;
    }
}
