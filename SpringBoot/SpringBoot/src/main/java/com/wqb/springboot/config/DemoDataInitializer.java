package com.wqb.springboot.config;

import com.wqb.springboot.entity.*;
import com.wqb.springboot.repository.*;
import com.wqb.springboot.scheduler.WeatherSyncScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 系统初始化配置类
 * <p>
 * 在应用首次启动时（数据库为空时）自动注入系统必需数据，
 * 包括管理员账户、村落数据、系统配置参数等，
 * 并在启动完成后异步同步真实天气数据。
 * </p>
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /**
     * 各村镇的高德行政区编码与中心点坐标 {adcode, 纬度, 经度}。
     * <p>
     * 经纬度供 Open-Meteo 做镇级格点查询，adcode 供高德天气查询。
     * 注意白坭镇与芦苞镇同属三水区（adcode 均为 440607），高德免费接口只到区县级，
     * 因此高德侧无法区分这两个镇 —— 实况数值以 Open-Meteo 的经纬度为准。
     * </p>
     */
    private static final Map<String, String[]> VILLAGE_GEO = Map.of(
            "baini", new String[]{"440607", "23.1600000", "112.8600000"},
            "genghe", new String[]{"440608", "22.7500000", "112.6200000"},
            "lishui", new String[]{"440605", "23.1800000", "113.1500000"},
            "chencun", new String[]{"440606", "22.9600000", "113.2100000"},
            "lubao", new String[]{"440607", "23.4000000", "112.8900000"}
    );

    private final VillageRepository villageRepository;
    private final WeatherObservationRepository weatherObservationRepository;
    private final AdminUserRepository adminUserRepository;
    private final AppConfigRepository appConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final WeatherSyncScheduler weatherSyncScheduler;

    public DemoDataInitializer(
            VillageRepository villageRepository,
            WeatherObservationRepository weatherObservationRepository,
            AdminUserRepository adminUserRepository,
            AppConfigRepository appConfigRepository,
            PasswordEncoder passwordEncoder,
            WeatherSyncScheduler weatherSyncScheduler
    ) {
        this.villageRepository = villageRepository;
        this.weatherObservationRepository = weatherObservationRepository;
        this.adminUserRepository = adminUserRepository;
        this.appConfigRepository = appConfigRepository;
        this.passwordEncoder = passwordEncoder;
        this.weatherSyncScheduler = weatherSyncScheduler;
    }

    @Override
    public void run(String... args) {
        seedAdminUser();
        seedRoleDemoAccounts();
        seedVillages();
        backfillVillageGeo();
        seedAppConfig();

        // 启动完成后立即异步同步真实天气数据（不阻塞应用启动）
        if (weatherObservationRepository.count() == 0) {
            new Thread(() -> {
                try {
                    // 稍等片刻，确保应用完全启动后再调用外部API
                    Thread.sleep(3000);
                    System.out.println("========== 开始从API同步真实天气数据... ==========");
                    weatherSyncScheduler.syncWeatherData();
                    System.out.println("========== 真实天气数据同步完成 ==========");
                } catch (Exception e) {
                    System.err.println("初始天气数据同步失败: " + e.getMessage());
                }
            }, "weather-init-sync").start();
        }
    }

    private void seedAdminUser() {
        if (!adminUserRepository.existsByUsername("admin")) {
            AdminUser admin = new AdminUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRealName("超级管理员");
            admin.setRole("SUPER_ADMIN");
            admin.setStatus(1);
            admin.setEmail("admin@agriweather.com");
            adminUserRepository.save(admin);
        }
    }

    /**
     * 三级角色的演示账号（幂等，已存在则跳过）。
     * <p>
     * 权限模型做完了必须能现场演：只有 admin 一个超级管理员的话，
     * 「专家看不到人员管理」「村镇管理员只能看本村镇」这两条根本没法演示。
     * 演示口令统一为 demo123 —— 仅为答辩/演示便利，正式部署前请改掉或删号。
     * </p>
     */
    private void seedRoleDemoAccounts() {
        // 专家：能看能改业务数据、能进人工客服，但看不到人员管理/系统设置
        createAdminIfAbsent("expert", "demo123", "李农艺", "EXPERT", null);
        // 村镇管理员：绑定白坭镇，业务数据只看到本村镇、且不能删除
        createAdminIfAbsent("village", "demo123", "白坭镇管理员", "VILLAGE_ADMIN", "baini");
    }

    private void createAdminIfAbsent(String username, String rawPassword, String realName,
                                     String role, String villageCode) {
        if (adminUserRepository.existsByUsername(username)) return;
        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(rawPassword));
        admin.setRealName(realName);
        admin.setRole(role);
        admin.setVillageCode(villageCode);
        admin.setStatus(1);
        adminUserRepository.save(admin);
    }

    private void seedVillages() {
        if (villageRepository.count() > 0) return;

        villageRepository.save(new Village("baini", "白坭镇", "丝苗米、黑皮冬瓜", "河谷平原", "重点帮扶"));
        villageRepository.save(new Village("genghe", "更合镇", "粉葛、茶叶、荔枝", "丘陵山区", "重点帮扶"));
        villageRepository.save(new Village("lishui", "里水镇", "花卉、蔬菜", "平原水网区", "一般帮扶"));
        villageRepository.save(new Village("chencun", "陈村镇", "年桔、兰花", "珠三角平原", "已脱贫"));
        villageRepository.save(new Village("lubao", "芦苞镇", "早稻、莲藕、水产", "河涌水网区", "一般帮扶"));
    }

    /**
     * 补全村镇的行政区编码与中心点坐标（幂等，只填缺失值）。
     * <p>
     * 这两个字段是天气查询的坐标来源：{@code resolveCoords} / {@code resolveAdcode} 优先读表字段，
     * 为空时才退回到代码内置的映射表。历史数据的经纬度、adcode 都是空的，
     * 全靠硬编码兜底 —— 这里补齐之后，新增村镇只要在库里填好坐标即可，不必再改代码。
     * </p>
     */
    private void backfillVillageGeo() {
        for (Village village : villageRepository.findAll()) {
            String[] geo = VILLAGE_GEO.get(village.getCode());
            if (geo == null) {
                continue;
            }
            boolean changed = false;
            if (village.getAdcode() == null || village.getAdcode().isBlank()) {
                village.setAdcode(geo[0]);
                changed = true;
            }
            if (village.getLatitude() == null) {
                village.setLatitude(new BigDecimal(geo[1]));
                changed = true;
            }
            if (village.getLongitude() == null) {
                village.setLongitude(new BigDecimal(geo[2]));
                changed = true;
            }
            if (changed) {
                villageRepository.save(village);
                log.info("Backfilled geo for village {}: adcode={}, lat={}, lon={}",
                        village.getName(), village.getAdcode(), village.getLatitude(), village.getLongitude());
            }
        }
    }

    private void seedAppConfig() {
        if (appConfigRepository.count() > 0) return;

        appConfigRepository.save(new AppConfig("weather.sync.interval", "30", "天气数据同步间隔（分钟）", "天气同步"));
        appConfigRepository.save(new AppConfig("weather.sync.enabled", "true", "是否启用自动天气同步", "天气同步"));
        appConfigRepository.save(new AppConfig("warning.push.enabled", "true", "是否启用预警推送", "预警通知"));
        appConfigRepository.save(new AppConfig("farm.advice.push.time", "07:30", "每日农事建议推送时间", "预警通知"));
        appConfigRepository.save(new AppConfig("ai.model", "qwen2.5:7b", "AI对话模型名称", "AI助手"));
        appConfigRepository.save(new AppConfig("ai.base.url", "http://localhost:11434/v1", "AI模型接口地址", "AI助手"));
        appConfigRepository.save(new AppConfig("ai.max.tokens", "1024", "AI最大输出token数", "AI助手"));
        appConfigRepository.save(new AppConfig("ai.temperature", "0.7", "AI生成温度参数", "AI助手"));
        appConfigRepository.save(new AppConfig("system.name", "乡村公益农业气象帮扶系统", "系统名称", "基本信息"));
        appConfigRepository.save(new AppConfig("system.version", "v1.0.0", "系统版本", "基本信息"));
    }
}
