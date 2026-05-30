/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jeequan.jeepay.core.entity.AgentProfitRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 代理商分润记录表 Mapper 接口
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
public interface AgentProfitRecordMapper extends BaseMapper<AgentProfitRecord> {

    /**
     * 分页查询分润记录（含代理商名）
     */
    IPage<Map<String, Object>> selectProfitPage(Page<Map<String, Object>> page,
                                                 @Param("agentNo") String agentNo,
                                                 @Param("mchNo") String mchNo,
                                                 @Param("state") Byte state,
                                                 @Param("startDate") Date startDate,
                                                 @Param("endDate") Date endDate);

    /**
     * 按代理商 + 结算周期聚合统计待结算金额
     * state=0 待结算，按 settle_date 聚合
     */
    List<Map<String, Object>> aggregateBySettleDate(@Param("agentNo") String agentNo,
                                                     @Param("state") Byte state,
                                                     @Param("startDate") Date startDate,
                                                     @Param("endDate") Date endDate);

    /**
     * 批量更新分润记录状态（结算完成后调用）
     * 注意：仅允许 0->1 的状态迁移，避免误改已结算记录
     */
    int batchUpdateState(@Param("recordIds") List<Long> recordIds,
                         @Param("targetState") Byte targetState,
                         @Param("fromState") Byte fromState);
}
