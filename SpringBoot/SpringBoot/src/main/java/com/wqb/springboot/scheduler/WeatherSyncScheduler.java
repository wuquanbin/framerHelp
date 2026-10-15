package com.wqb.springboot.scheduler;

import com.wqb.springboot.dto.AmapDto;
import com.wqb.springboot.dto.OpenMeteoDto;
import com.wqb.springboot.entity.User;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WarningNotice;
import com.wqb.springboot.entity.WeatherForecast;
import com.wqb.springboot.entity.WeatherObservation;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.repository.VillageRepository;
import com.wqb.springboot.repository.WarningNoticeRepository;
import com.wqb.springboot.repository.WeatherForecastRepository;
import com.wqb.springboot.repository.WeatherObservationRepository;
import com.wqb.springboot.service.AmapWeatherService;
import com.wqb.springboot.service.AppConfigService;
import com.wqb.springboot.service.OpenMeteoWeatherService;
import com.wqb.springboot.service.WechatPushService;
import com.wqb.springboot.service.WechatUserService;
import com.wqb.springboot.service.WeatherPushCoordinator;
import com.wqb.springboot.util.WeatherCodeMapper;
import com.wqb.springboot.util.WeatherIndexCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 天气数据同步调度器
 * <p>
 * 负责定时调度天气数据同步任务,编排实况/预报/预警三类数据的拉取与融合。
 * 气象指数计算委托给 {@link WeatherIndexCalculator},
 * 消息推送委托给 {@link WeatherPushCoordinator},
 * 本类只负责调度编排和数据持久化。
 * </p>
 */
@Component
public class WeatherSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeatherSyncScheduler.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 预报天数:前4天可用高德的天气现象做融合,其余天数只有 Open-Meteo 有数据 */
    private static final int FORECAST_DAYS = 15;

    private static final Map<DayOfWeek, String> WEEK_MAP = Map.of(
            DayOfWeek.MONDAY, "星期一", DayOfWeek.TUESDAY, "星期二",
            DayOfWeek.WEDNESDAY, "星期三", DayOfWeek.THURSDAY, "星期四",
            DayOfWeek.FRIDAY, "星期五", DayOfWeek.SATURDAY, "星期六",
            DayOfWeek.SUNDAY, "星期日"
    );

    private final AmapWeatherService amapWeatherService;
    private final OpenMeteoWeatherService openMeteoWeatherService;
    private final VillageRepository villageRepository;
    private final WeatherObservationRepository observationRepository;
    private final WeatherForecastRepository forecastRepository;
    private final WarningNoticeRepository warningNoticeRepository;
    private final WeatherPushCoordinator pushCoordinator;
    private final TransactionTemplate transactionTemplate;
    private final UserRepository userRepository;
    private final WechatUserService wechatUserService;
    private final WechatPushService wechatPushService;
    private final AppConfigService appConfigService;

    public WeatherSyncScheduler(
            AmapWeatherService amapWeatherService,
            OpenMeteoWeatherService openMeteoWeatherService,
            VillageRepository villageRepository,
            WeatherObservationRepository observationRepository,
            WeatherForecastRepository forecastRepository,
            WarningNoticeRepository warningNoticeRepository,
            WeatherPushCoordinator pushCoordinator,
            TransactionTemplate transactionTemplate,
            UserRepository userRepository,
            WechatUserService wechatUserService,
            WechatPushService wechatPushService,
            AppConfigService appConfigService
    ) {
        this.amapWeatherService = amapWeatherService;
        this.openMeteoWeatherService = openMeteoWeatherService;
        this.villageRepository = villageRepository;
        this.observationRepository = observationRepository;
        this.forecastRepository = forecastRepository;
        this.warningNoticeRepository = warningNoticeRepository;
        this.pushCoordinator = pushCoordinator;
        this.transactionTemplate = transactionTemplate;
        this.userRepository = userRepository;
        this.wechatUserService = wechatUserService;
        this.wechatPushService = wechatPushService;
        this.appConfigService = appConfigService;
    }

    /**
     * 天气数据同步主入口
     * <p>
     * <b>周期不在这里写死</b>：读后台「系统设置」的 {@code weather.sync.interval}（分钟），
     * 开关读 {@code weather.sync.enabled}，注册逻辑见
     * {@link com.wqb.springboot.config.WeatherSyncSchedulingConfig}。
     * </p>
     * <p>
     * 两个阶段：<b>并发拉取</b>（Open-Meteo 用多坐标语法一次 HTTP 取回全部村镇，高德按 adcode 去重后虚拟线程并发）；
     * <b>串行落库</b>（JPA 的 EntityManager 和 {@code @Transactional} 都绑在当前线程上，写操作并发会踩事务串味，
     * 而真正的耗时在外部 IO，那部分已经并发掉了）。
     * </p>
     */
    @Transactional
    public void syncWeatherData() {
        // 系统设置里的总开关：关掉后彻底不再打外部气象接口（演示/省流时用得上）
        if (!appConfigService.getBool(AppConfigService.K_SYNC_ENABLED, true)) {
            log.info("=== 天气同步已在系统设置中关闭（weather.sync.enabled=false），本次跳过 ===");
            return;
        }
        log.info("=== WeatherSyncScheduler: starting dual-API optimized sync ===");
        List<Village> villages = villageRepository.findAllByOrderByIdAsc();
        if (villages.isEmpty()) {
            log.warn("No villages configured, skipping sync");
            return;
        }

        // ========== 阶段一:并发拉取全部外部数据(不碰数据库) ==========
        long fetchStart = System.currentTimeMillis();
        Map<Long, ExternalData> bag = prefetchExternalData(villages);
        log.info("=== external data ready for {} village(s) in {} ms ===",
                villages.size(), System.currentTimeMillis() - fetchStart);

        // ========== 阶段二:串行落库(复用原有的融合与降级逻辑) ==========
        int successCount = 0;
        int failCount = 0;

        for (Village village : villages) {
            try {
                ExternalData data = bag.getOrDefault(village.getId(), ExternalData.EMPTY);
                // 1. 同步实况天气(双API融合)
                syncVillageObservation(village, data.amapLive(), data.omCurrent());
                // 2. 同步天气预报(双API融合:前4天高德+OpenMeteo,后11天OpenMeteo)
                syncVillageForecast(village, data.amapForecast(), data.omForecast());
                // 3. 同步气象预警
                syncVillageAlerts(village, data.omAlerts());
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("Failed to sync weather for village {}: {}", village.getCode(), e.getMessage(), e);
            }
        }

        log.info("=== WeatherSyncScheduler: sync finished. Success: {}, Failed: {} ===", successCount, failCount);

        // 同步完成后,尝试推送当日农事提醒(由协调器内部控制推送时机和防重复)
        pushCoordinator.pushDailyFarmingAdvice();
    }

    // ==================== 阶段一:并发拉取 ====================

    /**
     * 一个村镇在本轮同步中拿到的全部外部数据快照。
     * <p>
     * 拉取失败不会中断整体流程,对应字段保持 null 即可 ——
     * 落库阶段按原有规则降级(两个来源都拿不到才跳过该村镇)。
     * </p>
     */
    private record ExternalData(AmapDto.Live amapLive,
                                OpenMeteoDto.Current omCurrent,
                                Map<LocalDate, AmapDto.Cast> amapForecast,
                                OpenMeteoDto.ForecastResponse omForecast,
                                OpenMeteoDto.AlertsResponse omAlerts) {

        /** 全部拉取失败时的兜底快照 */
        static final ExternalData EMPTY = new ExternalData(null, null, Map.of(), null, null);
    }

    /**
     * 并发拉取全部村镇的外部数据。
     *
     * @param villages 本轮要同步的村镇
     * @return 村镇ID → 数据快照
     */
    private Map<Long, ExternalData> prefetchExternalData(List<Village> villages) {
        long start = System.currentTimeMillis();

        // 所有结果都用并发容器收集,每个拉取任务自己管自己的异常
        Map<String, AmapDto.Live> amapLiveByAdcode = new ConcurrentHashMap<>();
        Map<String, Map<LocalDate, AmapDto.Cast>> amapForecastByAdcode = new ConcurrentHashMap<>();
        Map<Long, OpenMeteoDto.AlertsResponse> alertsById = new ConcurrentHashMap<>();
        Map<Long, OpenMeteoDto.CurrentResponse> currentById = new ConcurrentHashMap<>();
        Map<Long, OpenMeteoDto.ForecastResponse> forecastById = new ConcurrentHashMap<>();

        Map<String, List<Village>> adcodeGroups = groupByAdcode(villages);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        // 一个池里塞进全部任务:三组高德、逐村预警、两次 Open-Meteo 批量互不依赖,
        // 串行排队的话总耗时是"各阶段之和",并行的话总耗时是"最慢的那一个请求"。
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            adcodeGroups.forEach((adcode, members) -> {
                Village representative = members.get(0);
                tasks.add(CompletableFuture.runAsync(
                        () -> fetchAmapLiveInto(representative, adcode, amapLiveByAdcode), pool));
                tasks.add(CompletableFuture.runAsync(
                        () -> fetchAmapForecastInto(representative, adcode, amapForecastByAdcode), pool));
            });
            for (Village village : villages) {
                tasks.add(CompletableFuture.runAsync(
                        () -> fetchAlertsInto(village, alertsById), pool));
            }
            tasks.add(CompletableFuture.runAsync(
                    () -> fetchCurrentBatchInto(villages, currentById), pool));
            tasks.add(CompletableFuture.runAsync(
                    () -> fetchForecastBatchInto(villages, forecastById), pool));

            tasks.forEach(CompletableFuture::join);
        }

        // 批量整批失败时退回逐村拉取,保证"一次外部抖动"不会让本轮完全空跑
        if (currentById.isEmpty()) {
            log.warn("Open-Meteo current batch yielded nothing, retrying village by village");
            fetchCurrentOneByOne(villages, currentById);
        }
        if (forecastById.isEmpty()) {
            log.warn("Open-Meteo forecast batch yielded nothing, retrying village by village");
            fetchForecastOneByOne(villages, forecastById);
        }

        log.info("=== prefetch finished: {} request(s) issued for {} village(s) in {} ms ===",
                tasks.size(), villages.size(), System.currentTimeMillis() - start);

        Map<Long, AmapDto.Live> amapLives = spreadToMembers(adcodeGroups, amapLiveByAdcode);
        Map<Long, Map<LocalDate, AmapDto.Cast>> amapForecasts = spreadToMembers(adcodeGroups, amapForecastByAdcode);

        Map<Long, ExternalData> bag = new HashMap<>(villages.size() * 2);
        for (Village village : villages) {
            bag.put(village.getId(), new ExternalData(
                    amapLives.get(village.getId()),
                    extractCurrent(currentById.get(village.getId())),
                    amapForecasts.getOrDefault(village.getId(), Map.of()),
                    sanitizeForecast(forecastById.get(village.getId())),
                    alertsById.get(village.getId())
            ));
        }
        return bag;
    }

    /** 拉高德实况,结果写进共享容器;失败只记日志,不影响其它任务 */
    private void fetchAmapLiveInto(Village representative, String adcode, Map<String, AmapDto.Live> sink) {
        try {
            AmapDto.LiveWeatherResponse response = amapWeatherService.fetchVillageLiveWeather(representative);
            if (response != null && response.lives != null && !response.lives.isEmpty()) {
                sink.put(adcode, response.lives.get(0));
            } else {
                log.warn("Amap live API returned empty payload for adcode {}", adcode);
            }
        } catch (Exception e) {
            log.warn("Amap live API failed for adcode {}: {}", adcode, e.getMessage());
        }
    }

    /** 拉高德多日预报并按日期索引写进共享容器 */
    private void fetchAmapForecastInto(Village representative, String adcode,
                                       Map<String, Map<LocalDate, AmapDto.Cast>> sink) {
        Map<LocalDate, AmapDto.Cast> byDate = new HashMap<>();
        try {
            AmapDto.ForecastWeatherResponse response = amapWeatherService.fetchVillageForecast(representative);
            if (response != null && response.forecasts != null && !response.forecasts.isEmpty()) {
                AmapDto.Forecast forecast = response.forecasts.get(0);
                if (forecast.casts != null) {
                    for (AmapDto.Cast cast : forecast.casts) {
                        try {
                            byDate.put(LocalDate.parse(cast.date), cast);
                        } catch (Exception e) {
                            log.warn("Failed to parse Amap forecast date {}: {}", cast.date, e.getMessage());
                        }
                    }
                }
            } else {
                log.warn("Amap forecast API returned empty payload for adcode {}", adcode);
            }
        } catch (Exception e) {
            log.warn("Amap forecast API failed for adcode {}: {}", adcode, e.getMessage());
        }
        sink.put(adcode, byDate);
    }

    /** 拉单村气象预警 */
    private void fetchAlertsInto(Village village, Map<Long, OpenMeteoDto.AlertsResponse> sink) {
        try {
            OpenMeteoDto.AlertsResponse response = openMeteoWeatherService.fetchAlerts(village);
            if (response != null) {
                sink.put(village.getId(), response);
            }
        } catch (Exception e) {
            log.warn("Open-Meteo alerts API failed for {}: {}", village.getName(), e.getMessage());
        }
    }

    /** 批量拉实况;整批失败就什么都不写,由上层退化为逐村重试 */
    private void fetchCurrentBatchInto(List<Village> villages, Map<Long, OpenMeteoDto.CurrentResponse> sink) {
        try {
            sink.putAll(openMeteoWeatherService.fetchCurrentBatch(villages));
        } catch (Exception e) {
            log.warn("Open-Meteo current batch failed: {}", e.getMessage());
        }
    }

    /** 批量拉预报;整批失败留在空容器里,由上层退化重试 */
    private void fetchForecastBatchInto(List<Village> villages, Map<Long, OpenMeteoDto.ForecastResponse> sink) {
        try {
            sink.putAll(openMeteoWeatherService.fetchForecastBatch(villages, FORECAST_DAYS));
        } catch (Exception e) {
            log.warn("Open-Meteo forecast batch failed: {}", e.getMessage());
        }
    }

    /** 从 Open-Meteo 实况响应中取出主体,响应体缺 current 段时视为失败 */
    private OpenMeteoDto.Current extractCurrent(OpenMeteoDto.CurrentResponse response) {
        return (response != null && response.current != null) ? response.current : null;
    }

    /** 预报响应要求 daily.time 非空,否则跟"没数据"等价,直接归一成 null */
    private OpenMeteoDto.ForecastResponse sanitizeForecast(OpenMeteoDto.ForecastResponse response) {
        if (response == null || response.daily == null || response.daily.time == null || response.daily.time.isEmpty()) {
            return null;
        }
        return response;
    }

    /** 按下标取整数值；越界或元素为 null 时返回 null（Open-Meteo 的数组里会真实出现 null） */
    private static Integer intOrNull(List<Integer> list, int i) {
        if (list == null || i < 0 || i >= list.size()) return null;
        return list.get(i);
    }

    /** 同上，取不到就用兜底值 —— 直接 get 会自动拆箱成 int，null 时抛 NPE 打断整村同步 */
    private static int intAt(List<Integer> list, int i, int fallback) {
        Integer value = intOrNull(list, i);
        return value == null ? fallback : value;
    }

    /** 按下标取浮点值，越界或元素为 null 时返回 null */
    private static Double doubleAt(List<Double> list, int i) {
        if (list == null || i < 0 || i >= list.size()) return null;
        return list.get(i);
    }

    /**
     * 按 adcode 分组村镇。
     * <p>
     * 高德是按行政区查询的,同一区县下的不同乡镇返回<b>完全相同</b>的报文
     * (白坭镇与芦苞镇同属三水区 440607),去重后可以少发一大半请求。
     * adcode 为空的村镇统一归到空串组 —— 服务层对空 adcode 用的是同一个市级兜底值,
     * 响应必然一致,合并成一次请求不丢数据。
     * </p>
     */
    private Map<String, List<Village>> groupByAdcode(List<Village> villages) {
        Map<String, List<Village>> groups = new LinkedHashMap<>();
        for (Village village : villages) {
            String key = (village.getAdcode() == null || village.getAdcode().isBlank())
                    ? "" : village.getAdcode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(village);
        }
        return groups;
    }

    /**
     * 用虚拟线程并行执行一组独立任务,并等待全部完成。
     * <p>
     * 走虚拟线程而不是固定线程池:任务全是阻塞式 HTTP IO,虚拟线程在 IO 阻塞时
     * 会让出载体线程,几十上百个并发任务也压不垮线程池,用完即关不需要池化。
     * </p>
     *
     * @param items 任务列表
     * @param task  单任务逻辑(内部自行消化异常,避免一个失败拖垮整个 {@code join})
     */
    private <E> void runInParallel(Collection<E> items, Consumer<E> task) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());
            for (E item : items) {
                futures.add(CompletableFuture.runAsync(() -> task.accept(item), pool));
            }
            futures.forEach(CompletableFuture::join);
        }
    }

    /** 把"按 key 取回的结果"摊平成"按村镇ID索引",缺失即表示该村镇本轮没拿到数据 */
    private <T> Map<Long, T> spreadToMembers(Map<String, List<Village>> groups, Map<String, T> fetched) {
        Map<Long, T> out = new HashMap<>();
        for (Map.Entry<String, List<Village>> entry : groups.entrySet()) {
            T value = fetched.get(entry.getKey());
            for (Village village : entry.getValue()) {
                out.put(village.getId(), value);
            }
        }
        return out;
    }

    /**
     * 批量失败后的退化路径:逐村拉实况。
     * <p>
     * 仍然用虚拟线程并发 —— 退化只是请求数变多,没有理由跟着退回到串行。
     * </p>
     */
    private void fetchCurrentOneByOne(List<Village> villages, Map<Long, OpenMeteoDto.CurrentResponse> sink) {
        runInParallel(villages, village -> {
            try {
                OpenMeteoDto.CurrentResponse response = openMeteoWeatherService.fetchCurrent(village);
                if (response != null && response.current != null) {
                    sink.put(village.getId(), response);
                }
            } catch (Exception e) {
                log.warn("Open-Meteo current API failed for {}: {}", village.getName(), e.getMessage());
            }
        });
    }

    /** 批量失败后的退化路径:逐村拉预报,同样保持并发 */
    private void fetchForecastOneByOne(List<Village> villages, Map<Long, OpenMeteoDto.ForecastResponse> sink) {
        runInParallel(villages, village -> {
            try {
                OpenMeteoDto.ForecastResponse response =
                        openMeteoWeatherService.fetchForecast(village, FORECAST_DAYS);
                if (response != null && response.daily != null
                        && response.daily.time != null && !response.daily.time.isEmpty()) {
                    sink.put(village.getId(), response);
                }
            } catch (Exception e) {
                log.warn("Open-Meteo forecast API failed for {}: {}", village.getName(), e.getMessage());
            }
        });
    }

    // ==================== 单村入口:自己拉数据再落库 ====================

    /**
     * 单村立即同步(供新建村镇后调用,无需等待定时周期)
     * <p>
     * 异步执行不阻塞创建请求;在独立事务中同步实况/预报/预警三类数据。
     * </p>
     *
     * @param villageId 村镇ID(创建事务提交后按ID重新加载)
     */
    public void syncSingleVillageAsync(Long villageId) {
        Thread.ofVirtual().name("village-initial-sync-" + villageId).start(() -> {
            try {
                Village village = villageRepository.findById(villageId).orElse(null);
                if (village == null) {
                    log.warn("Initial sync skipped: village {} not found", villageId);
                    return;
                }
                transactionTemplate.executeWithoutResult(tx -> {
                    try {
                        syncVillageObservation(village);
                        syncVillageForecast(village);
                        syncVillageAlerts(village);
                    } catch (Exception e) {
                        log.error("Initial weather sync failed for village {}: {}",
                                village.getName(), e.getMessage(), e);
                    }
                });
                log.info("Initial weather sync complete for new village: {}", village.getName());
            } catch (Exception e) {
                log.error("Initial weather sync failed for village id={}: {}", villageId, e.getMessage());
            }
        });
    }

    /**
     * 同步单村实况天气(自行拉取数据) - 单村入口,供新建村镇等场景调用
     *
     * @param village 目标村落
     */
    private void syncVillageObservation(Village village) {
        AmapDto.Live amapLive = null;
        OpenMeteoDto.Current openMeteoCurrent = null;

        try {
            AmapDto.LiveWeatherResponse amapResp = amapWeatherService.fetchVillageLiveWeather(village);
            if (amapResp != null && amapResp.lives != null && !amapResp.lives.isEmpty()) {
                amapLive = amapResp.lives.get(0);
                log.debug("Amap live data acquired for {}", village.getName());
            }
        } catch (Exception e) {
            log.warn("Amap live API failed for {}: {}", village.getName(), e.getMessage());
        }

        try {
            OpenMeteoDto.CurrentResponse omCurrentResp = openMeteoWeatherService.fetchCurrent(village);
            if (omCurrentResp != null && omCurrentResp.current != null) {
                openMeteoCurrent = omCurrentResp.current;
                log.debug("Open-Meteo current data acquired for {}", village.getName());
            }
        } catch (Exception e) {
            log.warn("Open-Meteo current API failed for {}: {}", village.getName(), e.getMessage());
        }

        syncVillageObservation(village, amapLive, openMeteoCurrent);
    }

    /**
     * 同步单村实况天气 - 高德主数据 + OpenMeteo补充体感温度/气压/能见度
     * <p>
     * 入参来自并发拉取阶段,任一来源为 null 表示该来源本轮没取到数据,按融合规则降级。
     * </p>
     *
     * @param village          目标村落
     * @param amapLive         高德实况(天气现象),可为 null
     * @param openMeteoCurrent Open-Meteo 实况(全部数值),可为 null
     */
    private void syncVillageObservation(Village village, AmapDto.Live amapLive, OpenMeteoDto.Current openMeteoCurrent) {
        // 容错:如果两个API都失败,跳过
        if (amapLive == null && openMeteoCurrent == null) {
            log.error("Both weather APIs failed for village {}, skipping observation", village.getName());
            return;
        }

        // ========== 数据融合:高德负责「天气现象」,Open-Meteo 负责「全部数值」 ==========
        // 高德免费天气接口只到区县级,同区县的不同乡镇拿到的是完全相同的数据
        // (白坭镇与芦苞镇同属三水区 440607,温度、湿度必然一模一样),而本系统按村镇做气象帮扶,
        // 数值必须逐镇独立 —— Open-Meteo 按经纬度查询、精度到乡镇,所以数值以它为准;
        // 天气现象保留高德:中文表述更贴合国内习惯,也是中国气象局的数据。
        String weatherType;
        BigDecimal temp;
        Integer humidity;
        String windDirection;
        String windPower;
        BigDecimal apparentTemp;
        BigDecimal pressure;
        BigDecimal visibility;

        // 1. 天气现象:优先高德
        if (amapLive != null && amapLive.weather != null && !amapLive.weather.isEmpty()) {
            weatherType = amapLive.weather;
        } else {
            weatherType = WeatherCodeMapper.getWeatherType(openMeteoCurrent.weather_code);
            log.info("Weather type degraded to Open-Meteo for {} (Amap unavailable)", village.getName());
        }

        // 2. 数值:优先 Open-Meteo(镇级格点);不可用时降级到高德(区县级,精度较低)
        //    前面已保证 amapLive 与 openMeteoCurrent 至少有一个非空,所以 else 分支里 amapLive 必然存在
        if (openMeteoCurrent != null) {
            temp = BigDecimal.valueOf(openMeteoCurrent.temperature_2m).setScale(1, RoundingMode.HALF_UP);
            humidity = (int) Math.round(openMeteoCurrent.relative_humidity_2m);
            windDirection = WeatherCodeMapper.getWindDirection(openMeteoCurrent.wind_direction_10m) + "风";
            windPower = WeatherCodeMapper.getWindScale(openMeteoCurrent.wind_speed_10m) + "级";
        } else {
            temp = new BigDecimal(amapLive.temperature);
            humidity = amapLive.humidity != null && !amapLive.humidity.isEmpty()
                    ? Integer.parseInt(amapLive.humidity) : 65;
            windDirection = (amapLive.winddirection != null ? amapLive.winddirection : "东南") + "风";
            windPower = (amapLive.windpower != null ? amapLive.windpower : "2") + "级";
            log.warn("Numeric fields degraded to Amap (district-level) for {}", village.getName());
        }

        // 体感温度:优先Open-Meteo(更专业),高德没有则计算估算值
        if (openMeteoCurrent != null && openMeteoCurrent.apparent_temperature != 0) {
            apparentTemp = BigDecimal.valueOf(openMeteoCurrent.apparent_temperature).setScale(1, RoundingMode.HALF_UP);
        } else {
            // 简单体感温度估算:温度+湿度修正
            apparentTemp = temp.add(BigDecimal.valueOf(humidity > 70 ? 2.0 : (humidity < 40 ? -1.0 : 0.5)));
        }

        // 气压:Open-Meteo
        if (openMeteoCurrent != null && openMeteoCurrent.surface_pressure != 0) {
            pressure = BigDecimal.valueOf(openMeteoCurrent.surface_pressure).setScale(1, RoundingMode.HALF_UP);
        } else {
            pressure = new BigDecimal("1013.2");
        }

        // 能见度:默认15公里(良好能见度),后续可接入AQI/能见度API补充
        visibility = new BigDecimal("15.0");

        // ========== 气象指数计算(委托给工具类) ==========
        String comfortLevel = WeatherIndexCalculator.calculateComfort(temp, apparentTemp, humidity);
        String uvIndex = WeatherIndexCalculator.calculateUVIndex(weatherType);
        String aqi = WeatherIndexCalculator.estimateAQI(temp, humidity);

        // 降水概率:优先使用 Open-Meteo 的真实数值,API 未提供时才退回按天气现象的估算值。
        // (高德免费版不提供降水概率,所以这里必须靠 Open-Meteo 补真实值)
        Double omPrecipProb = openMeteoCurrent != null ? openMeteoCurrent.precipitation_probability : null;
        int currentPrecip = omPrecipProb != null
                ? Math.min(100, Math.max(0, (int) Math.round(omPrecipProb)))
                : WeatherIndexCalculator.estimatePrecipitation(weatherType, weatherType);

        String farmIndex = WeatherIndexCalculator.calculateFarmIndex(weatherType, temp, windPower);

        WeatherObservation observation = new WeatherObservation(
                village,
                LocalDateTime.now(SHANGHAI).withSecond(0).withNano(0),
                weatherType,
                temp,
                apparentTemp,
                humidity,
                windDirection,
                windPower,
                aqi,
                currentPrecip,
                comfortLevel,
                uvIndex,
                farmIndex
        );
        observation.setPressure(pressure);
        observation.setVisibility(visibility);

        observationRepository.save(observation);
        log.info("Saved live weather for {}: {} {}°C (feels {}°C), humidity {}%, {} {}, precip {}% ({})",
                village.getName(), weatherType, temp, apparentTemp, humidity, windDirection, windPower,
                currentPrecip, omPrecipProb != null ? "Open-Meteo实时" : "按天气现象估算");
    }

    /**
     * 同步单村天气预报(自行拉取数据) - 单村入口,供新建村镇等场景调用
     *
     * @param village 目标村落
     */
    private void syncVillageForecast(Village village) {
        Map<LocalDate, AmapDto.Cast> amapForecastMap = new HashMap<>();
        OpenMeteoDto.ForecastResponse omForecast = null;

        // 1. 获取高德4天预报
        try {
            AmapDto.ForecastWeatherResponse amapResp = amapWeatherService.fetchVillageForecast(village);
            if (amapResp != null && amapResp.forecasts != null && !amapResp.forecasts.isEmpty()) {
                AmapDto.Forecast forecast = amapResp.forecasts.get(0);
                if (forecast.casts != null) {
                    for (AmapDto.Cast cast : forecast.casts) {
                        try {
                            LocalDate date = LocalDate.parse(cast.date);
                            amapForecastMap.put(date, cast);
                        } catch (Exception e) {
                            log.warn("Failed to parse Amap forecast date {}: {}", cast.date, e.getMessage());
                        }
                    }
                    log.debug("Amap forecast data acquired for {}: {} days", village.getName(), amapForecastMap.size());
                }
            }
        } catch (Exception e) {
            log.warn("Amap forecast API failed for {}: {}", village.getName(), e.getMessage());
        }

        // 2. 获取Open-Meteo 15天预报
        try {
            omForecast = sanitizeForecast(openMeteoWeatherService.fetchForecast(village, FORECAST_DAYS));
            if (omForecast == null) {
                log.warn("Open-Meteo forecast returned empty for {}", village.getName());
            } else {
                log.debug("Open-Meteo forecast data acquired for {}: {} days", village.getName(), omForecast.daily.time.size());
            }
        } catch (Exception e) {
            log.warn("Open-Meteo forecast API failed for {}: {}", village.getName(), e.getMessage());
        }

        syncVillageForecast(village, amapForecastMap, omForecast);
    }

    /**
     * 同步单村天气预报 - 前4天高德+OpenMeteo融合,后11天OpenMeteo
     * <p>
     * 入参来自并发拉取阶段,{@code amapForecastMap} 为空表示高德本轮没数据,
     * {@code omForecast} 为 null 表示 Open-Meteo 本轮没数据。
     * </p>
     *
     * @param village         目标村落
     * @param amapForecastMap 高德按日期索引的预报
     * @param omForecast      Open-Meteo 多日预报
     */
    private void syncVillageForecast(Village village,
                                     Map<LocalDate, AmapDto.Cast> amapForecastMap,
                                     OpenMeteoDto.ForecastResponse omForecast) {
        // 容错:至少需要一个API的预报数据
        if (amapForecastMap.isEmpty() && omForecast == null) {
            log.error("Both forecast APIs failed for village {}, skipping forecast", village.getName());
            return;
        }

        // 3. 融合结果先攒在内存里,循环跑完才删旧数据 —— 中途抛异常时旧预报还在,
        //    不会因为「删了没补回来」把整村预报清空（外层 catch 吞掉异常,删除会被提交）
        List<WeatherForecast> merged = new ArrayList<>();

        // 4. 优先使用Open-Meteo的日期范围(15天),逐天融合数据
        if (omForecast != null) {
            OpenMeteoDto.Daily daily = omForecast.daily;
            for (int i = 0; i < daily.time.size(); i++) {
                String dateText = daily.time.get(i);
                if (dateText == null) continue;
                // 整行缺值的天（Open-Meteo 用它表示"这天没有数据"）不落库
                Integer weatherCode = intOrNull(daily.weather_code, i);
                Double minTempRaw = doubleAt(daily.temperature_2m_min, i);
                Double maxTempRaw = doubleAt(daily.temperature_2m_max, i);
                if (weatherCode == null || (minTempRaw == null && maxTempRaw == null)) continue;
                LocalDate date = LocalDate.parse(dateText);
                String weekLabel = WEEK_MAP.getOrDefault(date.getDayOfWeek(), "");
                AmapDto.Cast amapCast = amapForecastMap.get(date);

                // 默认使用Open-Meteo数据
                String dayWeather = WeatherCodeMapper.getWeatherType(weatherCode);
                String nightWeather = dayWeather;
                BigDecimal minTemp = WeatherCodeMapper.toBigDecimal(minTempRaw);
                BigDecimal maxTemp = WeatherCodeMapper.toBigDecimal(maxTempRaw);
                int precipProb = intAt(daily.precipitation_probability_max, i, 10);
                String windDir = "东南";
                String windScale = "2";

                Integer windDegree = intOrNull(daily.wind_direction_10m_dominant, i);
                if (windDegree != null) {
                    windDir = WeatherCodeMapper.getWindDirection(windDegree);
                }
                Double windSpeed = doubleAt(daily.wind_speed_10m_max, i);
                if (windSpeed != null) {
                    windScale = WeatherCodeMapper.getWindScale(windSpeed);
                }

                // ========== 前4天:仅用高德覆盖「天气现象」,温度/风一律保留 Open-Meteo ==========
                // 高德的天气现象支持日/夜分开表述(如「雷阵雨转多云」),这是 Open-Meteo 单一天气代码给不了的;
                // 但高德的 daytemp/nighttemp/daywind 都是区县级数据,同区县的不同乡镇完全相同
                // (白坭镇与芦苞镇同属三水区 440607,预报高温会永远一致),
                // 所以温度、风、降水概率全部保留 Open-Meteo 的镇级格点值,不再被高德覆盖。
                if (amapCast != null) {
                    if (amapCast.dayweather != null && !amapCast.dayweather.isEmpty()) {
                        dayWeather = amapCast.dayweather;
                    }
                    if (amapCast.nightweather != null && !amapCast.nightweather.isEmpty()) {
                        nightWeather = amapCast.nightweather;
                    }
                }

                // 如果白天夜间天气不同,组合显示
                String weatherType = dayWeather;
                if (nightWeather != null && !nightWeather.equals(dayWeather) && !nightWeather.isEmpty()) {
                    weatherType = dayWeather + "转" + nightWeather;
                }

                // 农事建议(委托给工具类)
                String farmingAdvice = WeatherIndexCalculator.generateFarmingAdvice(dayWeather, nightWeather, windDir, windScale, precipProb);

                WeatherForecast entity = new WeatherForecast(
                        village, date, weekLabel, weatherType, minTemp, maxTemp,
                        precipProb, windDir + "风", windScale + "级", farmingAdvice
                );
                merged.add(entity);
            }
            log.info("Forecast sync complete for {}: {} days (merged {} days from Amap)",
                    village.getName(), merged.size(), amapForecastMap.size());
        } else if (!amapForecastMap.isEmpty()) {
            // Open-Meteo完全失败,降级使用高德4天预报
            log.warn("Degraded to Amap-only forecast for {} (Open-Meteo failed), only 4 days available", village.getName());
            List<LocalDate> dates = amapForecastMap.keySet().stream().sorted().toList();
            for (LocalDate date : dates) {
                AmapDto.Cast cast = amapForecastMap.get(date);
                String weekLabel = WEEK_MAP.getOrDefault(date.getDayOfWeek(), "");
                String dayWeather = cast.dayweather;
                String nightWeather = cast.nightweather;
                String weatherType = dayWeather;
                if (nightWeather != null && !nightWeather.equals(dayWeather) && !nightWeather.isEmpty()) {
                    weatherType = dayWeather + "转" + nightWeather;
                }
                BigDecimal minTemp = new BigDecimal(cast.nighttemp);
                BigDecimal maxTemp = new BigDecimal(cast.daytemp);
                int precipProb = WeatherIndexCalculator.estimatePrecipitation(dayWeather, nightWeather);
                String windDir = cast.daywind;
                String windScale = cast.daypower;
                String farmingAdvice = WeatherIndexCalculator.generateFarmingAdvice(dayWeather, nightWeather, windDir, windScale, precipProb);

                WeatherForecast entity = new WeatherForecast(
                        village, date, weekLabel, weatherType, minTemp, maxTemp,
                        precipProb, windDir + "风", windScale + "级", farmingAdvice
                );
                merged.add(entity);
            }
        }

        // 5. 到这里说明融合全部成功,再一次性替换旧预报;一条都没融合出来就保留原样,别把旧数据删空
        if (merged.isEmpty()) {
            log.warn("No usable forecast day for {} (all fields null), keeping existing records", village.getName());
            return;
        }
        forecastRepository.deleteAll(forecastRepository.findByVillageOrderByForecastDateAsc(village));
        forecastRepository.saveAll(merged);
    }

    /**
     * 同步单村气象预警(自行拉取数据) - 单村入口
     *
     * @param village 目标村落
     */
    private void syncVillageAlerts(Village village) {
        OpenMeteoDto.AlertsResponse response;
        try {
            response = openMeteoWeatherService.fetchAlerts(village);
        } catch (Exception e) {
            log.warn("Open-Meteo alerts API failed for {}: {}", village.getName(), e.getMessage());
            response = null;
        }
        syncVillageAlerts(village, response);
    }

    /**
     * 同步单村气象预警(Open-Meteo,高德免费版无预警)
     * <p>
     * {@code response} 为 null 表示接口不可用 —— 此时不清库,保留尚未过期的预警。
     * </p>
     *
     * @param village  目标村落
     * @param response Open-Meteo 预警响应,可为 null
     */
    private void syncVillageAlerts(Village village, OpenMeteoDto.AlertsResponse response) {
        try {
            // 1. 无条件清理已过期的预警。
            //    Open-Meteo 的 /v1/alerts 端点实际已下线(返回 404),fetchAlerts 因此返回 null。
            //    若把清理逻辑放在 null 判断之后,历史遗留的失效预警会永远以 active 状态挂在页面上。
            //    必须整表清理:历史演示数据的 village_id 为 NULL,按村镇查根本查不到。
            LocalDateTime now = LocalDateTime.now(SHANGHAI);
            long purged = warningNoticeRepository.deleteByExpiresAtBefore(now);
            if (purged > 0) {
                log.info("Purged {} expired alert(s) globally", purged);
            }

            // 2. 接口不可用时保留尚未过期的数据
            if (response == null) {
                log.debug("Alerts API unavailable for {}, kept {} unexpired alert(s)",
                        village.getName(), warningNoticeRepository.findByVillage(village).size());
                return;
            }

            // 3. 接口有返回时,全量替换为最新预警
            List<WarningNotice> stale = warningNoticeRepository.findByVillage(village);
            if (!stale.isEmpty()) {
                warningNoticeRepository.deleteAll(stale);
            }

            if (response.alerts == null || response.alerts.isEmpty()) {
                log.debug("No active alerts for {}", village.getName());
                return;
            }

            int savedCount = 0;
            for (OpenMeteoDto.Alert alert : response.alerts) {
                WarningNotice notice = new WarningNotice();
                notice.setVillage(village);
                notice.setWarningType(WeatherIndexCalculator.mapAlertEvent(alert.event));
                notice.setSeverity(WeatherIndexCalculator.mapSeverity(alert.severity));
                notice.setTitle(alert.headline != null ? alert.headline : (alert.event != null ? alert.event : "天气预警"));
                notice.setContent(alert.description != null ? alert.description : "");
                notice.setDefenseGuidance(alert.instruction != null ? alert.instruction : "");
                notice.setStatus("active");

                if (alert.onset != null) {
                    try {
                        notice.setIssuedAt(LocalDateTime.parse(alert.onset.replace("Z", "")));
                    } catch (Exception e) {
                        notice.setIssuedAt(LocalDateTime.now(SHANGHAI));
                    }
                } else {
                    notice.setIssuedAt(LocalDateTime.now(SHANGHAI));
                }
                if (alert.expires != null) {
                    try {
                        notice.setExpiresAt(LocalDateTime.parse(alert.expires.replace("Z", "")));
                    } catch (Exception e) {
                        notice.setExpiresAt(LocalDateTime.now(SHANGHAI).plusHours(24));
                    }
                } else if (alert.ends != null) {
                    try {
                        notice.setExpiresAt(LocalDateTime.parse(alert.ends.replace("Z", "")));
                    } catch (Exception e) {
                        notice.setExpiresAt(LocalDateTime.now(SHANGHAI).plusHours(24));
                    }
                } else {
                    notice.setExpiresAt(LocalDateTime.now(SHANGHAI).plusHours(24));
                }

                if (alert.areaDesc != null && !alert.areaDesc.isEmpty()) {
                    notice.setImpactArea(String.join(", ", alert.areaDesc));
                } else {
                    notice.setImpactArea(village.getName());
                }

                warningNoticeRepository.save(notice);
                savedCount++;
            }
            log.info("Alerts sync complete for {}: {} active alerts", village.getName(), savedCount);

            // 有新预警时,委托推送协调器推送高等级预警通知（系统设置里可整体关停）
            if (savedCount > 0 && appConfigService.getBool(AppConfigService.K_WARNING_PUSH, true)) {
                pushCoordinator.pushHighLevelAlerts(village);
            }
        } catch (Exception e) {
            log.error("Failed to sync alerts for village {}: {}", village.getCode(), e.getMessage());
        }
    }

    /**
     * 每日农事任务推送
     * <p>
     * 推送时间由后台「系统设置」的 {@code farm.advice.push.time}（HH:mm）决定，默认 07:30，
     * 同样由 {@link com.wqb.springboot.config.WeatherSyncSchedulingConfig} 动态注册 cron。
     * 遍历所有设置了播种日期的用户，计算今日农事任务并通过微信订阅消息推送。
     * </p>
     */
    public void pushDailyFarmingTasks() {
        log.info("[每日农事推送] 开始执行每日农事任务推送");
        try {
            List<User> allUsers = userRepository.findAll();
            int pushedCount = 0;
            for (User user : allUsers) {
                try {
                    // 只推送给设置了农事提醒通知的用户
                    if (user.getNotifyFarming() != null && !user.getNotifyFarming()) {
                        continue;
                    }
                    List<Map<String, Object>> tasks = wechatUserService.getTodayFarmingTasks(user.getOpenid());
                    if (tasks.isEmpty()) {
                        continue;
                    }
                    // 取优先级最高的任务推送
                    Map<String, Object> topTask = tasks.get(0);
                    String cropName = (String) topTask.get("cropName");
                    String taskName = (String) topTask.get("taskName");
                    String description = (String) topTask.get("description");
                    int currentDay = (int) topTask.get("currentDay");

                    String title = cropName + "·" + taskName;
                    String advice = "种植第" + currentDay + "天，" + taskName;
                    String note = tasks.size() > 1 ? "另有" + (tasks.size() - 1) + "项待办" : "";

                    wechatPushService.pushFarmingNotification(user.getOpenid(), title, advice, note);
                    pushedCount++;
                    Thread.sleep(100); // 避免推送过快
                } catch (Exception e) {
                    log.warn("[每日农事推送] 推送给用户 {} 失败: {}", user.getOpenid(), e.getMessage());
                }
            }
            log.info("[每日农事推送] 完成，共推送 {} 个用户", pushedCount);
        } catch (Exception e) {
            log.error("[每日农事推送] 执行失败: {}", e.getMessage(), e);
        }
    }
}
