package com.wqb.springboot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class AmapDto {

    private AmapDto() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiveWeatherResponse {
        public String status;
        public String info;
        public List<Live> lives;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Live {
        public String province;
        public String city;
        public String adcode;
        public String weather;
        public String temperature;
        public String winddirection;
        public String windpower;
        public String humidity;
        public String reporttime;
        public String temperature_float;
        public String humidity_float;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForecastWeatherResponse {
        public String status;
        public String info;
        public List<Forecast> forecasts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Forecast {
        public String city;
        public String adcode;
        public String province;
        public String reporttime;
        public List<Cast> casts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cast {
        public String date;
        public String week;
        public String dayweather;
        public String nightweather;
        public String daytemp;
        public String nighttemp;
        public String daywind;
        public String nightwind;
        public String daypower;
        public String nightpower;
    }
}
