package com.wqb.springboot.service;

import java.util.List;
import java.util.Map;

/**
 * 管理后台仪表盘服务接口
 * <p>
 * 提供管理后台首页仪表盘的统计数据查询功能。
 * </p>
 */
public interface AdminDashboardService {

    /**
     * 获取仪表盘统计数据
     * <p>
     * 返回用户总数、活跃预警数、反馈总数、待处理反馈数、
     * 村落总数、待处理农情上报数、作物总数、农事建议总数等。
     * </p>
     *
     * @return 统计数据
     */
    Map<String, Object> getStats();

    /**
     * 获取全局作物类型分布统计
     * <p>
     * 按作物类型聚合所有村庄的种植面积,返回用于饼图展示的分布数据。
     * </p>
     *
     * @return 作物类型分布列表,每项包含 name(类型名)和 value(种植面积/亩)
     */
    List<Map<String, Object>> getCropDistribution();
}
