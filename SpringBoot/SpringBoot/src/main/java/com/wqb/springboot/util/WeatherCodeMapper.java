package com.wqb.springboot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 天气代码映射工具类
 * <p>
 * 提供 Open-Meteo 天气数据的纯函数转换,包括 WMO 天气代码转中文天气、
 * 风向角度转中文方向、风速转蒲福风力等级、Double 转 BigDecimal 等。
 * 所有方法均为静态方法,无副作用,不依赖任何 Spring 组件。
 * </p>
 */
public final class WeatherCodeMapper {

    private WeatherCodeMapper() {
        // 工具类,禁止实例化
    }

    /** WMO天气代码到中文天气描述的映射 */
    private static final Map<Integer, String> WEATHER_CODE_MAP;

    static {
        Map<Integer, String> map = new HashMap<>();
        map.put(0, "晴");
        map.put(1, "晴");
        map.put(2, "多云");
        map.put(3, "阴");
        map.put(45, "雾");
        map.put(48, "雾凇");
        map.put(51, "毛毛雨");
        map.put(53, "小雨");
        map.put(55, "小雨");
        map.put(56, "冻雨");
        map.put(57, "冻雨");
        map.put(61, "小雨");
        map.put(63, "中雨");
        map.put(65, "大雨");
        map.put(66, "冻雨");
        map.put(67, "冻雨");
        map.put(71, "小雪");
        map.put(73, "中雪");
        map.put(75, "大雪");
        map.put(77, "雪粒");
        map.put(80, "阵雨");
        map.put(81, "阵雨");
        map.put(82, "雷阵雨");
        map.put(85, "阵雪");
        map.put(86, "阵雪");
        map.put(95, "雷阵雨");
        map.put(96, "雷阵雨");
        map.put(99, "雷阵雨");
        WEATHER_CODE_MAP = Map.copyOf(map);
    }

    /**
     * 将 WMO 天气代码转换为中文天气描述
     *
     * @param weatherCode WMO 天气代码
     * @return 中文天气描述,未知代码默认返回"晴"
     */
    public static String getWeatherType(int weatherCode) {
        return WEATHER_CODE_MAP.getOrDefault(weatherCode, "晴");
    }

    /**
     * 将风向角度转换为中文方向
     *
     * @param degree 风向角度(0-360度)
     * @return 中文风向描述,如"北"、"东北"、"东"等
     */
    public static String getWindDirection(int degree) {
        if (degree >= 340 || degree < 20) return "北";
        if (degree >= 20 && degree < 60) return "东北";
        if (degree >= 60 && degree < 100) return "东";
        if (degree >= 100 && degree < 140) return "东南";
        if (degree >= 140 && degree < 180) return "南";
        if (degree >= 180 && degree < 220) return "西南";
        if (degree >= 220 && degree < 260) return "西";
        if (degree >= 260 && degree < 300) return "西北";
        if (degree >= 300 && degree < 340) return "北";
        return "北";
    }

    /**
     * 将风速转换为蒲福风力等级
     * <p>
     * 入参单位是 km/h —— Open-Meteo 的 wind_speed_10m / wind_speed_10m_max 默认就是 km/h。
     * 下面的分档阈值（2/4/6/8/11/14/18/22）是蒲福风级的 <b>m/s</b> 界限，
     * 所以必须先把 km/h 换算成 m/s；直接把 km/h 套进这套阈值会系统性偏大 2~3 级
     * （实测 4.6 km/h 的微风会被报成 3 级）。
     * </p>
     *
     * @param speedKmh 风速,单位 km/h
     * @return 风力等级字符串(1-9级)
     */
    public static String getWindScale(double speedKmh) {
        double speed = speedKmh / 3.6;
        if (speed < 2) return "1";
        if (speed < 4) return "2";
        if (speed < 6) return "3";
        if (speed < 8) return "4";
        if (speed < 11) return "5";
        if (speed < 14) return "6";
        if (speed < 18) return "7";
        if (speed < 22) return "8";
        return "9";
    }

    /**
     * 将 Double 值转换为 BigDecimal,保留1位小数
     *
     * @param value 双精度值,可为 null
     * @return BigDecimal 值,null 时返回 BigDecimal.ZERO
     */
    public static BigDecimal toBigDecimal(Double value) {
        if (value == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
