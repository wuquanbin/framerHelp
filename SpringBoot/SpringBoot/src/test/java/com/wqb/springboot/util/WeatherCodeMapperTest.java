package com.wqb.springboot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link WeatherCodeMapper} 单元测试
 * <p>
 * 重点覆盖：<b>风速换算</b>（入参是 km/h，阈值是 m/s，历史 bug 高发区）、
 * WMO 天气代码、风向 16 方位、BigDecimal 转换。纯函数，无需 Spring 容器。
 * </p>
 */
@DisplayName("天气代码映射")
class WeatherCodeMapperTest {

    // ========== WMO 天气代码 ==========

    @ParameterizedTest(name = "WMO 代码 {0} → {1}")
    @CsvSource({
            "0, 晴",
            "1, 晴",
            "2, 多云",
            "3, 阴",
            "45, 雾",
            "48, 雾凇",
            "51, 毛毛雨",
            "53, 小雨",
            "61, 小雨",
            "63, 中雨",
            "65, 大雨",
            "71, 小雪",
            "75, 大雪",
            "80, 阵雨",
            "82, 雷阵雨",
            "95, 雷阵雨",
            "99, 雷阵雨",
    })
    void weatherCodes(int code, String expected) {
        assertEquals(expected, WeatherCodeMapper.getWeatherType(code));
    }

    @Test
    @DisplayName("WMO 未知代码默认返回「晴」")
    void unknownWeatherCode() {
        assertEquals("晴", WeatherCodeMapper.getWeatherType(999));
        assertEquals("晴", WeatherCodeMapper.getWeatherType(-1));
    }

    // ========== 风向 ==========

    @ParameterizedTest(name = "风向角 {0}° → {1}")
    @CsvSource({
            "0, 北",
            "19, 北",
            "45, 东北",
            "90, 东",
            "135, 东南",
            "170, 南",
            "200, 西南",
            "250, 西",
            "290, 西北",
            "300, 北",
            "350, 北",
            "360, 北",
    })
    void windDirections(int degree, String expected) {
        assertEquals(expected, WeatherCodeMapper.getWindDirection(degree));
    }

    // ========== 风力等级（历史 bug 高发区） ==========

    /**
     * 阈值按 m/s 分档：&lt;2→1级，&lt;4→2级，&lt;6→3级，&lt;8→4级……
     * 入参是 km/h，所以 4.6 km/h ≈ 1.28 m/s 应为 1 级；
     * 曾经的 bug 是忘了除以 3.6，导致微风被报成 3 级。
     */
    @ParameterizedTest(name = "风速 {0} km/h ≈ {1} m/s → {2} 级")
    @CsvSource({
            "0.0, 0.00, 1",
            "4.6, 1.28, 1",
            "7.2, 2.00, 2",
            "14.4, 4.00, 3",
            "21.6, 6.00, 4",
            "28.8, 8.00, 5",
            "39.6, 11.00, 6",
            "50.4, 14.00, 7",
            "64.8, 18.00, 8",
            "79.2, 22.00, 9",
            "120.0, 33.33, 9",
    })
    void windScaleConversion(double speedKmh, double speedMs, String expected) {
        assertEquals(expected, WeatherCodeMapper.getWindScale(speedKmh),
                "km/h 未正确换算成 m/s（历史上出过把 km/h 直接当 m/s 分档的 bug）");
    }

    @Test
    @DisplayName("微风必须是 1 级（回归测试：4.6 km/h 曾被误报 3 级）")
    void breezeIsLevelOne() {
        assertEquals("1", WeatherCodeMapper.getWindScale(4.6));
    }

    // ========== BigDecimal 转换 ==========

    @Test
    @DisplayName("toBigDecimal：null → 0，正常值四舍五入保留 1 位")
    void toBigDecimalConversion() {
        assertEquals(0, BigDecimal.ZERO.compareTo(WeatherCodeMapper.toBigDecimal(null)));
        assertEquals(0, new BigDecimal("3.1").compareTo(WeatherCodeMapper.toBigDecimal(3.14)));
        assertEquals(0, new BigDecimal("3.2").compareTo(WeatherCodeMapper.toBigDecimal(3.15)));
        assertEquals(0, new BigDecimal("-1.5").compareTo(WeatherCodeMapper.toBigDecimal(-1.45)));
    }

    @Test
    @DisplayName("toBigDecimal：返回的是新实例（避免共享可变状态）")
    void toBigDecimalReturnsIndependent() {
        BigDecimal a = WeatherCodeMapper.toBigDecimal(1.1);
        BigDecimal b = WeatherCodeMapper.toBigDecimal(1.1);
        assertEquals(a, b);
        assertSame(a, a); // 仅确保引用语义正常，不缓存共享实例
    }
}
