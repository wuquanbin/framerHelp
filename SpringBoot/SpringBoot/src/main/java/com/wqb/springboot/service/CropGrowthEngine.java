package com.wqb.springboot.service;

import com.wqb.springboot.dto.WeatherResponseDto.CropReminder;
import com.wqb.springboot.dto.WeatherResponseDto.DailyFarmPlan;
import com.wqb.springboot.dto.WeatherResponseDto.DisasterRemedy;

import java.util.List;

/**
 * 作物生长引擎服务接口
 * <p>
 * 融合天气数据与常见作物生长模型，为农户生成农事提醒。
 * 核心功能包括：作物农事提醒、周农事计划、灾后补救建议。
 * </p>
 */
public interface CropGrowthEngine {

    /**
     * 根据村落编码生成各作物的农事提醒
     *
     * @param villageCode 村落编码
     * @return 作物农事提醒列表
     */
    List<CropReminder> generateCropReminders(String villageCode);

    /**
     * 根据村落编码生成一周农事计划
     *
     * @param villageCode 村落编码
     * @return 每日农事计划列表，按日期升序排列
     */
    List<DailyFarmPlan> getWeeklyFarmPlan(String villageCode);

    /**
     * 根据村落编码和灾害类型生成灾后补救建议
     *
     * @param villageCode  村落编码
     * @param disasterType 灾害类型（暴雨/干旱/台风/高温/低温/冰雹）
     * @return 灾后补救建议列表
     */
    List<DisasterRemedy> generateDisasterRemedy(String villageCode, String disasterType);
}
