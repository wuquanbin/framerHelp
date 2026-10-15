package com.wqb.springboot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WeatherIndexCalculator} 单元测试
 * <p>
 * 覆盖气象指数计算的各档位边界：舒适度、紫外线、AQI、降水概率估算、
 * 农事适宜度、农事建议与预警映射。全部为纯函数，无需 Spring 容器。
 * </p>
 */
@DisplayName("气象指数计算")
class WeatherIndexCalculatorTest {

    // ========== 舒适度 ==========

    @Test
    @DisplayName("舒适度：体感温度各档位")
    void comfortLevels() {
        assertEquals("酷热", WeatherIndexCalculator.calculateComfort(d(30), d(36), 50));
        assertEquals("炎热", WeatherIndexCalculator.calculateComfort(d(30), d(33), 50));
        assertEquals("闷热", WeatherIndexCalculator.calculateComfort(d(28), d(29), 60));
        assertEquals("寒冷", WeatherIndexCalculator.calculateComfort(d(5), d(5), 50));
        assertEquals("偏凉", WeatherIndexCalculator.calculateComfort(d(15), d(15), 50));
    }

    @Test
    @DisplayName("舒适度：22~26°C 且湿度 40~70 才算「舒适」")
    void comfortSweetSpot() {
        assertEquals("舒适", WeatherIndexCalculator.calculateComfort(d(24), d(24), 55));
        // 湿度出界 → 不满足「舒适」，落回「较舒适」
        assertEquals("较舒适", WeatherIndexCalculator.calculateComfort(d(24), d(24), 80));
        assertEquals("较舒适", WeatherIndexCalculator.calculateComfort(d(24), d(24), 30));
    }

    @Test
    @DisplayName("舒适度：26~28°C 之间的空档回落到「较舒适」")
    void comfortGapFallsBack() {
        assertEquals("较舒适", WeatherIndexCalculator.calculateComfort(d(27), d(27), 50));
    }

    // ========== 紫外线 ==========

    @ParameterizedTest(name = "「{0}」→ 紫外线 {1}")
    @CsvSource({
            "晴, 强",
            "晴转多云, 强",
            "多云, 中等",
            "阴, 弱",
            "小雨, 弱",
            "中雪, 弱",
            "雾凇, 中等",
    })
    void uvIndex(String weather, String expected) {
        assertEquals(expected, WeatherIndexCalculator.calculateUVIndex(weather));
    }

    @Test
    @DisplayName("紫外线：null 天气按弱处理")
    void uvNullWeather() {
        assertEquals("弱", WeatherIndexCalculator.calculateUVIndex(null));
    }

    // ========== AQI 估算 ==========

    @Test
    @DisplayName("AQI：高湿低温为优、高温干燥为良、高温高湿为轻度污染")
    void aqiEstimates() {
        assertEquals("优", WeatherIndexCalculator.estimateAQI(d(26), 90));
        assertEquals("良", WeatherIndexCalculator.estimateAQI(d(34), 45));
        assertEquals("轻度污染", WeatherIndexCalculator.estimateAQI(d(36), 75));
        assertEquals("良", WeatherIndexCalculator.estimateAQI(d(28), 60));
    }

    // ========== 降水概率估算 ==========

    @ParameterizedTest(name = "白天「{0}」夜间「{1}」→ 降水概率 {2}%")
    @CsvSource({
            "暴雨, , 90",
            // 注意：「大到暴雨」含「暴」，会先命中 90 分支（分支顺序：暴 > 大到暴雨/大雨）
            "大到暴雨, , 90",
            "大雨, , 80",
            "中雨, , 65",
            "雷阵雨, , 65",
            "小雨, , 45",
            "阵雨, , 45",
            "多云, , 20",
            "阴, , 15",
            "晴, 晴, 5",
    })
    void precipitationEstimates(String day, String night, int expected) {
        assertEquals(expected, WeatherIndexCalculator.estimatePrecipitation(day, night));
    }

    // ========== 农事适宜度 ==========

    @Test
    @DisplayName("农事适宜度：恶劣天气优先级最高")
    void farmIndexBadWeather() {
        assertEquals("不宜农事", WeatherIndexCalculator.calculateFarmIndex("暴雨", d(30), "3级"));
        assertEquals("不宜农事", WeatherIndexCalculator.calculateFarmIndex("雷阵雨", d(30), "3级"));
        assertEquals("不宜农事", WeatherIndexCalculator.calculateFarmIndex("大雨", d(30), "3级"));
    }

    @Test
    @DisplayName("农事适宜度：6 级及以上大风不宜户外作业")
    void farmIndexStrongWind() {
        assertEquals("不宜户外作业", WeatherIndexCalculator.calculateFarmIndex("晴", d(30), "6级"));
        assertEquals("不宜户外作业", WeatherIndexCalculator.calculateFarmIndex("晴", d(30), "9级"));
        // 5 级仍在可作业范围
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("晴", d(30), "5级"));
    }

    @Test
    @DisplayName("农事适宜度：风力字段不是数字时不影响判断")
    void farmIndexInvalidWindPower() {
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("晴", d(30), "微风"));
    }

    @Test
    @DisplayName("农事适宜度：中雨防雨 / 极端温度防护 / 一般天气")
    void farmIndexOtherLevels() {
        assertEquals("需注意防雨", WeatherIndexCalculator.calculateFarmIndex("中雨", d(25), "2级"));
        assertEquals("注意防护", WeatherIndexCalculator.calculateFarmIndex("晴", d(40), "2级"));
        assertEquals("注意防护", WeatherIndexCalculator.calculateFarmIndex("晴", d(3), "2级"));
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("晴", d(28), "2级"));
        assertEquals("一般", WeatherIndexCalculator.calculateFarmIndex("阴", d(25), "2级"));
        assertEquals("适宜", WeatherIndexCalculator.calculateFarmIndex(null, d(25), "2级"));
    }

    // ========== 农事建议文案 ==========

    @Test
    @DisplayName("农事建议：按天气类型给出对应文案")
    void farmingAdvices() {
        String storm = WeatherIndexCalculator.generateFarmingAdvice("暴雨", "阴", "东南", "3级", 90);
        assertEquals("强降水天气,立即疏通农田排水沟渠,停止田间作业,注意防汛安全", storm);

        String thunder = WeatherIndexCalculator.generateFarmingAdvice("雷阵雨", "晴", "南", "2级", 30);
        assertEquals("雷雨天气,避免户外农事操作,人员及时到安全场所躲避,防范雷击", thunder);

        String rain = WeatherIndexCalculator.generateFarmingAdvice("多云", "中雨", "东", "2级", 70);
        assertEquals("有明显降水,推迟施肥喷药作业,做好田间排水准备,成熟作物及时抢收", rain);

        String sunny = WeatherIndexCalculator.generateFarmingAdvice("晴", "晴", "东北", "2级", 10);
        assertEquals("天气晴好,适宜各类田间作业,可进行施肥、喷药、移栽等农事活动,注意防暑降温", sunny);
    }

    // ========== 预警映射 ==========

    @ParameterizedTest(name = "预警事件「{0}」→ {1}")
    @CsvSource({
            "Rainstorm, rainstorm",
            "Heavy Rain, rainstorm",
            "大风, wind",
            "Gale, wind",
            "Thunderstorm, thunder",
            "Extreme heat, heat",
            "高温, heat",
            "Cold wave, cold",
            "寒潮, cold",
            "Dense fog, fog",
            "Typhoon, typhoon",
            // ⚠️ 记录现状：中文「台风」含「风」字，会先命中 wind 分支而不是 typhoon
            //    （mapAlertEvent 的分支顺序问题，英文 Typhoon 才能正确归类；如需修正应把
            //     typhoon 判断挪到 wind 之前——当前按现有行为断言）
            "台风, wind",
            "Drought, drought",
            "Snow, snow",
            "Hail, hail",
            "霜冻, frost",
            "完全未知事件, other",
    })
    void alertEventMapping(String event, String expected) {
        assertEquals(expected, WeatherIndexCalculator.mapAlertEvent(event));
    }

    @Test
    @DisplayName("预警事件映射：null 归入 other")
    void alertEventNull() {
        assertEquals("other", WeatherIndexCalculator.mapAlertEvent(null));
    }

    @ParameterizedTest(name = "严重程度「{0}」→ {1}")
    @CsvSource({
            "Extreme, red",
            "红色, red",
            "Severe, orange",
            "橙色, orange",
            "Moderate, yellow",
            "黄色, yellow",
            "Minor, blue",
            "蓝色, blue",
            "unknown, yellow",
    })
    void severityMapping(String severity, String expected) {
        assertEquals(expected, WeatherIndexCalculator.mapSeverity(severity));
    }

    @Test
    @DisplayName("预警等级中文标签")
    void severityLabels() {
        assertEquals("红色", WeatherIndexCalculator.getSeverityLabel("red"));
        assertEquals("橙色", WeatherIndexCalculator.getSeverityLabel("orange"));
        assertEquals("黄色", WeatherIndexCalculator.getSeverityLabel("yellow"));
        assertEquals("蓝色", WeatherIndexCalculator.getSeverityLabel("blue"));
        assertEquals("", WeatherIndexCalculator.getSeverityLabel("purple"));
        assertEquals("", WeatherIndexCalculator.getSeverityLabel(null));
    }

    // ========== 农事适宜度：风力解析（这里原来是空 catch，改成显式判断后补测） ==========

    @Test
    @DisplayName("风力为空时不参与判断，不抛异常")
    void farmIndexWithNullWind() {
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("晴", d(25), null));
    }

    @Test
    @DisplayName("风力文本里没有数字时按风力未知处理")
    void farmIndexWithNonNumericWind() {
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("晴", d(25), "微风"));
    }

    @Test
    @DisplayName("风力达到 6 级判定为不宜户外作业")
    void farmIndexWithStrongWind() {
        assertEquals("不宜户外作业", WeatherIndexCalculator.calculateFarmIndex("多云", d(25), "7级"));
        // 5 级是边界下沿，还不触发
        assertEquals("适宜农事", WeatherIndexCalculator.calculateFarmIndex("多云", d(25), "5级"));
    }

    private static BigDecimal d(double v) {
        return BigDecimal.valueOf(v);
    }
}
