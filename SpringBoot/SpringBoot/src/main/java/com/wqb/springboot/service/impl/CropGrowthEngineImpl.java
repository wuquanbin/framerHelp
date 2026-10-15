package com.wqb.springboot.service.impl;

import com.wqb.springboot.dto.WeatherResponseDto.CropReminder;
import com.wqb.springboot.dto.WeatherResponseDto.DailyFarmPlan;
import com.wqb.springboot.dto.WeatherResponseDto.DisasterRemedy;
import com.wqb.springboot.entity.CropProfile;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WarningNotice;
import com.wqb.springboot.entity.WeatherForecast;
import com.wqb.springboot.entity.WeatherObservation;
import com.wqb.springboot.repository.CropProfileRepository;
import com.wqb.springboot.repository.VillageRepository;
import com.wqb.springboot.repository.WarningNoticeRepository;
import com.wqb.springboot.repository.WeatherForecastRepository;
import com.wqb.springboot.repository.WeatherObservationRepository;
import com.wqb.springboot.service.CropGrowthEngine;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作物生长引擎服务实现类
 * <p>
 * 融合天气数据与常见作物生长模型，为农户生成农事提醒。
 * 核心功能包括：作物农事提醒、周农事计划、灾后补救建议。
 * </p>
 */
@Service
public class CropGrowthEngineImpl implements CropGrowthEngine {

    /** 作物生长阶段天气敏感性知识库 */
    private static final Map<String, Map<String, String>> CROP_STAGE_RISK = Map.of(
            "水稻", Map.of(
                    "播种期", "低温烂种风险，需保温育秧",
                    "分蘖期", "需充足光照和水分，阴雨不利分蘖",
                    "抽穗期", "怕连续阴雨，影响授粉结实",
                    "灌浆期", "高温逼熟风险，需保持水层",
                    "成熟期", "风雨易倒伏，需及时抢收"
            ),
            "玉米", Map.of(
                    "播种期", "土壤湿度要求高，干旱不利出苗",
                    "拔节期", "需水关键期，干旱严重影响产量",
                    "抽雄期", "高温干旱导致花期不遇",
                    "灌浆期", "需充足水分，干旱导致秃尖",
                    "成熟期", "连续降雨导致霉变"
            ),
            "蔬菜", Map.of(
                    "苗期", "高温或低温都影响成活率",
                    "生长期", "暴雨导致沤根和病害",
                    "开花期", "高温导致落花，雨天影响授粉",
                    "采收期", "雨水影响品质和储运"
            ),
            "水果", Map.of(
                    "花芽分化期", "高温影响花芽分化",
                    "开花期", "阴雨影响授粉，大风落花",
                    "膨大期", "干旱导致裂果，暴雨导致落果",
                    "着色期", "温差大有利着色，降雨不利",
                    "成熟期", "风雨导致落果，需及时采收"
            ),
            "经济作物", Map.of(
                    "苗期", "低温影响生长，需覆盖保温",
                    "生长期", "旱涝均不利，需排灌配套",
                    "采收期", "雨天影响采收品质",
                    "加工期", "天气影响晾晒和加工"
            )
    );

    /** 天气类型对应的风险提示 */
    private static final Map<String, String> WEATHER_RISK = Map.ofEntries(
            Map.entry("暴雨", "涝害风险高，需疏通排水，低洼田块及时排涝"),
            Map.entry("大雨", "注意田间排水，防积水沤根"),
            Map.entry("中雨", "推迟施肥打药，注意防雨棚加固"),
            Map.entry("小雨", "适合移栽定植，不适合施肥打药"),
            Map.entry("雷阵雨", "注意防雷避雨，加固设施"),
            Map.entry("台风", "加固大棚和农用设施，抢收成熟作物"),
            Map.entry("高温", "注意灌溉补水，合理遮阳，避免正午田间作业"),
            Map.entry("寒潮", "做好防冻措施，覆盖保温膜，温室大棚保温"),
            Map.entry("霜冻", "熏烟防霜，覆盖保温，灌水调温"),
            Map.entry("冰雹", "灾后及时清理残枝，补施肥料促恢复"),
            Map.entry("干旱", "充分利用灌溉设施，早晚浇水保墒，覆盖地膜减少蒸发")
    );

    /** 灾害类型对应的补救措施 */
    private static final Map<String, String> DISASTER_REMEDY = Map.of(
            "暴雨", "及时排涝，扶苗洗苗，追施速效肥，防治纹枯病",
            "干旱", "充分利用灌溉设施，早晚浇水保墒，叶面喷施抗旱剂",
            "台风", "风前加固支架，风后扶正培土，修剪断枝，喷杀菌剂防病",
            "高温", "增加喷灌降温，遮阳网覆盖，补施钾肥增强抗热性",
            "低温", "覆盖保温膜，灌水调温，增施热性肥料",
            "冰雹", "清理残枝败叶，中耕松土，追施速效氮肥促新芽"
    );

    /** 灾害类型对应的时间紧迫度 */
    private static final Map<String, String> DISASTER_TIMELINE = Map.of(
            "暴雨", "立即",
            "台风", "立即",
            "冰雹", "立即",
            "高温", "24小时内",
            "干旱", "24小时内",
            "低温", "3天内"
    );

    /** 作物类型到知识库分类的映射（粮食→水稻/玉米，蔬菜→蔬菜，水果→水果，经济作物→经济作物） */
    private static final Map<String, String> CROP_TYPE_TO_CATEGORY = Map.of(
            "粮食", "水稻",
            "蔬菜", "蔬菜",
            "水果", "水果",
            "经济作物", "经济作物"
    );

    /** 晴好天气关键词 */
    private static final Set<String> SUNNY_KEYWORDS = Set.of("晴", "多云", "阴");

    /** 雨天天气关键词 */
    private static final Set<String> RAIN_KEYWORDS = Set.of("雨");

    /** 风暴天气关键词 */
    private static final Set<String> STORM_KEYWORDS = Set.of("暴雨", "台风", "雷阵雨", "冰雹");

    /** 高温天气关键词 */
    private static final Set<String> HOT_KEYWORDS = Set.of("高温", "炙热");

    /** 低温天气关键词 */
    private static final Set<String> COLD_KEYWORDS = Set.of("寒潮", "霜冻", "低温");

    private final VillageRepository villageRepository;
    private final CropProfileRepository cropProfileRepository;
    private final WeatherObservationRepository weatherObservationRepository;
    private final WeatherForecastRepository weatherForecastRepository;
    private final WarningNoticeRepository warningNoticeRepository;

    public CropGrowthEngineImpl(
            VillageRepository villageRepository,
            CropProfileRepository cropProfileRepository,
            WeatherObservationRepository weatherObservationRepository,
            WeatherForecastRepository weatherForecastRepository,
            WarningNoticeRepository warningNoticeRepository
    ) {
        this.villageRepository = villageRepository;
        this.cropProfileRepository = cropProfileRepository;
        this.weatherObservationRepository = weatherObservationRepository;
        this.weatherForecastRepository = weatherForecastRepository;
        this.warningNoticeRepository = warningNoticeRepository;
    }

    // ========== 生成作物农事提醒 ==========

    @Override
    public List<CropReminder> generateCropReminders(String villageCode) {
        Village village = resolveVillage(villageCode);
        WeatherObservation obs = weatherObservationRepository
                .findFirstByVillageOrderByObservationTimeDesc(village)
                .orElse(null);
        List<CropProfile> crops = cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village);
        // 只取仍在有效期内的预警，过期预警不应再参与作物风险提示
        List<WarningNotice> activeWarnings =
                warningNoticeRepository.findEffectiveByStatus("active", LocalDateTime.now());

        String weatherType = obs != null ? obs.getWeatherType() : "暂无";

        List<CropReminder> reminders = new ArrayList<>();
        for (CropProfile crop : crops) {
            String category = resolveCropCategory(crop.getCropType(), crop.getCropName());
            String growthStage = crop.getGrowthStage();

            // 查找该作物在当前生长阶段的风险提示
            String stageRisk = lookupStageRisk(category, growthStage);

            // 查找当前天气的风险提示
            String weatherRisk = lookupWeatherRisk(weatherType);

            // 组合天气影响描述
            String weatherImpact = buildWeatherImpact(weatherType, stageRisk, weatherRisk);

            // 生成农事建议
            String suggestion = buildSuggestion(crop, stageRisk, weatherRisk, weatherType);

            // 判断紧迫程度
            String urgency = determineUrgency(weatherType, activeWarnings, crop);

            reminders.add(new CropReminder(
                    crop.getCropName(),
                    crop.getCropType(),
                    growthStage,
                    weatherImpact,
                    suggestion,
                    urgency
            ));
        }
        return reminders;
    }

    // ========== 生成周农事计划 ==========

    @Override
    public List<DailyFarmPlan> getWeeklyFarmPlan(String villageCode) {
        Village village = resolveVillage(villageCode);
        List<WeatherForecast> forecasts = weatherForecastRepository
                .findByVillageOrderByForecastDateAsc(village);
        List<CropProfile> crops = cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village);

        List<DailyFarmPlan> plans = new ArrayList<>();
        for (WeatherForecast forecast : forecasts) {
            String weatherType = forecast.getWeatherType();
            String date = forecast.getForecastDate().toString();
            String weekLabel = forecast.getWeekLabel();

            // 根据天气类型确定基础农事活动
            List<String> activities = resolveDailyActivities(weatherType);

            // 检查该天气下是否有作物处于风险阶段，生成注意事项
            String caution = buildCautionNote(weatherType, crops);

            plans.add(new DailyFarmPlan(
                    date,
                    weekLabel,
                    weatherType,
                    activities,
                    caution
            ));
        }
        return plans;
    }

    // ========== 生成灾后补救建议 ==========

    @Override
    public List<DisasterRemedy> generateDisasterRemedy(String villageCode, String disasterType) {
        Village village = resolveVillage(villageCode);
        List<CropProfile> crops = cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village);

        String remedyTemplate = DISASTER_REMEDY.getOrDefault(disasterType, "请根据实际情况采取相应补救措施");
        String timeline = DISASTER_TIMELINE.getOrDefault(disasterType, "24小时内");

        List<DisasterRemedy> remedies = new ArrayList<>();
        for (CropProfile crop : crops) {
            // 根据作物类型和灾害类型定制补救措施
            String remedy = customizeRemedy(disasterType, crop, remedyTemplate);
            remedies.add(new DisasterRemedy(
                    disasterType,
                    crop.getCropName(),
                    remedy,
                    timeline
            ));
        }
        return remedies;
    }

    // ========== 内部辅助方法 ==========

    /**
     * 根据村落编码解析村落实体
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

    /**
     * 将作物类型映射到知识库分类
     * <p>
     * 粮食类作物进一步按名称区分为水稻或玉米，其他类型直接映射。
     * </p>
     *
     * @param cropType 作物类型（粮食/蔬菜/水果/经济作物）
     * @param cropName 作物名称
     * @return 知识库分类名称
     */
    private String resolveCropCategory(String cropType, String cropName) {
        if ("粮食".equals(cropType)) {
            if (cropName.contains("稻")) {
                return "水稻";
            }
            if (cropName.contains("玉米")) {
                return "玉米";
            }
            // 默认粮食作物映射为水稻
            return "水稻";
        }
        return CROP_TYPE_TO_CATEGORY.getOrDefault(cropType, "经济作物");
    }

    /**
     * 查找作物在指定生长阶段的风险提示
     *
     * @param category    知识库分类名称
     * @param growthStage 生长阶段
     * @return 风险提示文本；若未匹配则返回null
     */
    private String lookupStageRisk(String category, String growthStage) {
        Map<String, String> stageRisks = CROP_STAGE_RISK.get(category);
        if (stageRisks == null || growthStage == null) {
            return null;
        }
        // 精确匹配
        if (stageRisks.containsKey(growthStage)) {
            return stageRisks.get(growthStage);
        }
        // 模糊匹配：遍历查找包含关键字的阶段
        for (Map.Entry<String, String> entry : stageRisks.entrySet()) {
            if (growthStage.contains(entry.getKey()) || entry.getKey().contains(growthStage)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 查找天气类型对应的风险提示
     *
     * @param weatherType 天气类型
     * @return 风险提示文本；若未匹配则返回null
     */
    private String lookupWeatherRisk(String weatherType) {
        if (weatherType == null) {
            return null;
        }
        // 精确匹配
        if (WEATHER_RISK.containsKey(weatherType)) {
            return WEATHER_RISK.get(weatherType);
        }
        // 模糊匹配：遍历查找包含关键字的天气类型
        for (Map.Entry<String, String> entry : WEATHER_RISK.entrySet()) {
            if (weatherType.contains(entry.getKey()) || entry.getKey().contains(weatherType)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 构建天气影响描述
     * <p>
     * 综合当前天气、生长阶段风险和天气风险，生成完整的影响描述。
     * </p>
     *
     * @param weatherType 当前天气类型
     * @param stageRisk   生长阶段风险
     * @param weatherRisk 天气风险
     * @return 天气影响描述文本
     */
    private String buildWeatherImpact(String weatherType, String stageRisk, String weatherRisk) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前天气：").append(weatherType);
        if (stageRisk != null) {
            sb.append("；阶段风险：").append(stageRisk);
        }
        if (weatherRisk != null) {
            sb.append("；天气风险：").append(weatherRisk);
        }
        return sb.toString();
    }

    /**
     * 构建农事建议
     * <p>
     * 综合作物档案、生长阶段风险、天气风险，生成针对性的农事建议。
     * </p>
     *
     * @param crop        作物档案
     * @param stageRisk   生长阶段风险
     * @param weatherRisk 天气风险
     * @param weatherType 当前天气类型
     * @return 农事建议文本
     */
    private String buildSuggestion(CropProfile crop, String stageRisk, String weatherRisk, String weatherType) {
        StringBuilder sb = new StringBuilder();
        sb.append(crop.getCropName()).append("当前处于").append(crop.getGrowthStage()).append("，");

        if (stageRisk != null) {
            sb.append(stageRisk).append("。");
        }

        if (weatherRisk != null) {
            sb.append(weatherRisk).append("。");
        }

        // 补充作物自带的农事备注
        if (crop.getFarmingNotes() != null && !crop.getFarmingNotes().isBlank()) {
            sb.append(crop.getFarmingNotes());
        }

        return sb.toString();
    }

    /**
     * 判断农事提醒的紧迫程度
     * <p>
     * 存在活跃预警时为"紧急"，天气包含雨/风暴/高温时为"重要"，其余为"一般"。
     * </p>
     *
     * @param weatherType    当前天气类型
     * @param activeWarnings 活跃预警列表
     * @param crop           作物档案
     * @return 紧迫程度（"紧急"/"重要"/"一般"）
     */
    private String determineUrgency(String weatherType, List<WarningNotice> activeWarnings, CropProfile crop) {
        // 存在活跃预警则紧急
        if (!activeWarnings.isEmpty()) {
            return "紧急";
        }
        // 天气包含雨/风暴/高温则重要
        if (containsAnyKeyword(weatherType, RAIN_KEYWORDS)
                || containsAnyKeyword(weatherType, STORM_KEYWORDS)
                || containsAnyKeyword(weatherType, HOT_KEYWORDS)) {
            return "重要";
        }
        // 作物风险等级较高则重要
        if (crop.getRiskLevel() != null && crop.getRiskLevel() >= 4) {
            return "重要";
        }
        return "一般";
    }

    /**
     * 判断天气类型是否包含指定关键词集合中的任一关键词
     *
     * @param weatherType 天气类型
     * @param keywords    关键词集合
     * @return 是否包含
     */
    private boolean containsAnyKeyword(String weatherType, Set<String> keywords) {
        if (weatherType == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (weatherType.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据天气类型确定每日农事活动建议
     *
     * @param weatherType 天气类型
     * @return 农事活动列表
     */
    private List<String> resolveDailyActivities(String weatherType) {
        List<String> activities = new ArrayList<>();

        if (containsAnyKeyword(weatherType, STORM_KEYWORDS)) {
            activities.add("停止户外农事");
            activities.add("加固设施");
            activities.add("检查排水");
            activities.add("抢收成熟作物");
        } else if (containsAnyKeyword(weatherType, RAIN_KEYWORDS)) {
            activities.add("适合室内农事");
            activities.add("检修农具");
            activities.add("排涝防渍");
            activities.add("推迟施肥打药");
        } else if (containsAnyKeyword(weatherType, HOT_KEYWORDS)) {
            activities.add("早晚作业");
            activities.add("中午避暑");
            activities.add("注意灌溉");
            activities.add("合理遮阳");
        } else if (containsAnyKeyword(weatherType, COLD_KEYWORDS)) {
            activities.add("注意保温防冻");
            activities.add("温室管理");
            activities.add("覆盖保温膜");
            activities.add("检修加热设备");
        } else {
            // 晴好天气
            activities.add("适合田间管理");
            activities.add("施肥打药");
            activities.add("作物采收");
            activities.add("田间巡查");
        }

        return activities;
    }

    /**
     * 构建每日注意事项
     * <p>
     * 检查该天气下是否有作物处于风险阶段，生成注意事项。
     * </p>
     *
     * @param weatherType 天气类型
     * @param crops       作物档案列表
     * @return 注意事项文本；若无特殊风险则返回空字符串
     */
    private String buildCautionNote(String weatherType, List<CropProfile> crops) {
        List<String> cautions = new ArrayList<>();

        for (CropProfile crop : crops) {
            String category = resolveCropCategory(crop.getCropType(), crop.getCropName());
            String stageRisk = lookupStageRisk(category, crop.getGrowthStage());
            String weatherRisk = lookupWeatherRisk(weatherType);

            // 仅在生长阶段风险与当前天气相关时才提示
            if (stageRisk != null && weatherRisk != null) {
                cautions.add(crop.getCropName() + "（" + crop.getGrowthStage() + "）：" + stageRisk);
            }
        }

        if (cautions.isEmpty()) {
            return "";
        }

        // 最多展示5条注意事项，避免信息过载
        int limit = Math.min(cautions.size(), 5);
        return "注意：" + String.join("；", cautions.subList(0, limit));
    }

    /**
     * 根据灾害类型和作物定制补救措施
     * <p>
     * 在通用补救模板基础上，结合作物特性和生长阶段进行个性化调整。
     * </p>
     *
     * @param disasterType  灾害类型
     * @param crop          作物档案
     * @param remedyTemplate 通用补救模板
     * @return 定制后的补救措施文本
     */
    private String customizeRemedy(String disasterType, CropProfile crop, String remedyTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append(crop.getCropName()).append("（").append(crop.getGrowthStage()).append("）：");

        // 根据作物类型和灾害类型进行定制
        String category = resolveCropCategory(crop.getCropType(), crop.getCropName());

        if ("暴雨".equals(disasterType)) {
            sb.append(customizeFloodRemedy(category, crop));
        } else if ("干旱".equals(disasterType)) {
            sb.append(customizeDroughtRemedy(category, crop));
        } else if ("台风".equals(disasterType)) {
            sb.append(customizeTyphoonRemedy(category, crop));
        } else if ("高温".equals(disasterType)) {
            sb.append(customizeHeatRemedy(category, crop));
        } else if ("低温".equals(disasterType)) {
            sb.append(customizeColdRemedy(category, crop));
        } else if ("冰雹".equals(disasterType)) {
            sb.append(customizeHailRemedy(category, crop));
        } else {
            sb.append(remedyTemplate);
        }

        return sb.toString();
    }

    /**
     * 暴雨灾害补救措施定制
     */
    private String customizeFloodRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "及时排涝，扶苗洗苗，追施速效肥，防治纹枯病和稻瘟病";
            case "玉米" -> "及时排涝，扶正倒伏植株，培土护根，追施速效氮肥";
            case "蔬菜" -> "疏通排水沟，清理烂叶，中耕松土，喷施杀菌剂防病";
            case "水果" -> "疏通果园排水，清除落果烂果，喷施杀菌剂，追施恢复肥";
            default -> "及时排涝，扶苗洗苗，追施速效肥，防治纹枯病";
        };
    }

    /**
     * 干旱灾害补救措施定制
     */
    private String customizeDroughtRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "充分利用灌溉设施，保持水层，叶面喷施抗旱剂";
            case "玉米" -> "早晚浇水保墒，覆盖地膜减少蒸发，喷施抗旱保水剂";
            case "蔬菜" -> "增加喷灌频率，覆盖遮阳网减少蒸发，叶面喷施抗旱剂";
            case "水果" -> "早晚滴灌补水，树盘覆盖保墒，喷施抗蒸腾剂";
            default -> "充分利用灌溉设施，早晚浇水保墒，叶面喷施抗旱剂";
        };
    }

    /**
     * 台风灾害补救措施定制
     */
    private String customizeTyphoonRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "风前加深水层护苗，风后及时排水，扶正倒伏，喷施杀菌剂防病";
            case "玉米" -> "风前加固支架，风后扶正培土，修剪断枝，追施速效肥促恢复";
            case "蔬菜" -> "风前加固大棚，风后修复设施，清理残株，补种速生蔬菜";
            case "水果" -> "风前加固果架，风后修剪断枝，喷施杀菌剂防病，追施恢复肥";
            default -> "风前加固支架，风后扶正培土，修剪断枝，喷杀菌剂防病";
        };
    }

    /**
     * 高温灾害补救措施定制
     */
    private String customizeHeatRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "加深水层降温，合理遮阳，补施钾肥增强抗热性";
            case "玉米" -> "增加喷灌降温，避免正午作业，补施钾肥增强抗热性";
            case "蔬菜" -> "遮阳网覆盖，增加喷灌频率，早晚浇水，补施钾肥";
            case "水果" -> "果实遮阳防护，增加喷灌降温，补施钾肥防日灼";
            default -> "增加喷灌降温，遮阳网覆盖，补施钾肥增强抗热性";
        };
    }

    /**
     * 低温灾害补救措施定制
     */
    private String customizeColdRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "灌水调温，覆盖保温膜，增施热性肥料，推迟播种期";
            case "玉米" -> "覆盖保温膜，推迟播种期，已出苗地块灌水调温";
            case "蔬菜" -> "大棚加盖草帘，增加保温措施，灌水调温，增施热性肥料";
            case "水果" -> "树干涂白防冻，覆盖保温膜，熏烟防霜，灌水调温";
            default -> "覆盖保温膜，灌水调温，增施热性肥料";
        };
    }

    /**
     * 冰雹灾害补救措施定制
     */
    private String customizeHailRemedy(String category, CropProfile crop) {
        return switch (category) {
            case "水稻" -> "清理残枝败叶，中耕松土，追施速效氮肥促新芽，防治病害";
            case "玉米" -> "清理残枝败叶，中耕松土，追施速效氮肥促新芽，及时补种";
            case "蔬菜" -> "清理残枝败叶，中耕松土，追施速效氮肥促新芽，补种速生蔬菜";
            case "水果" -> "修剪断枝残叶，清理落果，喷施杀菌剂防病，追施恢复肥";
            default -> "清理残枝败叶，中耕松土，追施速效氮肥促新芽";
        };
    }
}
