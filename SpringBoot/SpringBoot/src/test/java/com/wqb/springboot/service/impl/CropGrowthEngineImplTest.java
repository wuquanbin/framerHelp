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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link CropGrowthEngineImpl} 单元测试。
 * <p>
 * 五个 Repository 全部用 Mockito 打桩，不起 Spring 容器、不连数据库 ——
 * 引擎本体是「查库 + 查知识库拼文案」，规则部分完全可测。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("作物生长引擎")
class CropGrowthEngineImplTest {

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private CropProfileRepository cropProfileRepository;
    @Mock
    private WeatherObservationRepository weatherObservationRepository;
    @Mock
    private WeatherForecastRepository weatherForecastRepository;
    @Mock
    private WarningNoticeRepository warningNoticeRepository;

    private CropGrowthEngineImpl engine;
    private Village village;

    @BeforeEach
    void setUp() {
        engine = new CropGrowthEngineImpl(villageRepository, cropProfileRepository,
                weatherObservationRepository, weatherForecastRepository, warningNoticeRepository);

        village = new Village();
        village.setCode("baini");
        village.setName("白坭镇");

        when(villageRepository.findByCode("baini")).thenReturn(Optional.of(village));
        // 默认无任何生效预警；需要预警的用例自己覆盖
        when(warningNoticeRepository.findEffectiveByStatus(eq("active"), any(LocalDateTime.class)))
                .thenReturn(List.of());
    }

    /** 造一个作物档案；riskLevel 为空表示未评估 */
    private static CropProfile crop(String name, String type, String stage, Integer riskLevel) {
        CropProfile c = new CropProfile();
        c.setCropName(name);
        c.setCropType(type);
        c.setGrowthStage(stage);
        c.setRiskLevel(riskLevel);
        return c;
    }

    private static WeatherObservation obs(String weatherType) {
        WeatherObservation o = new WeatherObservation();
        o.setWeatherType(weatherType);
        o.setObservationTime(LocalDateTime.now());
        return o;
    }

    // ========== 农事提醒 ==========

    @Test
    @DisplayName("没有观测数据时降级为「暂无」，不抛异常（新建村镇首屏就会遇到）")
    void remindersWithoutObservation() {
        when(weatherObservationRepository.findFirstByVillageOrderByObservationTimeDesc(village))
                .thenReturn(Optional.empty());
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 2)));

        List<CropReminder> reminders = engine.generateCropReminders("baini");

        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).weatherImpact().contains("当前天气：暂无"));
        assertEquals("一般", reminders.get(0).urgency());
    }

    @Test
    @DisplayName("暴雨命中天气风险提示，紧迫度升级为「重要」")
    void remindersOnRainstorm() {
        when(weatherObservationRepository.findFirstByVillageOrderByObservationTimeDesc(village))
                .thenReturn(Optional.of(obs("暴雨")));
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 2)));

        CropReminder r = engine.generateCropReminders("baini").get(0);

        assertTrue(r.weatherImpact().contains("天气风险"));
        assertTrue(r.suggestion().contains("排涝"), "暴雨建议里应含排涝： " + r.suggestion());
        assertEquals("重要", r.urgency());
    }

    @Test
    @DisplayName("存在生效预警时紧迫度为「紧急」，且优先级高于天气判断")
    void remindersWithActiveWarning() {
        when(weatherObservationRepository.findFirstByVillageOrderByObservationTimeDesc(village))
                .thenReturn(Optional.of(obs("晴")));
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 1)));
        WarningNotice w = new WarningNotice();
        w.setWarningType("暴雨");
        w.setStatus("active");
        w.setExpiresAt(LocalDateTime.now().plusHours(2));
        when(warningNoticeRepository.findEffectiveByStatus(eq("active"), any(LocalDateTime.class)))
                .thenReturn(List.of(w));

        assertEquals("紧急", engine.generateCropReminders("baini").get(0).urgency());
    }

    @Test
    @DisplayName("天气晴好但作物风险等级≥4 时仍判定为「重要」")
    void remindersWithHighRiskCrop() {
        when(weatherObservationRepository.findFirstByVillageOrderByObservationTimeDesc(village))
                .thenReturn(Optional.of(obs("晴")));
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 4)));

        assertEquals("重要", engine.generateCropReminders("baini").get(0).urgency());
    }

    @Test
    @DisplayName("村落编码不存在时明确报错，不能静默返回空列表")
    void unknownVillageThrows() {
        when(villageRepository.findByCode("nope")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> engine.generateCropReminders("nope"));
    }

    // ========== 周农事计划 ==========

    @Test
    @DisplayName("暴雨天的活动建议是「停止户外农事」而不是「适合田间管理」")
    void weeklyPlanOnRainstorm() {
        WeatherForecast f = org.mockito.Mockito.mock(WeatherForecast.class);
        when(f.getWeatherType()).thenReturn("暴雨");
        when(f.getForecastDate()).thenReturn(LocalDate.of(2026, 9, 19));
        when(f.getWeekLabel()).thenReturn("周六");
        when(weatherForecastRepository.findByVillageOrderByForecastDateAsc(village)).thenReturn(List.of(f));
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village)).thenReturn(List.of());

        List<DailyFarmPlan> plans = engine.getWeeklyFarmPlan("baini");

        assertEquals(1, plans.size());
        assertEquals("2026-09-19", plans.get(0).date());
        assertTrue(plans.get(0).activities().contains("停止户外农事"));
        assertFalse(plans.get(0).activities().contains("适合田间管理"));
    }

    // ========== 灾后补救 ==========

    @Test
    @DisplayName("同为暴雨，水稻与蔬菜给出不同的补救措施（粮食按名称细分到水稻/玉米）")
    void disasterRemedyDiffersByCrop() {
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 2), crop("菜心", "蔬菜", "生长期", 2)));

        List<DisasterRemedy> remedies = engine.generateDisasterRemedy("baini", "暴雨");

        assertEquals(2, remedies.size());
        assertTrue(remedies.get(0).remedy().contains("排涝"));
        assertTrue(remedies.get(0).remedy().contains("纹枯病"), "水稻应走水稻分支：" + remedies.get(0).remedy());
        assertTrue(remedies.get(1).remedy().contains("杀菌剂"), "蔬菜应走蔬菜分支：" + remedies.get(1).remedy());
        assertEquals("立即", remedies.get(0).timeline());
    }

    @Test
    @DisplayName("未知灾害类型回落到通用模板，时间窗默认 24 小时内")
    void disasterRemedyFallback() {
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 2)));

        DisasterRemedy r = engine.generateDisasterRemedy("baini", "蝗灾").get(0);

        assertTrue(r.remedy().contains("请根据实际情况采取相应补救措施"));
        assertEquals("24小时内", r.timeline());
    }

    @Test
    @DisplayName("粮食类按作物名细分：名字带「玉米」走玉米知识库，不再是水稻文案")
    void grainCategorySplit() {
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("甜玉米", "粮食", "拔节期", 2)));

        DisasterRemedy r = engine.generateDisasterRemedy("baini", "干旱").get(0);

        assertTrue(r.remedy().contains("地膜"), "玉米抗旱应提地膜保墒：" + r.remedy());
        assertFalse(r.remedy().contains("保持水层"));
        assertEquals("24小时内", r.timeline());
    }

    @Test
    @DisplayName("温度类输入不影响引擎（回归用：引擎只读天气现象，不读数值）")
    void engineIgnoresNumericWeather() {
        WeatherObservation o = obs("多云");
        o.setTemperature(new BigDecimal("33.5"));
        when(weatherObservationRepository.findFirstByVillageOrderByObservationTimeDesc(village))
                .thenReturn(Optional.of(o));
        when(cropProfileRepository.findByVillageOrderByRiskLevelDescIdAsc(village))
                .thenReturn(List.of(crop("丝苗米", "粮食", "分蘖期", 1)));

        assertEquals("一般", engine.generateCropReminders("baini").get(0).urgency());
    }
}
