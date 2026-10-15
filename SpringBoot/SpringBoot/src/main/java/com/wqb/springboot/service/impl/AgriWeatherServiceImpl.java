package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import com.wqb.springboot.dto.WeatherResponseDto;
import com.wqb.springboot.dto.WeatherResponseDto.*;
import com.wqb.springboot.entity.*;
import com.wqb.springboot.repository.*;
import com.wqb.springboot.service.AgriWeatherService;
import com.wqb.springboot.service.CropGrowthEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 乡村农业气象帮扶服务实现类
 * <p>
 * 作为核心业务服务，整合了多个Repository的数据访问，
 * 提供各村落的实时天气、天气预报、农事建议、作物档案、
 * 灾害记录、预警通知和模块状态等数据查询与聚合功能。
 * </p>
 */
@Service
public class AgriWeatherServiceImpl implements AgriWeatherService {

    private static final Logger log = LoggerFactory.getLogger(AgriWeatherServiceImpl.class);

    /** 默认数据发布来源 */
    private static final String DEFAULT_PUBLISHER = "乡村气象帮扶站";

    private final VillageRepository villageRepository;
    private final CropGrowthEngine cropGrowthEngine;
    private final WeatherObservationRepository weatherObservationRepository;
    private final WeatherForecastRepository weatherForecastRepository;
    private final FarmAdviceRepository farmAdviceRepository;
    private final CropProfileRepository cropProfileRepository;
    private final DisasterRecordRepository disasterRecordRepository;
    private final WarningNoticeRepository warningNoticeRepository;

    public AgriWeatherServiceImpl(
            VillageRepository villageRepository,
            CropGrowthEngine cropGrowthEngine,
            WeatherObservationRepository weatherObservationRepository,
            WeatherForecastRepository weatherForecastRepository,
            FarmAdviceRepository farmAdviceRepository,
            CropProfileRepository cropProfileRepository,
            DisasterRecordRepository disasterRecordRepository,
            WarningNoticeRepository warningNoticeRepository
    ) {
        this.villageRepository = villageRepository;
        this.cropGrowthEngine = cropGrowthEngine;
        this.weatherObservationRepository = weatherObservationRepository;
        this.weatherForecastRepository = weatherForecastRepository;
        this.farmAdviceRepository = farmAdviceRepository;
        this.cropProfileRepository = cropProfileRepository;
        this.disasterRecordRepository = disasterRecordRepository;
        this.warningNoticeRepository = warningNoticeRepository;
    }

    // ========== Villages ==========

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_VILLAGES)
    public List<VillageOption> listVillages() {
        return villageRepository.findAllByOrderByIdAsc().stream()
                .map(v -> new VillageOption(v.getId(), v.getCode(), v.getName(),
                        v.getMainCrops(), v.getTerrain(), v.getAssistanceLevel()))
                .toList();
    }

    /**
     * 根据村落编码解析村落实体
     * <p>
     * 若编码为空则返回第一个村落（默认村落）。
     * </p>
     *
     * @param villageCode 村落编码，可为空
     * @return 村落实体
     * @throws RuntimeException 村落不存在或系统未初始化时抛出
     */
    private Village resolveVillage(String villageCode) {
        if (villageCode != null && !villageCode.isBlank()) {
            return villageRepository.findByCode(villageCode)
                    .orElseThrow(() -> new RuntimeException("村落信息不存在: " + villageCode));
        }
        return villageRepository.findAllByOrderByIdAsc().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("系统尚未初始化村落数据"));
    }

    // ========== Current Weather ==========

    @Override
    public CurrentWeather getCurrentWeather(String villageCode) {
        Village village = resolveVillage(villageCode);
        WeatherObservation obs = weatherObservationRepository
                .findFirstByVillageOrderByObservationTimeDesc(village)
                .orElse(null);

        // 无观测数据时返回默认占位
        if (obs == null) {
            return new CurrentWeather(
                    village.getCode(), village.getName(),
                    "暂无", BigDecimal.ZERO, BigDecimal.ZERO, 0,
                    "-", "-", "暂无", 0,
                    "暂无", "暂无", "暂无",
                    null, null, null
            );
        }

        return new CurrentWeather(
                village.getCode(),
                village.getName(),
                obs.getWeatherType(),
                obs.getTemperature(),
                obs.getApparentTemperature(),
                obs.getHumidity(),
                obs.getWindDirection(),
                obs.getWindScale(),
                obs.getAirQuality(),
                obs.getPrecipitationProbability(),
                obs.getComfortLevel(),
                obs.getUvLevel(),
                obs.getFarmIndex(),
                obs.getPressure(),
                obs.getVisibility(),
                obs.getObservationTime()
        );
    }

    // ========== Forecast ==========

    @Override
    public List<ForecastDay> getForecast(String villageCode) {
        Village village = resolveVillage(villageCode);
        return weatherForecastRepository.findByVillageOrderByForecastDateAsc(village).stream()
                .map(f -> new ForecastDay(
                        f.getForecastDate(),
                        f.getWeekLabel(),
                        f.getWeatherType(),
                        f.getLowTemperature(),
                        f.getHighTemperature(),
                        f.getPrecipitationProbability(),
                        f.getWindDirection(),
                        f.getWindScale(),
                        f.getFarmingAdvice()
                ))
                .toList();
    }

    // ========== Farm Advices ==========

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_FARM_ADVICES, key = "#villageCode ?: 'default'")
    public List<FarmAdviceItem> getFarmAdvices(String villageCode) {
        Village village = resolveVillage(villageCode);
        return farmAdviceRepository.findByVillageOrderByPriorityDescIdAsc(village).stream()
                .map(s -> new FarmAdviceItem(
                        s.getTitle(),
                        s.getSummary(),
                        s.getRecommendation(),
                        s.getPriorityTag(),
                        s.getIconKey(),
                        s.getPriority()
                ))
                .toList();
    }

    // ========== Crop Profiles ==========

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_CROP_PROFILES, key = "#villageCode ?: 'default'")
    public List<CropProfileItem> getCropProfiles(String villageCode) {
        Village village = resolveVillage(villageCode);
        return cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village).stream()
                .map(c -> new CropProfileItem(
                        c.getId(),
                        village.getCode(),
                        village.getName(),
                        c.getCropName(),
                        c.getCropType(),
                        c.getPlantingArea(),
                        c.getGrowthStage(),
                        c.getIsMainCrop(),
                        c.getSuitableWeather(),
                        c.getRiskWeather(),
                        c.getRiskLevel(),
                        c.getFarmingNotes(),
                        buildMatchReason(c),
                        c.getImageUrl()
                ))
                .toList();
    }

    /**
     * 构建作物的匹配原因描述
     *
     * @param c 作物档案实体
     * @return 匹配原因文本，如"主要作物；适宜晴好天气；高风险等级"
     */
    private String buildMatchReason(CropProfile c) {
        StringBuilder sb = new StringBuilder();
        if (c.getIsMainCrop()) {
            sb.append("主要作物；");
        }
        if (c.getSuitableWeather() != null && !c.getSuitableWeather().isBlank()) {
            sb.append("适宜").append(c.getSuitableWeather()).append("；");
        }
        if (c.getRiskLevel() >= 4) {
            sb.append("高风险等级");
        }
        return sb.toString();
    }

    // ========== Disaster Records ==========

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_DISASTERS, key = "#villageCode ?: 'default'")
    public List<DisasterRecordItem> getDisasterRecords(String villageCode) {
        Village village = resolveVillage(villageCode);
        return disasterRecordRepository.findByVillageOrderBySeverityDescIdAsc(village).stream()
                .map(r -> new DisasterRecordItem(
                        r.getId(),
                        village.getCode(),
                        village.getName(),
                        r.getDisasterName(),
                        r.getAffectedArea(),
                        r.getDisasterType(),
                        r.getTriggerWeather(),
                        r.getDescription(),
                        r.getRemedy(),
                        r.getSeverity()
                ))
                .toList();
    }

    // ========== Active Warnings ==========

    @Override
    public List<WarningNoticeItem> listActiveWarnings() {
        // 读取侧再挡一道：即使清理任务尚未执行，也不把已过期的预警当作"生效中"返回
        return warningNoticeRepository.findEffectiveByStatus("active", LocalDateTime.now()).stream()
                .map(this::toWarning)
                .toList();
    }

    /**
     * 将预警实体转换为DTO
     *
     * @param w 预警通知实体
     * @return 预警通知DTO
     */
    private WarningNoticeItem toWarning(WarningNotice w) {
        return new WarningNoticeItem(
                w.getWarningType(),
                w.getSeverity(),
                w.getTitle(),
                w.getContent(),
                w.getIssuedAt(),
                w.getExpiresAt(),
                w.getStatus(),
                w.getImpactArea(),
                w.getDefenseGuidance()
        );
    }

    // ========== Module Status ==========

    @Override
    public List<ModuleStatusItem> listModuleStatus() {
        List<ModuleStatusItem> modules = new ArrayList<>();
        modules.add(new ModuleStatusItem("weather-current", "实时气象数据", "RUNNING", 100,
                "已接入各村落实时天气观测"));
        modules.add(new ModuleStatusItem("weather-forecast", "多日预报", "RUNNING", 100,
                "支持3日/小时级预报数据"));
        modules.add(new ModuleStatusItem("warning-center", "预警中心", "RUNNING", 100,
                "暴雨/台风/高温等预警接入"));
        modules.add(new ModuleStatusItem("farm-engine", "农事建议引擎", "RUNNING", 90,
                "场景化农事建议+灾害风险提示"));
        modules.add(new ModuleStatusItem("crop-archive", "作物档案管理", "RUNNING", 100,
                "主要农作物生长阶段与风险档案"));
        modules.add(new ModuleStatusItem("wechat-push", "微信推送", "PENDING", 20,
                "订阅帮扶消息推送服务待开通"));
        modules.add(new ModuleStatusItem("weather-device", "气象设备数据", "PENDING", 10,
                "气象站设备数据对接待实施"));
        return modules;
    }

    // ========== Dashboard Overview ==========

    @Override
    // 含实况数值，天气每 30 分钟同步一次，这里只缓存 2 分钟：
    // 既削掉重复查询，也不会让人觉得"刷新半天数字不变"
    @Cacheable(cacheNames = CacheConfig.CACHE_OVERVIEW, key = "#villageCode ?: 'default'")
    public DashboardOverview getDashboardOverview(String villageCode) {
        Village village = resolveVillage(villageCode);
        VillageOption villageOption = new VillageOption(
                village.getId(), village.getCode(), village.getName(),
                village.getMainCrops(), village.getTerrain(), village.getAssistanceLevel()
        );

        // 灾害分布用全局统计（所有村镇），不受选中村镇影响
        List<DisasterRecordItem> allDisasters = disasterRecordRepository.findAll().stream()
                .map(r -> new DisasterRecordItem(
                        r.getId(),
                        r.getVillage().getCode(),
                        r.getVillage().getName(),
                        r.getDisasterName(),
                        r.getAffectedArea(),
                        r.getDisasterType(),
                        r.getTriggerWeather(),
                        r.getDescription(),
                        r.getRemedy(),
                        r.getSeverity()
                ))
                .toList();

        return new DashboardOverview(
                villageOption,
                getCurrentWeather(villageCode),
                getForecast(villageCode),
                getFarmAdvices(villageCode),
                getCropProfiles(villageCode),
                allDisasters,
                listActiveWarnings(),
                listModuleStatus()
        );
    }

    // ========== Agri Report ==========

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_AGRI_REPORT, key = "#villageCode ?: 'default'")
    public AgriReport getAgriReport(String villageCode) {
        Village village = resolveVillage(villageCode);
        CurrentWeather current = getCurrentWeather(villageCode);

        String weatherSummary = buildWeatherSummary(current);
        String farmingAdvice = buildFarmingAdvice(current);
        String riskLevel = assessRiskLevel(current, village);

        List<DisasterRecordItem> disasterRecords = getDisasterRecords(villageCode);
        List<CropProfileItem> cropProfiles = getCropProfiles(villageCode);

        return new AgriReport(
                LocalDate.now().toString(),
                weatherSummary,
                farmingAdvice,
                riskLevel,
                disasterRecords,
                cropProfiles
        );
    }

    /**
     * 构建天气概况文本
     *
     * @param w 当前天气数据
     * @return 天气概况字符串
     */
    private String buildWeatherSummary(CurrentWeather w) {
        if (w == null || w.weatherType() == null) {
            return "暂无天气数据";
        }
        return String.format("当前%s，温度%s℃，体感%s℃，%s%s，降水概率%d%%",
                w.weatherType(), w.temperature(), w.apparentTemperature(),
                w.windDirection(), w.windScale(), w.precipitationProbability());
    }

    /**
     * 根据天气状况生成农事建议
     * <p>
     * 基于当前天气生成基础建议，并尝试追加作物生长引擎中的紧急提醒。
     * </p>
     *
     * @param w 当前天气数据
     * @return 农事建议文本
     */
    private String buildFarmingAdvice(CurrentWeather w) {
        String baseAdvice;
        if (w == null || w.weatherType() == null) {
            baseAdvice = "建议关注最新天气预报，合理安排农事活动";
        } else {
            String type = w.weatherType();
            if (type.contains("暴雨") || type.contains("大雨")) {
                baseAdvice = "降水较强，注意农田排水防涝，避免低洼地带作业，关注作物倒伏风险";
            } else if (type.contains("雨")) {
                baseAdvice = "有降水，适当推迟施肥打药，注意防雨棚加固，雨后及时排涝";
            } else if (type.contains("台风")) {
                baseAdvice = "台风影响，加固大棚和农用设施，抢收成熟作物，停止户外农事作业";
            } else if (type.contains("高温") || "炙热".equals(type)) {
                baseAdvice = "高温天气，注意农作物灌溉补水，合理遮阳，避免正午时段田间作业";
            } else if (type.contains("寒") || type.contains("霜")) {
                baseAdvice = "低温寒潮，做好农作物防冻措施，覆盖保温膜，注意温室大棚保温";
            } else if (w.precipitationProbability() != null && w.precipitationProbability() < 20) {
                baseAdvice = "天气良好，适合田间管理、施肥打药和作物采收等农事活动";
            } else {
                baseAdvice = "请根据实际天气情况合理安排农事活动，注意关注天气变化";
            }
        }

        // 尝试追加作物生长引擎的紧急提醒
        try {
            if (w != null && w.villageCode() != null) {
                List<CropReminder> reminders = cropGrowthEngine.generateCropReminders(w.villageCode());
                for (CropReminder r : reminders) {
                    if ("紧急".equals(r.urgency())) {
                        baseAdvice += "；【紧急】" + r.cropName() + "(" + r.growthStage() + ")：" + r.suggestion();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("追加作物生长引擎提醒失败: {}", e.getMessage());
        }

        return baseAdvice;
    }

    /**
     * 评估农业风险等级
     * <p>
     * 存在活跃预警时为HIGH，降水概率>60%为MEDIUM，其余为LOW。
     * </p>
     *
     * @param w       当前天气数据
     * @param village 村落实体
     * @return 风险等级（HIGH/MEDIUM/LOW）
     */
    private String assessRiskLevel(CurrentWeather w, Village village) {
        boolean hasActiveWarning =
                warningNoticeRepository.countEffectiveByStatus("active", LocalDateTime.now()) > 0;
        if (hasActiveWarning) {
            return "HIGH";
        }
        if (w != null && w.precipitationProbability() != null && w.precipitationProbability() > 60) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
