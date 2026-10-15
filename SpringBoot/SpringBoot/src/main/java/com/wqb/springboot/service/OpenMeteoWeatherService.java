package com.wqb.springboot.service;

import com.wqb.springboot.dto.OpenMeteoDto;
import com.wqb.springboot.entity.Village;

import java.util.List;
import java.util.Map;

/**
 * Open-Meteo天气数据服务接口
 * <p>
 * 通过 Open-Meteo 开放 API 获取各村落的实时天气和预报数据。
 * 天气代码转换、风向风力转换等纯函数已抽离至
 * {@link com.wqb.springboot.util.WeatherCodeMapper}。
 * </p>
 */
public interface OpenMeteoWeatherService {

    /**
     * 获取指定村落的实时天气数据
     *
     * @param village 村落实体（优先使用其经纬度，其次按 code 查内置映射，最后白坭镇兜底）
     * @return 实时天气响应;API调用失败时返回null
     */
    OpenMeteoDto.CurrentResponse fetchCurrent(Village village);

    /**
     * 批量获取多个村镇的实时天气。
     * <p>
     * Open-Meteo 支持 {@code latitude=23.1,23.2&longitude=112.8,112.9} 的多坐标写法，
     * 一次 HTTP 往返即可取回全部村镇的数据（逐村串行时，N 个村镇要跑 N 次）。
     * 坐标相同的村镇会合并成同一个槽位，只占一次配额。
     * </p>
     *
     * @param villages 村镇列表（不能为空）
     * @return 村镇ID → 响应。缺失的键表示该村镇这次没取到数据，调用方按降级逻辑处理
     */
    Map<Long, OpenMeteoDto.CurrentResponse> fetchCurrentBatch(List<Village> villages);

    /**
     * 获取指定村落的多日天气预报
     *
     * @param village 村落实体（优先使用其经纬度，其次按 code 查内置映射，最后白坭镇兜底）
     * @param days    预报天数
     * @return 预报响应;API调用失败时返回null
     */
    OpenMeteoDto.ForecastResponse fetchForecast(Village village, int days);

    /**
     * 批量获取多个村镇的多日预报，语义同 {@link #fetchCurrentBatch(List)}。
     *
     * @param villages 村镇列表（不能为空）
     * @param days     预报天数
     * @return 村镇ID → 响应。缺失的键表示该村镇这次没取到数据
     */
    Map<Long, OpenMeteoDto.ForecastResponse> fetchForecastBatch(List<Village> villages, int days);

    /**
     * 获取指定村落的气象预警
     *
     * @param village 村落实体（优先使用其经纬度，其次按 code 查内置映射，最后白坭镇兜底）
     * @return 预警响应;API调用失败时返回null
     */
    OpenMeteoDto.AlertsResponse fetchAlerts(Village village);
}
