package com.wqb.springboot.config;

import com.wqb.springboot.scheduler.WeatherSyncScheduler;
import com.wqb.springboot.service.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 天气同步与每日推送的<b>动态调度</b>
 * <p>
 * 用 {@link Trigger} 而不是 {@code @Scheduled}：注解里的周期是编译期常量，
 * 「系统设置」改完不会重读；Trigger 每次任务跑完都重新问一次配置。
 * </p>
 *
 * <p>天气同步读 {@code weather.sync.interval}（分钟，首次 60 秒后启动，之后「上次实际执行时间 + 间隔」，
 * 开关 {@code weather.sync.enabled} 在 {@link WeatherSyncScheduler#syncWeatherData()} 内部判断）；
 * 每日农事推送读 {@code farm.advice.push.time}（HH:mm），换算成 cron 每天触发。</p>
 */
@Configuration
public class WeatherSyncSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WeatherSyncSchedulingConfig.class);

    /** 同步间隔下限：防止有人填 0 或负数，把外部气象接口刷爆 */
    private static final int MIN_INTERVAL_MINUTES = 1;
    /** 同步间隔上限：超过 24 小时就没意义了，按 24 小时算 */
    private static final int MAX_INTERVAL_MINUTES = 24 * 60;

    private final WeatherSyncScheduler scheduler;
    private final AppConfigService appConfigService;

    public WeatherSyncSchedulingConfig(WeatherSyncScheduler scheduler, AppConfigService appConfigService) {
        this.scheduler = scheduler;
        this.appConfigService = appConfigService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(scheduler::syncWeatherData, this::nextSyncTime);
        registrar.addTriggerTask(scheduler::pushDailyFarmingTasks, this::nextPushTime);
        log.info("[调度] 天气同步与每日推送已按系统配置动态注册");
    }

    /**
     * 下一次天气同步的时间点
     */
    private Instant nextSyncTime(org.springframework.scheduling.TriggerContext ctx) {
        int minutes = appConfigService.getInt(AppConfigService.K_SYNC_INTERVAL, 30);
        if (minutes < MIN_INTERVAL_MINUTES) {
            minutes = MIN_INTERVAL_MINUTES;
        } else if (minutes > MAX_INTERVAL_MINUTES) {
            minutes = MAX_INTERVAL_MINUTES;
        }
        // 首次触发给 1 分钟缓冲，等应用和连接池起来再打外部接口。
        // ⚠️ Spring 7 的 Trigger.nextExecutionTime 要求返回 Instant，
        //    但 TriggerContext.lastActualExecutionTime() 给的还是 java.util.Date，两边要转。
        Date last = ctx.lastActualExecutionTime();
        return last == null
                ? Instant.now().plusSeconds(60)
                : last.toInstant().plus(Duration.ofMinutes(minutes));
    }

    /**
     * 下一次每日农事推送的时间点（按配置里的 HH:mm 生成 cron）
     */
    private Instant nextPushTime(org.springframework.scheduling.TriggerContext ctx) {
        String hhmm = appConfigService.get(AppConfigService.K_ADVICE_PUSH_TIME, "07:30");
        try {
            return toInstant(new CronTrigger(buildDailyCron(hhmm)).nextExecutionTime(ctx));
        } catch (Exception e) {
            log.warn("[调度] 推送时间配置「{}」无法解析，回退 07:30", hhmm);
            return toInstant(new CronTrigger("0 30 7 * * ?").nextExecutionTime(ctx));
        }
    }

    /** CronTrigger 仍然返回 java.util.Date，统一转成 Trigger 接口要的 Instant */
    private Instant toInstant(Date date) {
        return date == null ? Instant.now().plusSeconds(60) : date.toInstant();
    }

    /**
     * "07:30" -> "0 30 7 * * ?"，格式不合规时回退 07:30 而不是让整个调度挂掉。
     */
    private String buildDailyCron(String hhmm) {
        int hour = 7;
        int minute = 30;
        String[] parts = hhmm == null ? new String[0] : hhmm.split(":");
        if (parts.length == 2) {
            hour = Integer.parseInt(parts[0].trim());
            minute = Integer.parseInt(parts[1].trim());
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("推送时间超出范围: " + hhmm);
        }
        // Spring 的 cron 是 6 段（秒 分 时 日 月 周），别漏了秒
        return "0 " + minute + " " + hour + " * * ?";
    }
}
