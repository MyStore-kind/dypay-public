/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jeequan.jeepay.core.entity.AgentInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 代理商信息表 Mapper 接口
 * 自定义查询：分页（含上级代理名）、树形结构查询
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
public interface AgentInfoMapper extends BaseMapper<AgentInfo> {

    /**
     * 分页查询代理商列表（关联上级代理名）
     * 为什么 XML 而非 Wrapper：需 LEFT JOIN 自身表取上级名称，Wrapper 不便表达
     */
    IPage<Map<String, Object>> selectAgentPage(Page<Map<String, Object>> page,
                                                @Param("agentName") String agentName,
                                                @Param("agentLevel") Byte agentLevel,
                                                @Param("state") Byte state,
                                                @Param("parentAgentNo") String parentAgentNo);

    /**
     * 查询某代理商所有下级（递归一层；多层由 Service 层多次调用拼接）
     */
    List<AgentInfo> selectChildren(@Param("parentAgentNo") String parentAgentNo);

    /**
     * 按代理商号查询完整上级链（最多 3 级）
     * 用于分润链路计算
     */
    List<AgentInfo> selectAgentChain(@Param("agentNo") String agentNo);
}
