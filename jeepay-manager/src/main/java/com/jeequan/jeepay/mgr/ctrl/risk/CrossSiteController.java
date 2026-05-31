/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.CrossSiteClient;
import com.jeequan.jeepay.core.entity.CrossSiteNotifyRecord;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.mapper.CrossSiteClientMapper;
import com.jeequan.jeepay.service.mapper.CrossSiteNotifyRecordMapper;
import com.jeequan.jeepay.service.mapper.CrossSitePushRecordMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 跨站客户端 CRUD + 推送记录 + 通知记录查询
 *
 * URL:
 *   /api/risk/crossSite/client      凭据管理（CRUD）
 *   /api/risk/crossSite/record      跨站订单流水（只读）
 *   /api/risk/crossSite/notify      异步通知记录（只读 + 手动重试）
 *
 * @author 反风控改造组
 */
@Tag(name = "A/B 站联动管理")
@RestController
@RequestMapping("/api/risk/crossSite")
public class CrossSiteController extends CommonCtrl {

    private static final SecureRandom RNG = new SecureRandom();

    @Autowired private CrossSiteClientMapper clientMapper;
    @Autowired private CrossSitePushRecordMapper pushMapper;
    @Autowired private CrossSiteNotifyRecordMapper notifyMapper;

    // ============================================
    // 客户端凭据 CRUD
    // ============================================

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_LIST')")
    @RequestMapping(value = "/client", method = RequestMethod.GET)
    public ApiPageRes<CrossSiteClient> listClient() {
        CrossSiteClient q = getObject(CrossSiteClient.class);
        LambdaQueryWrapper<CrossSiteClient> w = CrossSiteClient.gw();
        if (StringUtils.isNotEmpty(q.getClientId())) w.like(CrossSiteClient::getClientId, q.getClientId());
        if (q.getEnabled() != null) w.eq(CrossSiteClient::getEnabled, q.getEnabled());
        w.orderByDesc(CrossSiteClient::getCreatedAt);
        IPage<CrossSiteClient> page = clientMapper.selectPage(getIPage(true), w);
        // 脱敏：列表不返回 secret
        page.getRecords().forEach(c -> c.setClientSecret(null));
        return ApiPageRes.pages(page);
    }

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_VIEW')")
    @RequestMapping(value = "/client/{clientId}", method = RequestMethod.GET)
    public ApiRes<CrossSiteClient> detail(@PathVariable String clientId) {
        CrossSiteClient c = clientMapper.selectById(clientId);
        return c == null ? ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE) : ApiRes.ok(c);
    }

    /**
     * 新增凭据。如果 secret 字段为空 / 为字符串 "AUTO"，则后端自动生成 32 字节随机
     */
    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_EDIT')")
    @MethodLog(remark = "新增跨站客户端")
    @RequestMapping(value = "/client", method = RequestMethod.POST)
    public ApiRes<CrossSiteClient> add() {
        CrossSiteClient c = getObject(CrossSiteClient.class);
        if (StringUtils.isBlank(c.getClientId())) return ApiRes.customFail("client_id 不能为空");
        if (StringUtils.isBlank(c.getClientSecret()) || "AUTO".equals(c.getClientSecret())) {
            c.setClientSecret(generateSecret());
        }
        if (c.getEnabled() == null) c.setEnabled((byte) 1);
        clientMapper.insert(c);
        // 新增时返回明文 secret（仅此一次）
        return ApiRes.ok(c);
    }

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_EDIT')")
    @MethodLog(remark = "修改跨站客户端")
    @RequestMapping(value = "/client/{clientId}", method = RequestMethod.PUT)
    public ApiRes update(@PathVariable String clientId) {
        CrossSiteClient c = getObject(CrossSiteClient.class);
        c.setClientId(clientId);
        // 修改时不允许动 secret（要换密钥走 rotate 接口）
        c.setClientSecret(null);
        clientMapper.updateById(c);
        return ApiRes.ok();
    }

    /** 重新生成 secret（轮换密钥）；返回新明文 secret，仅此一次 */
    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_EDIT')")
    @MethodLog(remark = "重新生成跨站客户端密钥")
    @RequestMapping(value = "/client/{clientId}/rotateSecret", method = RequestMethod.POST)
    public ApiRes<String> rotate(@PathVariable String clientId) {
        CrossSiteClient c = clientMapper.selectById(clientId);
        if (c == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        String newSecret = generateSecret();
        c.setClientSecret(newSecret);
        clientMapper.updateById(c);
        return ApiRes.ok(newSecret);
    }

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_EDIT')")
    @MethodLog(remark = "删除跨站客户端")
    @RequestMapping(value = "/client/{clientId}", method = RequestMethod.DELETE)
    public ApiRes delete(@PathVariable String clientId) {
        clientMapper.deleteById(clientId);
        return ApiRes.ok();
    }

    // ============================================
    // 跨站订单流水（只读）
    // ============================================

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_LIST')")
    @RequestMapping(value = "/record", method = RequestMethod.GET)
    public ApiPageRes<CrossSitePushRecord> listRecord() {
        CrossSitePushRecord q = getObject(CrossSitePushRecord.class);
        LambdaQueryWrapper<CrossSitePushRecord> w = CrossSitePushRecord.gw();
        if (StringUtils.isNotEmpty(q.getClientId())) w.eq(CrossSitePushRecord::getClientId, q.getClientId());
        if (StringUtils.isNotEmpty(q.getOrderId())) w.eq(CrossSitePushRecord::getOrderId, q.getOrderId());
        if (StringUtils.isNotEmpty(q.getState())) w.eq(CrossSitePushRecord::getState, q.getState());
        if (StringUtils.isNotEmpty(q.getChannelProvider())) w.eq(CrossSitePushRecord::getChannelProvider, q.getChannelProvider());
        w.orderByDesc(CrossSitePushRecord::getId);
        IPage<CrossSitePushRecord> page = pushMapper.selectPage(getIPage(true), w);
        return ApiPageRes.pages(page);
    }

    // ============================================
    // 异步通知记录（只读 + 手动重试）
    // ============================================

    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_LIST')")
    @RequestMapping(value = "/notify", method = RequestMethod.GET)
    public ApiPageRes<CrossSiteNotifyRecord> listNotify() {
        CrossSiteNotifyRecord q = getObject(CrossSiteNotifyRecord.class);
        LambdaQueryWrapper<CrossSiteNotifyRecord> w = CrossSiteNotifyRecord.gw();
        if (StringUtils.isNotEmpty(q.getClientId())) w.eq(CrossSiteNotifyRecord::getClientId, q.getClientId());
        if (StringUtils.isNotEmpty(q.getOrderId())) w.eq(CrossSiteNotifyRecord::getOrderId, q.getOrderId());
        if (q.getState() != null) w.eq(CrossSiteNotifyRecord::getState, q.getState());
        w.orderByDesc(CrossSiteNotifyRecord::getId);
        IPage<CrossSiteNotifyRecord> page = notifyMapper.selectPage(getIPage(true), w);
        return ApiPageRes.pages(page);
    }

    /** 手动重置某条通知 next_notify_time=NOW，让调度器立刻重试 */
    @PreAuthorize("hasAuthority('ENT_CROSS_SITE_EDIT')")
    @MethodLog(remark = "手动重试跨站通知")
    @RequestMapping(value = "/notify/{id}/retry", method = RequestMethod.POST)
    public ApiRes retryNotify(@PathVariable Long id) {
        CrossSiteNotifyRecord r = notifyMapper.selectById(id);
        if (r == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        // 重置为进行中并立刻触发
        r.setState(CrossSiteNotifyRecord.STATE_ING);
        r.setNextNotifyTime(new java.util.Date());
        notifyMapper.updateById(r);
        return ApiRes.ok();
    }

    // ============================================
    // 辅助
    // ============================================
    private String generateSecret() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
