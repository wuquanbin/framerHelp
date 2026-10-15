package com.wqb.springboot.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class WeatherResponseDto {

    private WeatherResponseDto() {
    }

    public record VillageOption(
            Long id,
            String code,
            String name,
            String mainCrops,
            String terrain,
            String assistanceLevel
    ) {
    }

    public record CurrentWeather(
            String villageCode,
            String villageName,
            String weatherType,
            BigDecimal temperature,
            BigDecimal apparentTemperature,
            Integer humidity,
            String windDirection,
            String windScale,
            String airQuality,
            Integer precipitationProbability,
            String comfortLevel,
            String uvLevel,
            String farmIndex,
            BigDecimal pressure,
            BigDecimal visibility,
            LocalDateTime observationTime
    ) {
    }

    public record ForecastDay(
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
    }

    public record FarmAdviceItem(
            String title,
            String summary,
            String recommendation,
            String priorityTag,
            String iconKey,
            Integer priority
    ) {
    }

    public record CropProfileItem(
            Long id,
            String villageCode,
            String villageName,
            String cropName,
            String cropType,
            String plantingArea,
            String growthStage,
            Boolean isMainCrop,
            String suitableWeather,
            String riskWeather,
            Integer riskLevel,
            String farmingNotes,
            String matchReason,
            String imageUrl
    ) {
    }

    public record DisasterRecordItem(
            Long id,
            String villageCode,
            String villageName,
            String disasterName,
            String affectedArea,
            String disasterType,
            String triggerWeather,
            String description,
            String remedy,
            Integer severity
    ) {
    }

    public record WarningNoticeItem(
            String warningType,
            String severity,
            String title,
            String content,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            String status,
            String impactArea,
            String defenseGuidance
    ) {
    }

    public record ModuleStatusItem(
            String code,
            String name,
            String status,
            Integer progress,
            String description
    ) {
    }

    public record DashboardOverview(
            VillageOption village,
            CurrentWeather currentWeather,
            List<ForecastDay> forecast,
            List<FarmAdviceItem> farmAdvices,
            List<CropProfileItem> cropProfiles,
            List<DisasterRecordItem> disasterRecords,
            List<WarningNoticeItem> activeWarnings,
            List<ModuleStatusItem> moduleStatus
    ) {
    }

    public record AgriReport(
            String reportDate,
            String weatherSummary,
            String farmingAdvice,
            String riskLevel,
            List<DisasterRecordItem> disasterRecords,
            List<CropProfileItem> cropProfiles
    ) {
    }

    // ========== Crop Growth Engine ==========

    public record CropReminder(
            String cropName,
            String cropType,
            String growthStage,
            String weatherImpact,
            String suggestion,
            String urgency  // "紧急"/"重要"/"一般"
    ) {
    }

    public record DailyFarmPlan(
            String date,
            String weekLabel,
            String weatherType,
            List<String> activities,
            String caution
    ) {
    }

    public record DisasterRemedy(
            String disasterType,
            String cropName,
            String remedy,
            String timeline  // "立即"/"24小时内"/"3天内"
    ) {
    }
}
