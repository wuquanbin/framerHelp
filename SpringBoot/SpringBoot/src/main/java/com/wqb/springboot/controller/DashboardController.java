package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.WeatherResponseDto;
import com.wqb.springboot.dto.WeatherResponseDto.CropReminder;
import com.wqb.springboot.entity.User;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.service.AgriWeatherService;
import com.wqb.springboot.service.CropGrowthEngine;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 前台仪表盘控制器
 * <p>
 * 提供面向前端用户的农业气象帮扶、农事建议、作物档案、灾害记录、
 * 预警通知、知识文档、系统模块状态等数据查询接口。
 * </p>
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final AgriWeatherService agriWeatherService;
    private final CropGrowthEngine cropGrowthEngine;
    private final UserRepository userRepository;

    public DashboardController(AgriWeatherService agriWeatherService, CropGrowthEngine cropGrowthEngine, UserRepository userRepository) {
        this.agriWeatherService = agriWeatherService;
        this.cropGrowthEngine = cropGrowthEngine;
        this.userRepository = userRepository;
    }

    /**
     * 获取仪表盘概览数据
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 仪表盘概览数据
     */
    @GetMapping("/dashboard/overview")
    public ApiResponse<WeatherResponseDto.DashboardOverview> overview(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getDashboardOverview(villageCode));
    }

    /**
     * 获取村落选项列表
     *
     * @return 村落选项列表
     */
    @GetMapping("/meta/villages")
    public ApiResponse<List<WeatherResponseDto.VillageOption>> villages() {
        return ApiResponse.ok(agriWeatherService.listVillages());
    }

    /**
     * 获取当前天气信息
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 当前天气数据
     */
    @GetMapping("/weather/current")
    public ApiResponse<WeatherResponseDto.CurrentWeather> current(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getCurrentWeather(villageCode));
    }

    /**
     * 获取天气预报数据
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 未来多日天气预报列表
     */
    @GetMapping("/weather/forecast")
    public ApiResponse<List<WeatherResponseDto.ForecastDay>> forecast(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getForecast(villageCode));
    }

    /**
     * 获取农事建议列表
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 农事建议列表
     */
    @GetMapping("/farm/advices")
    public ApiResponse<List<WeatherResponseDto.FarmAdviceItem>> farmAdvices(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getFarmAdvices(villageCode));
    }

    /**
     * 获取作物档案列表
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 作物档案列表
     */
    @GetMapping("/farm/crops")
    public ApiResponse<List<WeatherResponseDto.CropProfileItem>> cropProfiles(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getCropProfiles(villageCode));
    }

    /**
     * 获取灾害记录列表
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 灾害记录列表
     */
    @GetMapping("/farm/disasters")
    public ApiResponse<List<WeatherResponseDto.DisasterRecordItem>> disasterRecords(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getDisasterRecords(villageCode));
    }

    // ========== 作物生长模型 ==========

    @GetMapping("/crop/reminders")
    public ApiResponse<List<CropReminder>> getCropReminders(
            @RequestParam String villageCode) {
        List<CropReminder> all = cropGrowthEngine.generateCropReminders(villageCode);

        // 尝试获取当前登录用户，如果用户选了"我的作物"则只返回他选的作物
        String openid = getCurrentOpenidOrNull();
        if (openid != null) {
            List<String> myCrops = userRepository.findByOpenid(openid)
                    .map(User::getMyCrops)
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> Arrays.stream(s.split(","))
                            .map(String::trim)
                            .filter(t -> !t.isEmpty())
                            .collect(Collectors.toList()))
                    .orElse(null);
            if (myCrops != null && !myCrops.isEmpty()) {
                List<CropReminder> filtered = all.stream()
                        .filter(r -> myCrops.contains(r.cropName()))
                        .collect(Collectors.toList());
                // 如果筛选后为空（用户选的作物不在本村档案中），返回全部
                if (!filtered.isEmpty()) {
                    return ApiResponse.ok(filtered);
                }
            }
        }
        return ApiResponse.ok(all);
    }

    /**
     * 从安全上下文获取当前登录用户 openid，未登录返回 null
     */
    private String getCurrentOpenidOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception e) {
            // 匿名/未登录是这条路径的正常分支，不当错误；但真抛异常要能看见，否则「登录了却拿不到数据」无从查起。
            log.debug("未能从 SecurityContext 取得 openid，按匿名处理: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取农业气象报告
     *
     * @param villageCode 村落编码（可选，用于筛选特定村落）
     * @return 农业气象报告
     */
    @GetMapping("/farm/report")
    public ApiResponse<WeatherResponseDto.AgriReport> agriReport(
            @RequestParam(required = false) String villageCode
    ) {
        return ApiResponse.ok(agriWeatherService.getAgriReport(villageCode));
    }

    /**
     * 获取当前活跃的预警通知列表
     *
     * @return 活跃预警通知列表
     */
    @GetMapping("/warnings/active")
    public ApiResponse<List<WeatherResponseDto.WarningNoticeItem>> activeWarnings() {
        return ApiResponse.ok(agriWeatherService.listActiveWarnings());
    }

    /**
     * 获取系统模块状态列表
     *
     * @return 系统模块状态列表
     */
    @GetMapping("/system/modules")
    public ApiResponse<List<WeatherResponseDto.ModuleStatusItem>> moduleStatus() {
        return ApiResponse.ok(agriWeatherService.listModuleStatus());
    }
}
