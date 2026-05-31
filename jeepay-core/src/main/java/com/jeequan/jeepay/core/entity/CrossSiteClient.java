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
 * A/B 站联动 - 跨站客户端凭据
 * client_secret 用于 HMAC-SHA256 验签
 *
 * @author 反风控改造组
 */
@Schema(description = "跨站客户端凭据")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_cross_site_client")
public class CrossSiteClient extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<CrossSiteClient> gw() {
        return new LambdaQueryWrapper<>();
    }

    @TableId(value = "client_id", type = IdType.INPUT)
    private String clientId;

    private String clientName;
    private String clientSecret;
    private Byte enabled;
    private String ipWhitelist;
    private String remark;

    private Date createdAt;
    private Date updatedAt;
}
