package com.wqb.springboot.service.impl;

import com.wqb.springboot.dto.OpenMeteoDto;
import com.wqb.springboot.entity.Village;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenMeteoWeatherServiceImpl} 批量拉取相关逻辑的单元测试。
 * <p>
 * 只测"不依赖网络"的部分：多坐标响应体的解析、坐标去重、分批策略。
 * 真正的 HTTP 调用由集成场景覆盖。
 * </p>
 */
@DisplayName("Open-Meteo 多坐标批量拉取")
class OpenMeteoWeatherServiceImplTest {

    private final OpenMeteoWeatherServiceImpl service = new OpenMeteoWeatherServiceImpl(new RestTemplate());

    // ==================== 多坐标响应解析 ====================

    @Nested
    @DisplayName("多坐标响应体解析")
    class BatchResponseParsing {

        @Test
        @DisplayName("多个坐标时响应是数组 → 按下标解析成列表")
        void parseArray() throws Exception {
            String json = """
                    [
                      {"latitude":23.16,"longitude":112.86,"current":{"temperature_2m":28.4,"relative_humidity_2m":71}},
                      {"latitude":23.25,"longitude":112.95,"current":{"temperature_2m":27.1,"relative_humidity_2m":68}}
                    ]
                    """;

            List<OpenMeteoDto.CurrentResponse> result =
                    service.parseBatchResponse(json, OpenMeteoDto.CurrentResponse.class);

            assertEquals(2, result.size(), "两个坐标应解析出两份响应");
            assertEquals(28.4, result.get(0).current.temperature_2m, "第 1 份的温度应对应第 1 个坐标");
            assertEquals(71, result.get(0).current.relative_humidity_2m);
            assertEquals(27.1, result.get(1).current.temperature_2m, "第 2 份的温度应对应第 2 个坐标");
            assertEquals(68, result.get(1).current.relative_humidity_2m);
        }

        @Test
        @DisplayName("单个坐标时响应是对象(不是数组) → 兼容之,不能崩")
        void parseSingleObject() throws Exception {
            String json = """
                    {"latitude":23.16,"longitude":112.86,"current":{"temperature_2m":30.2,"relative_humidity_2m":55}}
                    """;

            List<OpenMeteoDto.CurrentResponse> result =
                    service.parseBatchResponse(json, OpenMeteoDto.CurrentResponse.class);

            assertEquals(1, result.size(), "单坐标响应包了一层对象,应归一化成 1 份");
            assertEquals(30.2, result.get(0).current.temperature_2m);
        }

        @Test
        @DisplayName("数组顺序必须与入参坐标顺序一致(否则村镇天气会串位)")
        void arrayOrderMatched() throws Exception {
            String json = """
                    [
                      {"daily":{"time":["2026-09-15"],"temperature_2m_max":[35.0]}},
                      {"daily":{"time":["2026-09-15"],"temperature_2m_max":[20.0]}},
                      {"daily":{"time":["2026-09-15"],"temperature_2m_max":[27.5]}}
                    ]
                    """;

            List<OpenMeteoDto.ForecastResponse> result =
                    service.parseBatchResponse(json, OpenMeteoDto.ForecastResponse.class);

            assertEquals(3, result.size());
            assertEquals(List.of(35.0, 20.0, 27.5),
                    result.stream().map(r -> r.daily.temperature_2m_max.get(0)).toList(),
                    "三个坐标的最高温度顺序必须与请求顺序一致");
        }

        @Test
        @DisplayName("多余字段不能让解析失败(Open-Meteo 会随参数返回很多无关字段)")
        void unknownFieldsIgnored() throws Exception {
            String json = """
                    [{"elevation":12.0,"generationtime_ms":0.3,"utc_offset_seconds":28800,
                      "current_units":{"temperature_2m":"celsius"},
                      "current":{"temperature_2m":26.0,"weather_code":3}}]
                    """;

            List<OpenMeteoDto.CurrentResponse> result =
                    service.parseBatchResponse(json, OpenMeteoDto.CurrentResponse.class);

            assertEquals(1, result.size());
            assertEquals(26.0, result.get(0).current.temperature_2m);
        }

        @Test
        @DisplayName("空响应体要抛异常,让上层退回到逐村拉取")
        void emptyBodyThrows() {
            assertThrows(Exception.class,
                    () -> service.parseBatchResponse("   ", OpenMeteoDto.CurrentResponse.class));
            assertThrows(Exception.class,
                    () -> service.parseBatchResponse(null, OpenMeteoDto.CurrentResponse.class));
        }

        @Test
        @DisplayName("非 JSON(比如网关返回的纯文本错误页)要抛异常而不是返回空列表")
        void junkBodyThrows() {
            assertThrows(Exception.class, () ->
                    service.parseBatchResponse("<html>502 Bad Gateway</html>", OpenMeteoDto.CurrentResponse.class));
        }

        @Test
        @DisplayName("空数组如实返回空列表 —— 上层按长度不等判定该批失败")
        void emptyArray() throws Exception {
            assertTrue(service.parseBatchResponse("[]", OpenMeteoDto.CurrentResponse.class).isEmpty());
        }
    }

    // ==================== 坐标去重 ====================

    @Nested
    @DisplayName("坐标去重")
    class CoordinateDedupe {

        @Test
        @DisplayName("经纬度相同的村镇合并为一个槽位(少占一次请求配额)")
        void mergeSameCoordinates() {
            Village a = villageWithCoords(1L, "23.16", "112.86");
            Village b = villageWithCoords(2L, "23.16", "112.86");
            Village c = villageWithCoords(3L, "23.30", "113.00");

            List<OpenMeteoWeatherServiceImpl.CoordSlot> slots =
                    service.dedupeByCoordinate(List.of(a, b, c));

            assertEquals(2, slots.size(), "两个坐标相同的村镇应合并成 1 个槽位");
            assertEquals(2, slots.get(0).members().size(), "合并后的槽位要记住共享它的 2 个村镇");
            assertEquals(1, slots.get(1).members().size());
        }

        @Test
        @DisplayName("缺经纬度的村镇按服务默认坐标兜底,不能因为 null 崩掉")
        void nullCoordinatesFallBack() {
            Village missing = new Village();
            setId(missing, 9L);

            List<OpenMeteoWeatherServiceImpl.CoordSlot> slots =
                    service.dedupeByCoordinate(List.of(missing));

            assertEquals(1, slots.size());
            assertEquals(23.16, slots.get(0).lat(), "应落到白坭镇默认坐标");
            assertEquals(112.86, slots.get(0).lon());
        }

        @Test
        @DisplayName("槽位顺序保持首次出现顺序 —— 回填数据时按下标对应才不会串位")
        void keepEncounterOrder() {
            Village first = villageWithCoords(1L, "23.16", "112.86");
            Village second = villageWithCoords(2L, "23.30", "113.00");
            Village third = villageWithCoords(3L, "23.16", "112.86");

            List<OpenMeteoWeatherServiceImpl.CoordSlot> slots =
                    service.dedupeByCoordinate(List.of(first, second, third));

            assertEquals(23.16, slots.get(0).lat(), "第 1 个槽位应是首次出现的坐标");
            assertEquals(23.30, slots.get(1).lat());
        }

        @Test
        @DisplayName("空村镇列表不强转 expected 越界")
        void emptyVillageList() {
            assertTrue(service.dedupeByCoordinate(List.of()).isEmpty());
        }
    }

    // ==================== 分批策略 ====================

    @Nested
    @DisplayName("分批策略")
    class Chunking {

        @Test
        @DisplayName("超过上限时按上限切片")
        void splitByLimit() {
            List<Integer> source = List.of(1, 2, 3, 4, 5);
            List<List<Integer>> chunks = OpenMeteoWeatherServiceImpl.chunk(source, 2);
            assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), chunks);
        }

        @Test
        @DisplayName("不足上限时只有一片,元素不能丢")
        void singleChunk() {
            List<List<Integer>> chunks = OpenMeteoWeatherServiceImpl.chunk(List.of(1, 2), 10);
            assertEquals(1, chunks.size());
            assertEquals(2, chunks.get(0).size());
        }

        @Test
        @DisplayName("空列表返回空的分片集合")
        void emptySource() {
            assertTrue(OpenMeteoWeatherServiceImpl.chunk(List.of(), 10).isEmpty());
        }
    }

    // ==================== 测试辅助 ====================

    private static Village villageWithCoords(Long id, String lat, String lon) {
        Village village = new Village();
        setId(village, id);
        village.setLatitude(new BigDecimal(lat));
        village.setLongitude(new BigDecimal(lon));
        return village;
    }

    /** Village 没有暴露 id 的 setter,测试里构造未持久化实体只能反射注入 */
    private static void setId(Village village, Long id) {
        try {
            Field field = Village.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(village, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法为测试村镇注入 id", e);
        }
    }
}