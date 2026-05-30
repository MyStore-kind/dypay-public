/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.entity.MerchantRiskScore;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.MerchantRiskService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商户风险看板（评分历史与详情）
 */
@Tag(name = "商户风险看板")
@RestController
@RequestMapping("/api/merchantRisk")
public class MerchantRiskController extends CommonCtrl {

    @Autowired private MerchantRiskService merchantRiskService;

    /** 分页查询评分历史（支持按商户/风险等级筛选） */
    @PreAuthorize("hasAuthority('ENT_MERCHANT_RISK_LIST')")
    @RequestMapping(value = "/scores", method = RequestMethod.GET)
    public ApiPageRes<MerchantRiskScore> listScores() {
        MerchantRiskScore q = getObject(MerchantRiskScore.class);
        LambdaQueryWrapper<MerchantRiskScore> wrapper = MerchantRiskScore.gw();
        if (StringUtils.isNotEmpty(q.getMchNo())) wrapper.eq(MerchantRiskScore::getMchNo, q.getMchNo());
        if (StringUtils.isNotEmpty(q.getRiskTier())) wrapper.eq(MerchantRiskScore::getRiskTier, q.getRiskTier());
        wrapper.orderByDesc(MerchantRiskScore::getScoreDate);
        IPage<MerchantRiskScore> pages = merchantRiskService.page(getIPage(true), wrapper);
        return ApiPageRes.pages(pages);
    }

    /** 查询某商户最近 30 天的评分趋势 */
    @PreAuthorize("hasAuthority('ENT_MERCHANT_RISK_LIST')")
    @RequestMapping(value = "/trend/{mchNo}", method = RequestMethod.GET)
    public ApiRes<List<MerchantRiskScore>> trend(@PathVariable String mchNo) {
        List<MerchantRiskScore> list = merchantRiskService.list(MerchantRiskScore.gw()
                .eq(MerchantRiskScore::getMchNo, mchNo)
                .orderByDesc(MerchantRiskScore::getScoreDate)
                .last("LIMIT 30"));
        return ApiRes.ok(list);
    }
}
