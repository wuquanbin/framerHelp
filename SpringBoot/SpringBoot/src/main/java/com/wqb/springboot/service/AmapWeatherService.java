package com.wqb.springboot.service;

import com.wqb.springboot.dto.AmapDto;
import com.wqb.springboot.entity.Village;

/**
 * 高德天气数据服务接口
 * <p>
 * 通过高德开放API获取各村落实时天气和天气预报数据。
 * </p>
 */
public interface AmapWeatherService {

    /**
     * 获取村落实况天气
     *
     * @param village 村落实体（优先使用其 adcode 字段，其次按 code 查内置映射，最后市级兜底）
     * @return 实况天气响应；API调用失败时返回null
     */
    AmapDto.LiveWeatherResponse fetchVillageLiveWeather(Village village);

    /**
     * 获取天气预报（4天：当天+未来3天）
     *
     * @param village 村落实体（优先使用其 adcode 字段，其次按 code 查内置映射，最后市级兜底）
     * @return 预报响应；API调用失败时返回null
     */
    AmapDto.ForecastWeatherResponse fetchVillageForecast(Village village);
}
