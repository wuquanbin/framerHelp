package com.wqb.springboot.util;

import java.math.BigDecimal;

/**
 * 气象指数计算工具类
 * <p>
 * 提供气象指数的纯函数计算,包括舒适度、紫外线、空气质量、降水概率、
 * 农事适宜度、农事建议等指数的估算,以及预警类型与等级的映射转换。
 * 所有方法均为静态方法,无副作用,不依赖任何 Spring 组件。
 * </p>
 */
public final class WeatherIndexCalculator {

    private WeatherIndexCalculator() {
        // 工具类,禁止实例化
    }

    // ========== 气象指数计算 ==========

    /**
     * 计算舒适度等级
     * <p>
     * 基于体感温度和湿度综合判断。
     * </p>
     *
     * @param temp         实际温度
     * @param apparentTemp 体感温度
     * @param humidity     相对湿度(%)
     * @return 舒适度等级描述(酷热/炎热/闷热/舒适/较舒适/寒冷/偏凉)
     */
    public static String calculateComfort(BigDecimal temp, BigDecimal apparentTemp, int humidity) {
        double at = apparentTemp.doubleValue();
        if (at >= 35) return "酷热";
        if (at >= 32) return "炎热";
        if (at >= 28) return "闷热";
        if (at >= 22 && at <= 26 && humidity >= 40 && humidity <= 70) return "舒适";
        if (at >= 18 && at < 22) return "较舒适";
        if (at < 10) return "寒冷";
        if (at < 18) return "偏凉";
        return "较舒适";
    }

    /**
     * 估算紫外线强度
     * <p>
     * 根据天气现象简单估算,晴天为强,多云为中等,阴雨雪为弱。
     * </p>
     *
     * @param weatherType 天气现象描述
     * @return 紫外线强度等级(强/中等/弱)
     */
    public static String calculateUVIndex(String weatherType) {
        if (weatherType == null) return "弱";
        if (weatherType.contains("晴")) return "强";
        if (weatherType.contains("多云")) return "中等";
        if (weatherType.contains("阴") || weatherType.contains("雨") || weatherType.contains("雪")) return "弱";
        return "中等";
    }

    /**
     * 估算空气质量等级
     * <p>
     * 简单算法:夏季高温高湿可能臭氧偏高,雨天空气好。
     * 实际使用建议接入和风天气/高德AQI API获取真实数据。
     * </p>
     *
     * @param temp    温度
     * @param humidity 相对湿度(%)
     * @return 空气质量等级(优/良/轻度污染)
     */
    public static String estimateAQI(BigDecimal temp, int humidity) {
        double t = temp.doubleValue();
        if (humidity > 85 && t < 28) return "优";
        if (t > 33 && humidity < 50) return "良";
        if (t > 35 && humidity > 70) return "轻度污染";
        return "良";
    }

    /**
     * 根据天气现象估算降水概率
     *
     * @param dayWeather   白天天气现象
     * @param nightWeather 夜间天气现象
     * @return 降水概率(%)
     */
    public static int estimatePrecipitation(String dayWeather, String nightWeather) {
        String w = (dayWeather != null ? dayWeather : "") + (nightWeather != null ? nightWeather : "");
        if (w.contains("暴")) return 90;
        if (w.contains("大到暴雨") || w.contains("大雨")) return 80;
        if (w.contains("中雨") || w.contains("雷")) return 65;
        if (w.contains("小雨") || w.contains("阵雨")) return 45;
        if (w.contains("多云")) return 20;
        if (w.contains("阴")) return 15;
        return 5;
    }

    /**
     * 计算农事适宜度指数
     * <p>
     * 综合天气现象、温度和风力等级判断农事适宜度。
     * </p>
     *
     * @param weather   天气现象
     * @param temp      温度
     * @param windPower 风力描述(如"3级")
     * @return 农事适宜度描述(不宜农事/不宜户外作业/需注意防雨/注意防护/适宜农事/一般)
     */
    public static String calculateFarmIndex(String weather, BigDecimal temp, String windPower) {
        if (weather == null) return "适宜";
        if (weather.contains("暴") || weather.contains("雷") || weather.contains("大雨")) return "不宜农事";
        // 风力等级是半结构化文本（"3级"、"≤3级"、"微风"），取不到数字就当风力未知，
        // 不做风力相关的降级判断。这里刻意不用 try/catch 当流程控制：
        // 空 catch 会把「风力字段格式变了」这件事彻底藏起来，排查时无从下手。
        if (windPower != null) {
            String digits = windPower.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && Integer.parseInt(digits) >= 6) {
                return "不宜户外作业";
            }
        }
        if (weather.contains("中雨")) return "需注意防雨";
        double t = temp.doubleValue();
        if (t > 35 || t < 5) return "注意防护";
        if (weather.contains("晴") || weather.contains("多云")) return "适宜农事";
        return "一般";
    }

    /**
     * 生成农事建议
     * <p>
     * 结合白天夜间天气和降水概率,生成针对性的农事建议文本。
     * </p>
     *
     * @param dayWeather   白天天气
     * @param nightWeather 夜间天气
     * @param wind         风向
     * @param power        风力
     * @param precipProb   降水概率(%)
     * @return 农事建议文本
     */
    public static String generateFarmingAdvice(String dayWeather, String nightWeather, String wind, String power, int precipProb) {
        String w = dayWeather != null ? dayWeather : "";
        if (w.contains("暴") || w.contains("大到暴雨")) {
            return "强降水天气,立即疏通农田排水沟渠,停止田间作业,注意防汛安全";
        }
        if (w.contains("雷")) {
            return "雷雨天气,避免户外农事操作,人员及时到安全场所躲避,防范雷击";
        }
        if (precipProb > 60 || w.contains("中雨")) {
            return "有明显降水,推迟施肥喷药作业,做好田间排水准备,成熟作物及时抢收";
        }
        if (w.contains("小雨") || w.contains("阵雨")) {
            return "有分散性阵雨,外出携带雨具,喷药施肥可适当推迟,雨后注意病虫害防治";
        }
        if (w.contains("晴")) {
            return "天气晴好,适宜各类田间作业,可进行施肥、喷药、移栽等农事活动,注意防暑降温";
        }
        if (w.contains("多云")) {
            return "多云天气,温湿度适宜,适合开展田间管理、采摘、修剪等农事活动";
        }
        if (w.contains("阴")) {
            return "阴天无强日照,适宜移栽、补种等作业,大棚注意通风降湿";
        }
        return "天气条件一般,合理安排农事活动,关注天气预报变化";
    }

    // ========== 预警映射转换 ==========

    /**
     * 预警类型映射
     * <p>
     * 将 Open-Meteo 的英文预警事件映射为内部预警类型标识。
     * </p>
     *
     * @param event 预警事件描述(英文或中文)
     * @return 内部预警类型标识(rainstorm/wind/thunder/heat/cold/fog/typhoon/drought/snow/hail/frost/other)
     */
    public static String mapAlertEvent(String event) {
        if (event == null) return "other";
        String lower = event.toLowerCase();
        if (lower.contains("rain") || lower.contains("雨") || lower.contains("precipitation") || lower.contains("暴雨")) return "rainstorm";
        if (lower.contains("wind") || lower.contains("风") || lower.contains("gale") || lower.contains("大风")) return "wind";
        if (lower.contains("thunder") || lower.contains("雷")) return "thunder";
        if (lower.contains("heat") || lower.contains("高温") || lower.contains("hot")) return "heat";
        if (lower.contains("cold") || lower.contains("寒") || lower.contains("frost") || lower.contains("寒潮")) return "cold";
        if (lower.contains("fog") || lower.contains("雾") || lower.contains("haze") || lower.contains("霾")) return "fog";
        if (lower.contains("typhoon") || lower.contains("台风") || lower.contains("cyclone")) return "typhoon";
        if (lower.contains("drought") || lower.contains("旱")) return "drought";
        if (lower.contains("snow") || lower.contains("雪")) return "snow";
        if (lower.contains("hail") || lower.contains("冰雹")) return "hail";
        if (lower.contains("frost") || lower.contains("霜冻")) return "frost";
        return "other";
    }

    /**
     * 预警等级映射
     * <p>
     * 将 Open-Meteo 的英文严重程度映射为内部颜色等级。
     * </p>
     *
     * @param severity 严重程度描述(英文或中文)
     * @return 颜色等级(red/orange/yellow/blue)
     */
    public static String mapSeverity(String severity) {
        if (severity == null) return "yellow";
        String lower = severity.toLowerCase();
        if (lower.contains("extreme") || lower.contains("红色") || lower.contains("红")) return "red";
        if (lower.contains("severe") || lower.contains("橙色") || lower.contains("橙")) return "orange";
        if (lower.contains("moderate") || lower.contains("黄色") || lower.contains("黄")) return "yellow";
        if (lower.contains("minor") || lower.contains("蓝色") || lower.contains("蓝")) return "blue";
        return "yellow";
    }

    /**
     * 预警等级中文标签
     *
     * @param severity 颜色等级(red/orange/yellow/blue)
     * @return 中文标签(红色/橙色/黄色/蓝色);未知等级返回空字符串
     */
    public static String getSeverityLabel(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "red" -> "红色";
            case "orange" -> "橙色";
            case "yellow" -> "黄色";
            case "blue" -> "蓝色";
            default -> "";
        };
    }
}
