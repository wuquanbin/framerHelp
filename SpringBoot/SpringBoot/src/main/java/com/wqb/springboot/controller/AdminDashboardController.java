package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.service.AdminDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理后台仪表盘控制器
 * <p>
 * 提供管理后台首页仪表盘的统计数据接口,包括用户数、预警数、反馈数、农情上报数等。
 * 业务逻辑已下沉至 {@link AdminDashboardService},控制器仅负责请求转发。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    /**
     * 获取仪表盘统计数据
     *
     * @return 统计数据Map,包含 totalUsers、activeWarnings、totalFeedback、pendingFeedback、
     *         totalVillages、pendingFarmReports、totalCrops、totalFarmAdvices
     */
    @GetMapping("/dashboard/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(adminDashboardService.getStats());
    }

    /**
     * 获取全局作物类型分布统计
     *
     * @return 作物类型分布列表,每项包含 name(类型名)和 value(种植面积/亩)
     */
    @GetMapping("/dashboard/crop-distribution")
    public ApiResponse<List<Map<String, Object>>> cropDistribution() {
        return ApiResponse.ok(adminDashboardService.getCropDistribution());
    }
}
