package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import com.wqb.springboot.entity.CropProfile;
import com.wqb.springboot.repository.*;
import com.wqb.springboot.service.AdminDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理后台仪表盘服务实现类
 * <p>
 * 提供管理后台首页仪表盘的统计数据查询功能,包括用户数、预警数、
 * 反馈数、农情上报数、作物分布等统计聚合。
 * </p>
 */
@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardServiceImpl.class);

    private final UserRepository userRepository;
    private final WarningNoticeRepository warningNoticeRepository;
    private final FeedbackRepository feedbackRepository;
    private final VillageRepository villageRepository;
    private final FarmReportRepository farmReportRepository;
    private final CropProfileRepository cropProfileRepository;
    private final FarmAdviceRepository farmAdviceRepository;

    public AdminDashboardServiceImpl(UserRepository userRepository,
                                     WarningNoticeRepository warningNoticeRepository,
                                     FeedbackRepository feedbackRepository,
                                     VillageRepository villageRepository,
                                     FarmReportRepository farmReportRepository,
                                     CropProfileRepository cropProfileRepository,
                                     FarmAdviceRepository farmAdviceRepository) {
        this.userRepository = userRepository;
        this.warningNoticeRepository = warningNoticeRepository;
        this.feedbackRepository = feedbackRepository;
        this.villageRepository = villageRepository;
        this.farmReportRepository = farmReportRepository;
        this.cropProfileRepository = cropProfileRepository;
        this.farmAdviceRepository = farmAdviceRepository;
    }

    @Override
    // 后台首页 KPI：8 次 count 查询，每次打开管理端都跑一遍；
    // 这些数字本来就是"看个大概"，缓存 2 分钟不影响判断，却能省掉大量重复 count
    @Cacheable(cacheNames = CacheConfig.CACHE_ADMIN_STATS)
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", userRepository.count());
        // 只统计仍在有效期内的预警，避免历史/演示遗留的过期预警被计入首页 KPI
        stats.put("activeWarnings",
                warningNoticeRepository.countEffectiveByStatus("active", LocalDateTime.now()));
        stats.put("totalFeedback", feedbackRepository.count());
        stats.put("pendingFeedback", feedbackRepository.countByStatus("pending"));
        stats.put("totalVillages", villageRepository.count());
        stats.put("pendingFarmReports", farmReportRepository.countByStatus("pending"));
        stats.put("totalCrops", cropProfileRepository.count());
        stats.put("totalFarmAdvices", farmAdviceRepository.count());
        return stats;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_ADMIN_STATS, key = "'cropDistribution'")
    public List<Map<String, Object>> getCropDistribution() {
        List<CropProfile> allCrops = cropProfileRepository.findAll();
        Map<String, Double> areaMap = new HashMap<>();
        Pattern numPattern = Pattern.compile("([\\d.]+)");

        for (CropProfile c : allCrops) {
            String type = (c.getCropType() == null || c.getCropType().isBlank()) ? "其他" : c.getCropType();
            double area = 0;
            if (c.getPlantingArea() != null) {
                Matcher m = numPattern.matcher(c.getPlantingArea());
                if (m.find()) {
                    try {
                        area = Double.parseDouble(m.group(1));
                    } catch (NumberFormatException e) {
                        // 正则已经保证抓到的是数字形式（含小数点/负号），正常走不到这里；
                        // 真走到说明种植面积里混进了异常格式（如超长数字），记一条 warn 便于回头修数据。
                        log.warn("作物「{}」种植面积解析失败，按 0 计入: {}", c.getCropName(), c.getPlantingArea());
                    }
                }
            }
            areaMap.merge(type, area, Double::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        areaMap.forEach((name, value) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("name", name);
            item.put("value", value);
            result.add(item);
        });
        result.sort((a, b) -> Double.compare((Double) b.get("value"), (Double) a.get("value")));
        return result;
    }
}
