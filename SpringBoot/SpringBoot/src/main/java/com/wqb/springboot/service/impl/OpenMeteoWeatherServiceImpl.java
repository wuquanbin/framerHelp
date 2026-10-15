package com.wqb.springboot.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wqb.springboot.dto.OpenMeteoDto;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.service.OpenMeteoWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Open-Meteo天气数据服务实现类
 * <p>
 * 通过 Open-Meteo 开放 API 获取各村落的实时天气和预报数据。
 * 坐标唯一来源是 village 表的经纬度字段；未配置时退回默认坐标（白坭镇）并打 warn。
 * 本服务是「镇级」气象数值的来源 —— 高德免费接口只到区县级，无法区分同区县的乡镇。
 * 天气代码转换、风向风力转换等纯函数已抽离至 {@link com.wqb.springboot.util.WeatherCodeMapper}。
 * </p>
 */
@Service
public class OpenMeteoWeatherServiceImpl implements OpenMeteoWeatherService {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherServiceImpl.class);

    /** Open-Meteo API基础地址 */
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";
    /** Open-Meteo 预警API地址 */
    private static final String ALERTS_URL = "https://api.open-meteo.com/v1/alerts";

    /**
     * 默认坐标兜底（白坭镇）。
     * <p>
     * 坐标的唯一来源是 {@code village} 表的 latitude / longitude 字段
     * （由 {@code DemoDataInitializer.backfillVillageGeo()} 在启动时补齐）。
     * 这里只作为村镇坐标为空的最后兜底，出现时说明数据没配好，会打 warn 日志。
     * </p>
     */
    private static final double[] DEFAULT_COORDS = new double[]{23.16, 112.86};

    /**
     * 单次批量请求的坐标上限。
     * <p>
     * Open-Meteo 理论上支持几十个坐标，但 URL 过长会被 CDN/网关直接拒掉；
     * 更实际的问题是"一损俱损"——某个坐标非法会让整批请求失败。
     * 取 10 可以在"请求次数"与"单批失败的爆炸半径"之间取得平衡，
     * 超出部分自动拆成多批，各批之间再用虚拟线程并行。
     * </p>
     */
    private static final int MAX_COORDS_PER_REQUEST = 10;

    /**
     * 批量响应解析专用。
     * <p>
     * 这里刻意 new 一个而不是注入 Spring 的 ObjectMapper：本模块用的是 Jackson 2 的
     * {@code com.fasterxml.jackson} API，而 Spring Boot 4 容器里的默认 ObjectMapper 是
     * Jackson 3（{@code tools.jackson}），注入会拿到错误类型。
     * </p>
     */
    private static final ObjectMapper BATCH_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RestTemplate restTemplate;

    public OpenMeteoWeatherServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 一个坐标槽位：经纬度 + 共用该坐标的所有村镇。
     * <p>
     * 坐标完全相同的村镇（库里重复录入或经纬度配错）合并成同一槽位，只占一次请求配额。
     * </p>
     */
    record CoordSlot(double lat, double lon, List<Village> members) {}

    /**
     * 解析村镇经纬度 {纬度, 经度}。
     * <p>
     * 唯一来源是 village 表自身的坐标字段；为空时退回默认坐标并打 warn，
     * 提示该村镇没有配好经纬度（而不是静默给出一个错误地点的天气）。
     * </p>
     */
    private double[] resolveCoords(Village village) {
        if (village != null && village.getLatitude() != null && village.getLongitude() != null) {
            return new double[]{village.getLatitude().doubleValue(), village.getLongitude().doubleValue()};
        }
        log.warn("Village {} has no coordinates configured, falling back to default ({}, {})",
                village != null ? village.getCode() : "null",
                DEFAULT_COORDS[0], DEFAULT_COORDS[1]);
        return DEFAULT_COORDS;
    }

    @Override
    public OpenMeteoDto.CurrentResponse fetchCurrent(Village village) {
        double[] coords = resolveCoords(village);

        // 单坐标请求复用批量接口的 URI 构造器，避免两处参数漂移
        URI uri = buildCurrentUri(List.of(coords[0]), List.of(coords[1]));

        log.debug("Calling Open-Meteo current API: {}", uri);
        try {
            return restTemplate.getForObject(uri, OpenMeteoDto.CurrentResponse.class);
        } catch (Exception e) {
            log.warn("Open-Meteo current API failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public OpenMeteoDto.ForecastResponse fetchForecast(Village village, int days) {
        double[] coords = resolveCoords(village);

        URI uri = buildForecastUri(List.of(coords[0]), List.of(coords[1]), days);

        log.info("Calling Open-Meteo forecast API: {}", uri);
        try {
            return restTemplate.getForObject(uri, OpenMeteoDto.ForecastResponse.class);
        } catch (Exception e) {
            log.warn("Open-Meteo API failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public OpenMeteoDto.AlertsResponse fetchAlerts(Village village) {
        double[] coords = resolveCoords(village);

        URI uri = UriComponentsBuilder.fromUriString(ALERTS_URL)
                .queryParam("latitude", coords[0])
                .queryParam("longitude", coords[1])
                .queryParam("timezone", "Asia/Shanghai")
                .build()
                .toUri();

        log.info("Calling Open-Meteo alerts API: {}", uri);
        try {
            return restTemplate.getForObject(uri, OpenMeteoDto.AlertsResponse.class);
        } catch (Exception e) {
            log.warn("Open-Meteo alerts API failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 批量接口 ====================

    @Override
    public Map<Long, OpenMeteoDto.CurrentResponse> fetchCurrentBatch(List<Village> villages) {
        return fetchMultiLocation(villages, "current",
                OpenMeteoDto.CurrentResponse.class,
                this::buildCurrentUri);
    }

    @Override
    public Map<Long, OpenMeteoDto.ForecastResponse> fetchForecastBatch(List<Village> villages, int days) {
        return fetchMultiLocation(villages, "forecast(" + days + "d)",
                OpenMeteoDto.ForecastResponse.class,
                (latitudes, longitudes) -> buildForecastUri(latitudes, longitudes, days));
    }

    /**
     * 批量请求的通用实现：坐标去重 → 分块 → 各块并行 HTTP → 按村镇ID回填结果。
     *
     * @param villages   村镇列表
     * @param label      日志里区分用途的标签
     * @param type       单份响应的类型
     * @param uriBuilder 由坐标列表拼 URI 的函数
     * @return 村镇ID → 单份响应；失败的村镇不会出现在 map 里
     */
    private <T> Map<Long, T> fetchMultiLocation(List<Village> villages,
                                                String label,
                                                Class<T> type,
                                                BiFunction<List<Double>, List<Double>, URI> uriBuilder) {
        if (villages == null || villages.isEmpty()) {
            return Map.of();
        }

        List<CoordSlot> slots = dedupeByCoordinate(villages);
        List<List<CoordSlot>> chunks = chunk(slots, MAX_COORDS_PER_REQUEST);

        log.info("Open-Meteo batch {}: {} villages → {} unique coord(s), {} request(s)",
                label, villages.size(), slots.size(), chunks.size());

        Map<Long, T> result = new ConcurrentHashMap<>();
        long start = System.currentTimeMillis();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(chunks.size());
            for (List<CoordSlot> group : chunks) {
                futures.add(CompletableFuture.runAsync(() -> {
                    List<Double> latitudes = group.stream().map(s -> s.lat).toList();
                    List<Double> longitudes = group.stream().map(s -> s.lon).toList();
                    try {
                        List<T> responses = requestMultiLocation(uriBuilder.apply(latitudes, longitudes), type);
                        if (responses.size() != group.size()) {
                            // 服务端少返回了 —— 不能按下标盲取，否则整批村镇的数据会串位
                            log.warn("Open-Meteo batch {} response size mismatch: expected {}, got {}",
                                    label, group.size(), responses.size());
                            return;
                        }
                        for (int i = 0; i < group.size(); i++) {
                            T value = responses.get(i);
                            if (value != null) {
                                for (Village member : group.get(i).members()) {
                                    result.put(member.getId(), value);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Open-Meteo batch {} request failed for {} coord(s): {}",
                                label, group.size(), e.getMessage());
                    }
                }, pool));
            }
            futures.forEach(CompletableFuture::join);
        }

        log.info("Open-Meteo batch {} finished: {}/{} village(s) have data, {} ms",
                label, result.size(), villages.size(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 发起一次多坐标请求并解析成列表。
     * <p>
     * 刻意读成 String 再交给 {@link #parseBatchResponse} 处理，
     * 而不是让 RestTemplate 直接绑定类型：多坐标响应是"数组 or 对象"两种形状，
     * 直接绑定会在单坐标时炸掉。
     * </p>
     */
    private <T> List<T> requestMultiLocation(URI uri, Class<T> type) throws Exception {
        String body = restTemplate.getForObject(uri, String.class);
        return parseBatchResponse(body, type);
    }

    /**
     * 解析多坐标响应体。
     * <p>
     * 只传 1 个坐标时 Open-Meteo 返回<b>对象</b>、传多个时返回<b>数组</b>，两种 JSON 形状完全不同，
     * 所以不能直接反序列化成 {@code T[]}：必须先看根节点是数组还是对象，再分别处理。
     * </p>
     *
     * @param body 原始响应体
     * @param type 单份数据的类型
     * @return 按下标对应的响应列表
     */
    <T> List<T> parseBatchResponse(String body, Class<T> type) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty response body");
        }
        JsonNode root = BATCH_MAPPER.readTree(body);
        List<T> out = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                out.add(BATCH_MAPPER.treeToValue(node, type));
            }
        } else if (root.isObject()) {
            out.add(BATCH_MAPPER.treeToValue(root, type));
        } else {
            throw new IllegalStateException("unexpected JSON shape: " + root.getNodeType());
        }
        return out;
    }

    /** 按经纬度去重:坐标相同的村镇合并为一个槽位,保持首次出现顺序 */
    List<CoordSlot> dedupeByCoordinate(List<Village> villages) {
        Map<String, CoordSlot> byKey = new LinkedHashMap<>();
        for (Village village : villages) {
            double[] coords = resolveCoords(village);
            String key = coords[0] + "," + coords[1];
            CoordSlot slot = byKey.get(key);
            if (slot == null) {
                slot = new CoordSlot(coords[0], coords[1], new ArrayList<>());
                byKey.put(key, slot);
            }
            slot.members().add(village);
        }
        return new ArrayList<>(byKey.values());
    }

    /** 把槽位列表切成若干不超过 limit 的分片 */
    static <E> List<List<E>> chunk(List<E> source, int limit) {
        List<List<E>> chunks = new ArrayList<>();
        for (int i = 0; i < source.size(); i += limit) {
            chunks.add(source.subList(i, Math.min(i + limit, source.size())));
        }
        return chunks;
    }

    /** 多坐标实况 URI： latitudes/longitudes 用逗号连接 */
    private URI buildCurrentUri(List<Double> latitudes, List<Double> longitudes) {
        return UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("latitude", join(latitudes))
                .queryParam("longitude", join(longitudes))
                // precipitation_probability 为真实预报的当前时次降水概率，用于替代原先按天气现象查表的估算值
                .queryParam("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,precipitation_probability")
                .queryParam("timezone", "Asia/Shanghai")
                .build()
                .toUri();
    }

    /** 多坐标预报 URI */
    private URI buildForecastUri(List<Double> latitudes, List<Double> longitudes, int days) {
        return UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("latitude", join(latitudes))
                .queryParam("longitude", join(longitudes))
                .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_direction_10m_dominant,wind_speed_10m_max")
                .queryParam("timezone", "Asia/Shanghai")
                .queryParam("forecast_days", days)
                .build()
                .toUri();
    }

    /** 把数值列表拼成 "1.2,3.4,5.6" —— Open-Meteo 多坐标语法 */
    private static String join(List<Double> values) {
        StringBuilder sb = new StringBuilder();
        for (Double v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
