package com.wqb.springboot.service;

import com.wqb.springboot.entity.User;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WarningNotice;
import com.wqb.springboot.entity.WeatherForecast;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.repository.VillageRepository;
import com.wqb.springboot.repository.WarningNoticeRepository;
import com.wqb.springboot.repository.WeatherForecastRepository;
import com.wqb.springboot.util.WeatherIndexCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 微信消息推送协调器
 * <p>
 * 负责协调灾害预警推送和每日农事提醒推送,包含防重复推送的状态管理。
 * 由 {@link com.wqb.springboot.scheduler.WeatherSyncScheduler} 在调度时机调用。
 * </p>
 */
@Component
public class WeatherPushCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WeatherPushCoordinator.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final VillageRepository villageRepository;
    private final WeatherForecastRepository forecastRepository;
    private final WarningNoticeRepository warningNoticeRepository;
    private final UserRepository userRepository;
    private final WechatPushService pushService;

    // 记录上次预警推送时间,避免重复推送
    private final Map<Long, LocalDateTime> lastWarningPushTime = new HashMap<>();
    // 记录上次农事提醒推送日期,每天只推一次
    private volatile LocalDate lastFarmingPushDate = null;

    public WeatherPushCoordinator(
            VillageRepository villageRepository,
            WeatherForecastRepository forecastRepository,
            WarningNoticeRepository warningNoticeRepository,
            UserRepository userRepository,
            WechatPushService pushService
    ) {
        this.villageRepository = villageRepository;
        this.forecastRepository = forecastRepository;
        this.warningNoticeRepository = warningNoticeRepository;
        this.userRepository = userRepository;
        this.pushService = pushService;
    }

    /**
     * 每日农事提醒推送(每天一次,早上6:00-9:00之间触发)
     * <p>
     * 遍历所有村落,获取当日预报,向开启农事提醒的用户推送。
     * 同一天只会推送一次。
     * </p>
     */
    public void pushDailyFarmingAdvice() {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        LocalTime currentTime = now.toLocalTime();
        LocalDate today = now.toLocalDate();

        // 检查是否已推送过今天的提醒
        if (lastFarmingPushDate != null && lastFarmingPushDate.equals(today)) {
            return;
        }

        // 只在早上6:00-9:00之间推送
        if (currentTime.isBefore(LocalTime.of(6, 0)) || currentTime.isAfter(LocalTime.of(9, 0))) {
            return;
        }

        try {
            List<Village> villages = villageRepository.findAllByOrderByIdAsc();
            int totalUsers = 0;

            for (Village village : villages) {
                // 获取该村今日预报
                List<WeatherForecast> forecasts = forecastRepository.findByVillageAndForecastDate(village, today);
                if (forecasts.isEmpty()) {
                    continue;
                }
                WeatherForecast todayForecast = forecasts.get(0);

                // 获取该村开启农事提醒的用户
                List<User> users = userRepository.findByVillageCode(village.getCode());
                List<String> openids = users.stream()
                        .filter(u -> Boolean.TRUE.equals(u.getNotifyFarming()))
                        .map(User::getOpenid)
                        .collect(Collectors.toList());

                if (openids.isEmpty()) {
                    continue;
                }

                String title = "今日农事提醒 - " + today.format(DateTimeFormatter.ofPattern("MM月dd日"));
                String advice = todayForecast.getFarmingAdvice();
                String weatherNote = todayForecast.getWeatherType() + " " +
                        todayForecast.getLowTemperature().intValue() + "~" + todayForecast.getHighTemperature().intValue() + "°C";

                log.info("Pushing daily farming advice to {} users in {}", openids.size(), village.getName());
                pushService.pushFarmingToUsers(openids, title, advice != null ? advice : "关注天气变化,合理安排农事", weatherNote);
                totalUsers += openids.size();

                Thread.sleep(200); // 避免请求过快
            }

            if (totalUsers > 0) {
                lastFarmingPushDate = today;
                log.info("Daily farming advice push completed, total users: {}", totalUsers);
            }
        } catch (Exception e) {
            log.error("Failed to push daily farming advice: {}", e.getMessage());
        }
    }

    /**
     * 推送高等级预警给该村开启通知的用户
     * <p>
     * 仅推送橙色/红色高等级预警,同一个村1小时内只推一次,避免骚扰用户。
     * </p>
     *
     * @param village 目标村落
     */
    public void pushHighLevelAlerts(Village village) {
        try {
            LocalDateTime now = LocalDateTime.now(SHANGHAI);
            // 先剔除已过期的预警，避免把失效预警推送给农户
            List<WarningNotice> activeAlerts = warningNoticeRepository.findByVillageAndStatus(village, "active").stream()
                    .filter(a -> a.getExpiresAt() == null || a.getExpiresAt().isAfter(now))
                    .collect(Collectors.toList());
            // 筛选橙色/红色高等级预警
            List<WarningNotice> highAlerts = activeAlerts.stream()
                    .filter(a -> "red".equals(a.getSeverity()) || "orange".equals(a.getSeverity()))
                    .collect(Collectors.toList());

            if (highAlerts.isEmpty()) {
                return;
            }

            // 防重复推送:同一个村1小时内只推一次
            LocalDateTime lastPush = lastWarningPushTime.get(village.getId());
            if (lastPush != null && lastPush.plusHours(1).isAfter(LocalDateTime.now(SHANGHAI))) {
                log.debug("High alerts for {} already pushed within 1 hour, skipping", village.getName());
                return;
            }

            // 获取该村开启预警推送的用户openid
            List<User> users = userRepository.findByVillageCode(village.getCode());
            List<String> openids = users.stream()
                    .filter(u -> Boolean.TRUE.equals(u.getNotifyWarning()))
                    .map(User::getOpenid)
                    .collect(Collectors.toList());

            if (openids.isEmpty()) {
                log.debug("No users subscribed to warning notifications in {}", village.getName());
                return;
            }

            // 取最高等级的预警推送
            WarningNotice topAlert = highAlerts.stream()
                    .sorted((a, b) -> "red".equals(a.getSeverity()) ? -1 : 1)
                    .findFirst().orElse(highAlerts.get(0));

            String warningType = WeatherIndexCalculator.getSeverityLabel(topAlert.getSeverity()) + topAlert.getWarningTypeCn();
            String area = village.getName();
            String content = topAlert.getTitle() != null ? topAlert.getTitle() : "请注意防范";

            log.info("Pushing {} warning to {} users in {}", warningType, openids.size(), village.getName());
            pushService.pushWarningToUsers(openids, warningType, area, content, topAlert.getSeverity());
            lastWarningPushTime.put(village.getId(), LocalDateTime.now(SHANGHAI));
        } catch (Exception e) {
            log.error("Failed to push warning notifications for {}: {}", village.getName(), e.getMessage());
        }
    }
}
