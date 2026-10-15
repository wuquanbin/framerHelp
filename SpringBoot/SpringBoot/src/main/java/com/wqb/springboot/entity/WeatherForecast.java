package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 天气预报（每村镇每天一条，15 天窗口）
 * <p>
 * 索引说明：查询模式固定为「按村镇取某日期之后的预报并按日期排序」，
 * 且每 30 分钟同步会先查后删，故用 (village_id, forecast_date) 联合索引。
 * </p>
 */
@Entity
@Table(
    name = "weather_forecast",
    indexes = {
        @Index(name = "idx_fc_village_date", columnList = "village_id, forecast_date")
    }
)
public class WeatherForecast extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false)
    private LocalDate forecastDate;

    @Column(nullable = false, length = 16)
    private String weekLabel;

    @Column(nullable = false, length = 32)
    private String weatherType;

    @Column(nullable = false)
    private BigDecimal lowTemperature;

    @Column(nullable = false)
    private BigDecimal highTemperature;

    @Column(nullable = false)
    private Integer precipitationProbability;

    @Column(nullable = false, length = 32)
    private String windDirection;

    @Column(nullable = false, length = 32)
    private String windScale;

    @Column(nullable = false, length = 255)
    private String farmingAdvice;

    public WeatherForecast() {
    }

    public WeatherForecast(
            Village village,
            LocalDate forecastDate,
            String weekLabel,
            String weatherType,
            BigDecimal lowTemperature,
            BigDecimal highTemperature,
            Integer precipitationProbability,
            String windDirection,
            String windScale,
            String farmingAdvice
    ) {
        this.village = village;
        this.forecastDate = forecastDate;
        this.weekLabel = weekLabel;
        this.weatherType = weatherType;
        this.lowTemperature = lowTemperature;
        this.highTemperature = highTemperature;
        this.precipitationProbability = precipitationProbability;
        this.windDirection = windDirection;
        this.windScale = windScale;
        this.farmingAdvice = farmingAdvice;
    }

    public Long getId() {
        return id;
    }

    public Village getVillage() {
        return village;
    }

    public LocalDate getForecastDate() {
        return forecastDate;
    }

    public String getWeekLabel() {
        return weekLabel;
    }

    public String getWeatherType() {
        return weatherType;
    }

    public BigDecimal getLowTemperature() {
        return lowTemperature;
    }

    public BigDecimal getHighTemperature() {
        return highTemperature;
    }

    public Integer getPrecipitationProbability() {
        return precipitationProbability;
    }

    public String getWindDirection() {
        return windDirection;
    }

    public String getWindScale() {
        return windScale;
    }

    public String getFarmingAdvice() {
        return farmingAdvice;
    }
}
