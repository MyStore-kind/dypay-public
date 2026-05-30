/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.ChargebackRecord;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.service.mapper.ChargebackRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private OrderRiskRecordService orderRiskRecordService;

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
}
