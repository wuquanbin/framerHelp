package com.wqb.springboot.service;

import com.wqb.springboot.dto.WeatherResponseDto.AgriReport;
import com.wqb.springboot.dto.WeatherResponseDto.CropProfileItem;
import com.wqb.springboot.dto.WeatherResponseDto.CurrentWeather;
import com.wqb.springboot.dto.WeatherResponseDto.DashboardOverview;
import com.wqb.springboot.dto.WeatherResponseDto.DisasterRecordItem;
import com.wqb.springboot.dto.WeatherResponseDto.FarmAdviceItem;
import com.wqb.springboot.dto.WeatherResponseDto.ForecastDay;
import com.wqb.springboot.dto.WeatherResponseDto.ModuleStatusItem;
import com.wqb.springboot.dto.WeatherResponseDto.VillageOption;
import com.wqb.springboot.dto.WeatherResponseDto.WarningNoticeItem;

import java.util.List;

/**
 * 乡村农业气象帮扶服务接口
 * <p>
 * 提供各村落的实时天气、天气预报、农事建议、作物档案、
 * 灾害记录、预警通知和模块状态等数据查询与聚合功能。
 * </p>
 */
public interface AgriWeatherService {

    /**
     * 获取所有村落列表
     *
     * @return 村落选项列表，按ID升序排列
     */
    List<VillageOption> listVillages();

    /**
     * 获取指定村落的实时天气
     *
     * @param villageCode 村落编码
     * @return 当前天气数据；若无观测数据则返回默认占位值
     */
    CurrentWeather getCurrentWeather(String villageCode);

    /**
     * 获取指定村落的天气预报
     *
     * @param villageCode 村落编码
     * @return 预报日列表，按日期升序排列
     */
    List<ForecastDay> getForecast(String villageCode);

    /**
     * 获取指定村落的农事建议
     *
     * @param villageCode 村落编码
     * @return 农事建议列表，按优先级降序排列
     */
    List<FarmAdviceItem> getFarmAdvices(String villageCode);

    /**
     * 获取指定村落的作物档案
     *
     * @param villageCode 村落编码
     * @return 作物档案列表，按风险等级降序排列
     */
    List<CropProfileItem> getCropProfiles(String villageCode);

    /**
     * 获取指定村落的灾害记录
     *
     * @param villageCode 村落编码
     * @return 灾害记录列表，按严重程度降序排列
     */
    List<DisasterRecordItem> getDisasterRecords(String villageCode);

    /**
     * 获取所有活跃的气象预警
     *
     * @return 活跃预警列表
     */
    List<WarningNoticeItem> listActiveWarnings();

    /**
     * 获取各功能模块的运行状态
     *
     * @return 模块状态列表
     */
    List<ModuleStatusItem> listModuleStatus();

    /**
     * 获取仪表盘概览数据
     *
     * @param villageCode 村落编码
     * @return 仪表盘概览数据
     */
    DashboardOverview getDashboardOverview(String villageCode);

    /**
     * 生成农业气象报告
     *
     * @param villageCode 村落编码
     * @return 农业气象报告
     */
    AgriReport getAgriReport(String villageCode);
}
