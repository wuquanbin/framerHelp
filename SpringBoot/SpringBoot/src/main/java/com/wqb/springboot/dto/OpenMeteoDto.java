package com.wqb.springboot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class OpenMeteoDto {

    private OpenMeteoDto() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForecastResponse {
        public String timezone;
        public Daily daily;
        public CurrentWeather current_weather;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Daily {
        public List<String> time;
        public List<Integer> weather_code;
        public List<Double> temperature_2m_max;
        public List<Double> temperature_2m_min;
        public List<Integer> precipitation_probability_max;
        public List<Integer> wind_direction_10m_dominant;
        public List<Double> wind_speed_10m_max;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentWeather {
        public double temperature;
        public int weathercode;
        public double windspeed;
        public int winddirection;
        public String time;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentResponse {
        public double latitude;
        public double longitude;
        public CurrentUnits current_units;
        public HourlyUnits hourly_units;
        public Current current;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentUnits {
        public String temperature_2m;
        public String apparent_temperature;
        public String relative_humidity_2m;
        public String wind_speed_10m;
        public String wind_direction_10m;
        public String surface_pressure;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HourlyUnits {
        public String visibility;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Current {
        public String time;
        public double temperature_2m;
        public double apparent_temperature;
        public int relative_humidity_2m;
        public double wind_speed_10m;
        public int wind_direction_10m;
        public int weather_code;
        public double surface_pressure;
        public double visibility;
        /**
         * 当前时次的降水概率(%)。
         * 用包装类型而非基本类型：Open-Meteo 在部分坐标/时段不返回该字段，
         * 用 null 才能区分"未提供"与"真实值为 0%"。
         */
        public Double precipitation_probability;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlertsResponse {
        public List<Alert> alerts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Alert {
        public String headline;
        public String msgType;
        public String severity;
        public String certainty;
        public String urgency;
        public String event;
        public String senderName;
        public String description;
        public String instruction;
        public String effective;
        public String onset;
        public String expires;
        public String ends;
        public List<String> areaDesc;
    }
}