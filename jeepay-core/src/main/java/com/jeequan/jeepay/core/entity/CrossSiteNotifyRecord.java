/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jeequan.jeepay.core.model.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 跨站异步通知重试记录
 *
 * 设计：
 *  - 与 t_mch_notify_record（商户支付订单通知）解耦，避免类型混淆
 *  - state=1 时调度器扫描 next_notify_time<=NOW() 的记录重试
 *  - 6 次指数退避：60s / 5m / 30m / 1h / 6h / 24h
 *
 * @author 反风控改造组
 */
@Schema(description = "跨站异步通知记录")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_cross_site_notify_record")
public class CrossSiteNotifyRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<CrossSiteNotifyRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    /** 通知状态 */
    public static final byte STATE_ING = 1;
    public static final byte STATE_SUCCESS = 2;
    public static final byte STATE_FAIL = 3;

    /** 事件类型 */
    public static final String EVENT_PAID = "paid";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_EXPIRED = "expired";
    public static final String EVENT_REFUNDED = "refunded";

    /** 指数退避间隔（秒） */
    public static final int[] BACKOFF_SECONDS = { 60, 300, 1800, 3600, 21600, 86400 };

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long pushRecordId;
    private String clientId;
    private String orderId;
    private String notifyUrl;

    private String payload;
    private String eventType;

    private Integer notifyCount;
    private Integer notifyCountLimit;
    private Date nextNotifyTime;
    private Date lastNotifyTime;
    private String lastResponse;
    private Integer lastHttpCode;

    private Byte state;

    private Date createdAt;
    private Date updatedAt;
}
