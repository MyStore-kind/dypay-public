/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.OrderRiskRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 订单风控记录 Mapper
 *
 * @author 反风控改造组
 */
public interface OrderRiskRecordMapper extends BaseMapper<OrderRiskRecord> {

    /**
     * 按支付订单号查询风控记录（唯一）
     */
    OrderRiskRecord selectByPayOrderId(@Param("payOrderId") String payOrderId);

    /**
     * 高频卡号检测：同一 card_bin+last4 在 10 分钟内出现的次数
     * 用于实时风控拦截
     */
    Integer countSameCardInWindow(@Param("cardBin") String cardBin,
                                   @Param("cardLast4") String cardLast4,
                                   @Param("windowMinutes") Integer windowMinutes);

    /**
     * 同 IP 高频检测：10 分钟内 N 笔
     */
    Integer countSameIpInWindow(@Param("ip") String ip,
                                 @Param("windowMinutes") Integer windowMinutes);

    /**
     * 按命中规则聚合统计（用于规则有效性分析）
     * 返回：risk_action, count
     */
    List<Map<String, Object>> countByRiskAction(@Param("mchNo") String mchNo,
                                                 @Param("days") Integer days);

    /**
     * 同一 card_bin 在窗口期内涉及到的不同商户数
     * 为什么这么做：检测卡测试 / 盗卡 —— 短时间内一张卡在多个商户出现意味着高风险
     * 注意：使用 DISTINCT mch_no 计数；调用方应传 cardBin 非空
     */
    @Select("SELECT COUNT(DISTINCT mch_no) FROM t_order_risk_record " +
            "WHERE card_bin = #{cardBin} " +
            "AND created_at >= DATE_SUB(NOW(), INTERVAL #{windowMinutes} MINUTE)")
    Integer countDistinctMerchantsByCardBin(@Param("cardBin") String cardBin,
                                            @Param("windowMinutes") Integer windowMinutes);

    /**
     * 商户历史最常见的 IP 国家。
     * 用于判断本次订单与历史习惯是否偏差较大；冷启动时（无历史）返回 null
     */
    @Select("SELECT ip_country FROM t_order_risk_record " +
            "WHERE mch_no = #{mchNo} AND ip_country IS NOT NULL AND ip_country <> '' " +
            "GROUP BY ip_country ORDER BY COUNT(*) DESC LIMIT 1")
    String findDominantIpCountry(@Param("mchNo") String mchNo);
}
