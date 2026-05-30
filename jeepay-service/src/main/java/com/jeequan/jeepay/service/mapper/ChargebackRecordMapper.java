/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jeequan.jeepay.core.entity.ChargebackRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.Map;

/**
 * 拒付记录 Mapper
 *
 * @author 反风控改造组
 */
public interface ChargebackRecordMapper extends BaseMapper<ChargebackRecord> {

    /**
     * 分页查询拒付记录（含申诉状态、商户名）
     */
    IPage<Map<String, Object>> selectChargebackPage(Page<Map<String, Object>> page,
                                                     @Param("mchNo") String mchNo,
                                                     @Param("accountId") String accountId,
                                                     @Param("ifCode") String ifCode,
                                                     @Param("state") String state,
                                                     @Param("startTime") Date startTime,
                                                     @Param("endTime") Date endTime);

    /**
     * 按账号统计拒付笔数（用于通道健康度计算）
     */
    Integer countByAccount(@Param("accountId") String accountId,
                           @Param("startTime") Date startTime,
                           @Param("endTime") Date endTime);

    /**
     * 查询即将到期的应诉记录（用于提醒）
     * state=received/under_review 且 evidence_due_at 在 N 小时内
     */
    Integer countNearDeadline(@Param("hoursLeft") Integer hoursLeft);
}
