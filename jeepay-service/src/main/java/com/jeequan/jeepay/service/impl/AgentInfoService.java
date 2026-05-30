/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.AgentInfo;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.AgentInfoMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 代理商信息表 服务实现类
 * 注意：
 * - 新增代理商时需校验上级代理商是否存在
 * - 修改分润比例只影响后续订单，不影响历史分润记录
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class AgentInfoService extends ServiceImpl<AgentInfoMapper, AgentInfo> {

    /**
     * 校验并创建代理商
     * 为什么单独封装：层级关系校验是业务核心，不能省略
     */
    public boolean createAgent(AgentInfo agentInfo) {
        // 校验上级代理商
        if (StringUtils.isNotBlank(agentInfo.getParentAgentNo())) {
            AgentInfo parent = getById(agentInfo.getParentAgentNo());
            if (parent == null) {
                throw new BizException("上级代理商不存在");
            }
            if (parent.getState() != AgentInfo.STATE_ENABLE) {
                throw new BizException("上级代理商状态异常，无法新增下级");
            }
            // 子级层级 = 父级层级 + 1
            agentInfo.setAgentLevel((byte) (parent.getAgentLevel() + 1));
            // 限制最多 3 级
            if (agentInfo.getAgentLevel() > AgentInfo.LEVEL_THIRD) {
                throw new BizException("代理商层级最多支持 3 级");
            }
        } else {
            // 无上级则为一级代理
            agentInfo.setAgentLevel(AgentInfo.LEVEL_FIRST);
        }

        return save(agentInfo);
    }

    /**
     * 获取代理商的完整层级链（从当前代理向上追溯到一级代理）
     * 用于多级分润计算
     *
     * @param agentNo 代理商号
     * @return 代理商列表（从当前到顶级，顺序：[当前代理, 父代理, 祖代理]）
     */
    public List<AgentInfo> getAgentChain(String agentNo) {
        List<AgentInfo> chain = new ArrayList<>();
        String currentNo = agentNo;
        // 防御性检查：限制递归深度，防止数据异常导致死循环
        int maxDepth = 10;
        while (StringUtils.isNotBlank(currentNo) && chain.size() < maxDepth) {
            AgentInfo agent = getById(currentNo);
            if (agent == null) {
                break;
            }
            chain.add(agent);
            currentNo = agent.getParentAgentNo();
        }
        return chain;
    }
}
