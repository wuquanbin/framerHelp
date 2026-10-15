package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.AmapConfig;
import com.wqb.springboot.dto.AmapDto;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.service.AmapWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 高德天气数据服务实现类
 * <p>
 * 通过高德开放API获取各村落实时天气和天气预报数据。
 * adcode 唯一来源是 village 表的 adcode 字段；未配置时退回市级（佛山市）并打 warn。
 * 本服务主要提供「天气现象」的中文表述（含日/夜转场），数值精度只到区县级。
 * </p>
 */
@Service
public class AmapWeatherServiceImpl implements AmapWeatherService {

    private static final Logger log = LoggerFactory.getLogger(AmapWeatherServiceImpl.class);

    /**
     * 默认行政区编码兜底（佛山市）。
     * <p>
     * adcode 的唯一来源是 {@code village} 表的 adcode 字段
     * （由 {@code DemoDataInitializer.backfillVillageGeo()} 在启动时补齐）。
     * 这里只作为村镇未配 adcode 时的最后兜底，出现时说明数据没配好，会打 warn 日志。
     * </p>
     */
    private static final String DEFAULT_ADCODE = "440600";

    private final AmapConfig amapConfig;
    private final RestTemplate restTemplate;

    public AmapWeatherServiceImpl(AmapConfig amapConfig, RestTemplate restTemplate) {
        this.amapConfig = amapConfig;
        this.restTemplate = restTemplate;
    }

    /**
     * 解析村镇的高德 adcode。
     * <p>
     * 唯一来源是 village 表自身的 adcode 字段；为空时退回市级（佛山市）并打 warn，
     * 提示该村镇没有配好行政区编码。
     * </p>
     */
    private String resolveAdcode(Village village) {
        if (village != null && village.getAdcode() != null && !village.getAdcode().isBlank()) {
            return village.getAdcode();
        }
        log.warn("Village {} has no adcode configured, falling back to city-level {}",
                village != null ? village.getCode() : "null", DEFAULT_ADCODE);
        return DEFAULT_ADCODE;
    }

    @Override
    public AmapDto.LiveWeatherResponse fetchVillageLiveWeather(Village village) {
        String villageCode = village != null ? village.getCode() : "unknown";
        String adcode = resolveAdcode(village);
        URI uri = UriComponentsBuilder.fromUriString(amapConfig.getBaseUrl() + "/v3/weather/weatherInfo")
                .queryParam("key", amapConfig.getApiKey())
                .queryParam("city", adcode)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON")
                .build()
                .toUri();

        log.info("Calling Amap live weather API for village {} (adcode={}): {}", villageCode, adcode, uri);
        AmapDto.LiveWeatherResponse response = restTemplate.getForObject(uri, AmapDto.LiveWeatherResponse.class);
        if (response == null || !"1".equals(response.status)) {
            log.warn("Amap live weather API failed for {}: {}", villageCode, response != null ? response.info : "null");
            return null;
        }
        return response;
    }

    @Override
    public AmapDto.ForecastWeatherResponse fetchVillageForecast(Village village) {
        String villageCode = village != null ? village.getCode() : "unknown";
        String adcode = resolveAdcode(village);
        URI uri = UriComponentsBuilder.fromUriString(amapConfig.getBaseUrl() + "/v3/weather/weatherInfo")
                .queryParam("key", amapConfig.getApiKey())
                .queryParam("city", adcode)
                .queryParam("extensions", "all")
                .queryParam("output", "JSON")
                .build()
                .toUri();

        log.info("Calling Amap forecast API for village {} (adcode={}): {}", villageCode, adcode, uri);
        AmapDto.ForecastWeatherResponse response = restTemplate.getForObject(uri, AmapDto.ForecastWeatherResponse.class);
        if (response == null || !"1".equals(response.status)) {
            log.warn("Amap forecast API failed for {}: {}", villageCode, response != null ? response.info : "null");
            return null;
        }
        return response;
    }
}
