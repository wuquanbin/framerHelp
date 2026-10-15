package com.wqb.springboot.service.impl;

import com.wqb.springboot.config.CacheConfig;
import com.wqb.springboot.dto.PageResult;
import org.springframework.cache.annotation.CacheEvict;
import com.wqb.springboot.entity.*;
import com.wqb.springboot.exception.BusinessException;
import com.wqb.springboot.repository.*;
import com.wqb.springboot.security.AdminRole;
import com.wqb.springboot.service.AdminCrudService;
import com.wqb.springboot.service.AppConfigService;
import com.wqb.springboot.service.FarmReportService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台增删改查服务实现类
 * <p>
 * 承载管理端对各类实体的CRUD业务逻辑，包括字段级更新、
 * 密码加密、状态联动（如农情上报 resolved 时自动创建帮扶记录）等。
 * </p>
 */
@Service
public class AdminCrudServiceImpl implements AdminCrudService {

    private static final Logger log = LoggerFactory.getLogger(AdminCrudServiceImpl.class);

    /**
     * 每页大小上限：前端分页器顶多传 100，再大就拉一次全量了，
     * 分页也就失去意义（还容易被恶意参数拖垮）。
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 归一化分页参数，防止负数页码和超大页宽下推到 SQL。
     *
     * @param page 原始页码（从 0 开始，负数归零）
     * @param size 原始每页大小（≤0 回退到 20，超上限截断）
     * @return 带 createdAt 倒序的分页请求
     */
    private static PageRequest pageable(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * 更新时只在「提交了值」的情况下覆盖。
     * 业务表的字段大多是 NOT NULL，无条件 set 会把本次没提交的列清成 null，
     * 结果是数据库约束报错、前端只看到一句「服务暂时不可用」。
     */
    private static void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 状态过滤参数归一化：null/空白/「全部」等价于不过滤 */
    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status) || "全部".equals(status)) {
            return null;
        }
        return status;
    }

    private final VillageRepository villageRepository;
    private final CropProfileRepository cropProfileRepository;
    private final DisasterRecordRepository disasterRecordRepository;
    private final FarmAdviceRepository farmAdviceRepository;
    private final AssistanceRecordRepository assistanceRecordRepository;
    private final UserRepository userRepository;
    private final FeedbackRepository feedbackRepository;
    private final AdminUserRepository adminUserRepository;
    private final OperationLogRepository operationLogRepository;
    private final FarmReportRepository farmReportRepository;
    private final FarmResponseRepository farmResponseRepository;
    private final AppConfigRepository appConfigRepository;
    private final WarningNoticeRepository warningNoticeRepository;
    private final WeatherForecastRepository weatherForecastRepository;
    private final WeatherObservationRepository weatherObservationRepository;
    private final FarmingTaskRecordRepository farmingTaskRecordRepository;
    private final AiGenerationRecordRepository aiGenerationRecordRepository;
    private final UserCropRepository userCropRepository;
    private final FarmReportService farmReportService;
    private final AppConfigService appConfigService;
    private final PasswordEncoder passwordEncoder;

    public AdminCrudServiceImpl(
            VillageRepository villageRepository,
            CropProfileRepository cropProfileRepository,
            DisasterRecordRepository disasterRecordRepository,
            FarmAdviceRepository farmAdviceRepository,
            AssistanceRecordRepository assistanceRecordRepository,
            UserRepository userRepository,
            FeedbackRepository feedbackRepository,
            AdminUserRepository adminUserRepository,
            OperationLogRepository operationLogRepository,
            FarmReportRepository farmReportRepository,
            FarmResponseRepository farmResponseRepository,
            AppConfigRepository appConfigRepository,
            WarningNoticeRepository warningNoticeRepository,
            WeatherForecastRepository weatherForecastRepository,
            WeatherObservationRepository weatherObservationRepository,
            FarmingTaskRecordRepository farmingTaskRecordRepository,
            AiGenerationRecordRepository aiGenerationRecordRepository,
            UserCropRepository userCropRepository,
            FarmReportService farmReportService,
            AppConfigService appConfigService,
            PasswordEncoder passwordEncoder
    ) {
        this.villageRepository = villageRepository;
        this.cropProfileRepository = cropProfileRepository;
        this.disasterRecordRepository = disasterRecordRepository;
        this.farmAdviceRepository = farmAdviceRepository;
        this.assistanceRecordRepository = assistanceRecordRepository;
        this.userRepository = userRepository;
        this.feedbackRepository = feedbackRepository;
        this.adminUserRepository = adminUserRepository;
        this.operationLogRepository = operationLogRepository;
        this.farmReportRepository = farmReportRepository;
        this.farmResponseRepository = farmResponseRepository;
        this.appConfigRepository = appConfigRepository;
        this.warningNoticeRepository = warningNoticeRepository;
        this.weatherForecastRepository = weatherForecastRepository;
        this.weatherObservationRepository = weatherObservationRepository;
        this.farmingTaskRecordRepository = farmingTaskRecordRepository;
        this.aiGenerationRecordRepository = aiGenerationRecordRepository;
        this.userCropRepository = userCropRepository;
        this.farmReportService = farmReportService;
        this.appConfigService = appConfigService;
        this.passwordEncoder = passwordEncoder;
    }

    // ========== 农事任务统计 ==========

    @Override
    public java.util.Map<String, Object> farmingTaskStats() {
        java.time.LocalDate today = java.time.LocalDate.now();
        long todayTotal = farmingTaskRecordRepository.countByTaskDate(today);
        long todayDone = farmingTaskRecordRepository.countByTaskDateAndStatus(today, "completed");

        java.util.Map<String, Object> todayMap = new java.util.LinkedHashMap<>();
        todayMap.put("total", todayTotal);
        todayMap.put("completed", todayDone);
        // 完成率保留 1 位小数；无任务时给 null 而不是 0.0，前端能区分"还没产生任务"和"0% 完成"
        todayMap.put("rate", todayTotal > 0 ? Math.round(todayDone * 1000.0 / todayTotal) / 10.0 : null);

        java.util.List<java.util.Map<String, Object>> byCrop = new java.util.ArrayList<>();
        for (Object[] row : farmingTaskRecordRepository.countByCropOnDate(today)) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("cropName", row[0]);
            m.put("total", row[1]);
            m.put("completed", row[2]);
            byCrop.add(m);
        }

        java.util.List<java.util.Map<String, Object>> trend = new java.util.ArrayList<>();
        for (Object[] row : farmingTaskRecordRepository.countByDateSince(today.minusDays(6))) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("date", row[0]);
            m.put("total", row[1]);
            m.put("completed", row[2]);
            trend.add(m);
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("today", todayMap);
        result.put("byCrop", byCrop);
        result.put("trend", trend);
        return result;
    }

    // ========== 帮扶成效看板 ==========

    @Override
    public java.util.Map<String, Object> assistanceDashboard() {
        java.util.Map<String, Object> overview = new java.util.LinkedHashMap<>();

        long totalRecords = assistanceRecordRepository.count();
        long helpedFarmers = assistanceRecordRepository.countDistinctUsers();
        long monthNew = assistanceRecordRepository.countByCreatedAtGreaterThanEqual(
                java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay());
        long warningTotal = warningNoticeRepository.count();
        long reportTotal = farmReportRepository.count();
        long reportResolved = farmReportRepository.countByStatus("resolved");
        long feedbackTotal = feedbackRepository.count();
        long feedbackHandled = feedbackRepository.countByStatus("replied")
                + feedbackRepository.countByStatus("resolved");

        overview.put("totalRecords", totalRecords);
        overview.put("helpedFarmers", helpedFarmers);
        overview.put("monthNew", monthNew);
        overview.put("warningTotal", warningTotal);
        overview.put("reportTotal", reportTotal);
        overview.put("reportResolved", reportResolved);
        overview.put("reportResolveRate", rate(reportResolved, reportTotal));
        overview.put("feedbackTotal", feedbackTotal);
        overview.put("feedbackHandled", feedbackHandled);
        overview.put("feedbackHandleRate", rate(feedbackHandled, feedbackTotal));

        // 复用任务统计的今日数据，避免看板页再发一次请求
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> taskToday =
                (java.util.Map<String, Object>) farmingTaskStats().get("today");
        overview.put("taskTodayTotal", taskToday.get("total"));
        overview.put("taskTodayDone", taskToday.get("completed"));
        overview.put("taskTodayRate", taskToday.get("rate"));

        java.util.List<java.util.Map<String, Object>> byType = new java.util.ArrayList<>();
        for (Object[] row : assistanceRecordRepository.countGroupByType()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", row[0]);
            m.put("count", row[1]);
            byType.add(m);
        }

        // 村镇编码 → 名称；用户未归属村镇（villageCode 为 null）的单独归一类
        java.util.Map<String, String> villageNames = new java.util.HashMap<>();
        for (Village v : villageRepository.findAll()) {
            villageNames.put(v.getCode(), v.getName());
        }
        java.util.List<java.util.Map<String, Object>> byVillage = new java.util.ArrayList<>();
        for (Object[] row : assistanceRecordRepository.countGroupByVillageCode()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            String code = (String) row[0];
            m.put("villageCode", code);
            m.put("villageName", code == null ? "未归属" : villageNames.getOrDefault(code, code));
            m.put("count", row[1]);
            byVillage.add(m);
        }

        java.util.List<java.util.Map<String, Object>> trend = new java.util.ArrayList<>();
        for (Object[] row : assistanceRecordRepository.countByDateSince(
                java.time.LocalDateTime.now().minusDays(29).toLocalDate().atStartOfDay())) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("date", row[0] == null ? null : row[0].toString());
            m.put("count", row[1]);
            trend.add(m);
        }

        // ---- 帮扶需求按作物分布（作物名来自农情上报）----
        java.util.List<java.util.Map<String, Object>> byCrop = new java.util.ArrayList<>();
        for (Object[] row : farmReportRepository.countGroupByCropName()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("cropName", row[0]);
            m.put("count", row[1]);
            byCrop.add(m);
        }

        // ---- 响应时效：上报 → 首次帮扶响应 ----
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        // Hibernate 7（Spring Boot 4）下原生聚合查询走 List 路径才能拿到正常的行数组，
        // 单值 Object[] 返回会被多包一层（Object[1]{Object[3]}），直接取 [1] 会越界
        Object[] stats = farmReportRepository.firstResponseStatsRows().stream()
                .findFirst().orElse(null);
        long responded = toLong(stats != null ? stats[1] : null);
        long within24h = toLong(stats != null ? stats[2] : null);
        Double avgMinutes = stats == null || stats[0] == null ? null : ((Number) stats[0]).doubleValue();
        response.put("reportTotal", reportTotal);
        response.put("respondedReports", responded);
        // 响应覆盖率：被响应过的上报占全部上报的比例
        response.put("coverageRate", rate(responded, reportTotal));
        // 平均首响时长（小时，1 位小数）；没有任何响应时为 null，前端显示「--」
        response.put("avgHours", avgMinutes == null ? null : Math.round(avgMinutes / 6.0) / 10.0);
        // 24 小时内响应的占比（在「已响应」的分母上算，衡量响应速度是否达标）
        response.put("within24hCount", within24h);
        response.put("within24hRate", rate(within24h, responded));

        // ---- 最近帮扶动态（时间线）：让看板读到「谁的什么困难、多久被接住」，而不只是冷数字 ----
        java.util.List<AssistanceRecord> recentRecords =
                assistanceRecordRepository.findAll(pageable(0, 8)).getContent();

        // 一次性批量取这些记录关联上报的首响耗时，避免逐条查库
        java.util.Set<Long> recentReportIds = new java.util.HashSet<>();
        for (AssistanceRecord rec : recentRecords) {
            if (rec.getReportId() != null) {
                recentReportIds.add(rec.getReportId());
            }
        }
        java.util.Map<Long, Double> responseHoursByReport = new java.util.HashMap<>();
        if (!recentReportIds.isEmpty()) {
            for (Object[] row : farmReportRepository.firstResponseMinutesByReportIds(recentReportIds)) {
                Long rid = row[0] == null ? null : ((Number) row[0]).longValue();
                Double minutes = row[1] == null ? null : ((Number) row[1]).doubleValue();
                if (rid != null) {
                    responseHoursByReport.put(rid,
                            minutes == null ? null : Math.round(minutes / 6.0) / 10.0);
                }
            }
        }

        java.time.format.DateTimeFormatter timelineFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        java.util.List<java.util.Map<String, Object>> recent = new java.util.ArrayList<>();
        for (AssistanceRecord rec : recentRecords) {
            User farmer = rec.getUser();
            String vc = farmer != null ? farmer.getVillageCode() : null;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rec.getId());
            m.put("type", rec.getAssistanceType());
            m.put("description", rec.getDescription());
            m.put("status", rec.getStatus());
            m.put("createdAt", rec.getCreatedAt() == null
                    ? null : rec.getCreatedAt().format(timelineFmt));
            m.put("farmerName", farmer != null && farmer.getNickname() != null && !farmer.getNickname().isBlank()
                    ? farmer.getNickname()
                    : "农户#" + (farmer != null ? farmer.getId() : rec.getId()));
            m.put("villageName", vc == null ? "未归属" : villageNames.getOrDefault(vc, vc));
            m.put("reportId", rec.getReportId());
            m.put("responseHours", rec.getReportId() == null ? null
                    : responseHoursByReport.get(rec.getReportId()));
            recent.add(m);
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("overview", overview);
        result.put("byType", byType);
        result.put("byVillage", byVillage);
        result.put("byCrop", byCrop);
        result.put("response", response);
        result.put("trend", trend);
        result.put("recent", recent);
        return result;
    }

    @Override
    public byte[] exportAssistanceReport() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = assistanceDashboard();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ov = (java.util.Map<String, Object>)
                data.getOrDefault("overview", java.util.Collections.emptyMap());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> resp = (java.util.Map<String, Object>)
                data.getOrDefault("response", java.util.Collections.emptyMap());

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.createSheet("帮扶成效看板");
            sheet.setDisplayGridlines(false);

            // 列宽（单位为 1/256 字符宽）：A-G 同时服务上方两列区块与下方七列明细
            sheet.setColumnWidth(0, 26 * 256);
            sheet.setColumnWidth(1, 12 * 256);
            sheet.setColumnWidth(2, 12 * 256);
            sheet.setColumnWidth(3, 15 * 256);
            sheet.setColumnWidth(4, 11 * 256);
            sheet.setColumnWidth(5, 20 * 256);
            sheet.setColumnWidth(6, 46 * 256);

            XlsStyles st = XlsStyles.of(wb);
            int[] r = {0};

            // 标题与导出时间
            org.apache.poi.ss.usermodel.Row titleRow = sheet.createRow(r[0]++);
            titleRow.setHeightInPoints(34);
            mergeStyled(sheet, titleRow, 0, 6, st.title, "帮扶成效看板导出");

            org.apache.poi.ss.usermodel.Row metaRow = sheet.createRow(r[0]++);
            metaRow.setHeightInPoints(20);
            mergeStyled(sheet, metaRow, 0, 6, st.meta,
                    "导出时间：" + java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            addSpacer(sheet, r, 8);

            // 一、总览指标
            appendKpiBlock(sheet, r, st, "一、总览指标", "指标", "数值", new Object[][]{
                    {"帮扶记录总数", "totalRecords", "int"},
                    {"覆盖农户数", "helpedFarmers", "int"},
                    {"本月新增帮扶", "monthNew", "int"},
                    {"预警发布数", "warningTotal", "int"},
                    {"农情上报总数", "reportTotal", "int"},
                    {"上报已解决数", "reportResolved", "int"},
                    {"上报处理率(%)", "reportResolveRate", "rate"},
                    {"反馈总数", "feedbackTotal", "int"},
                    {"反馈已处理数", "feedbackHandled", "int"},
                    {"反馈处理率(%)", "feedbackHandleRate", "rate"},
                    {"今日农事任务数", "taskTodayTotal", "int"},
                    {"今日农事已完成", "taskTodayDone", "int"},
                    {"今日任务完成率(%)", "taskTodayRate", "rate"},
            }, ov);

            // 二、响应时效
            appendKpiBlock(sheet, r, st, "二、响应时效", "指标", "数值", new Object[][]{
                    {"已响应上报数", "respondedReports", "int"},
                    {"响应覆盖率(%)", "coverageRate", "rate"},
                    {"平均首次响应时长(小时)", "avgHours", "rate"},
                    {"24小时内响应数", "within24hCount", "int"},
                    {"24小时内响应占比(%)", "within24hRate", "rate"},
            }, resp);

            // 三~六：分布类区块
            appendDistBlock(sheet, r, st, "三、帮扶类型分布", "帮扶类型", "记录数", data.get("byType"), "type");
            appendDistBlock(sheet, r, st, "四、分村镇帮扶分布", "村镇", "记录数", data.get("byVillage"), "villageName");
            appendDistBlock(sheet, r, st, "五、帮扶需求作物分布", "作物", "上报数", data.get("byCrop"), "cropName");
            appendDistBlock(sheet, r, st, "六、近30天帮扶趋势", "日期", "记录数", data.get("trend"), "date");

            // 七、最近帮扶明细：汇报材料常需附上「什么时候、谁、在哪、什么困难、是否完成」的逐笔台账
            appendBand(sheet, r, st, "七、最近帮扶明细");
            org.apache.poi.ss.usermodel.Row dh = sheet.createRow(r[0]++);
            dh.setHeightInPoints(22);
            String[] detailHeads = {"时间", "农户", "村镇", "帮扶类型", "状态", "首次响应耗时(小时)", "内容"};
            for (int c = 0; c < detailHeads.length; c++) {
                setCell(dh, c, detailHeads[c], st.head);
            }
            java.util.List<?> recent = data.get("recent") instanceof java.util.List<?> l ? l
                    : java.util.Collections.emptyList();
            if (recent.isEmpty()) {
                appendEmptyRow(sheet, r, st, "暂无帮扶明细");
            } else {
                int i = 0;
                for (Object o : recent) {
                    if (!(o instanceof java.util.Map<?, ?> m)) {
                        continue;
                    }
                    boolean zebra = i++ % 2 == 1;
                    org.apache.poi.ss.usermodel.Row dr = sheet.createRow(r[0]++);
                    setCell(dr, 0, str(m.get("createdAt")), zebra ? st.cellZebra : st.cellPlain);
                    setCell(dr, 1, str(m.get("farmerName")), zebra ? st.cellZebra : st.cellPlain);
                    setCell(dr, 2, str(m.get("villageName")), zebra ? st.cellZebra : st.cellPlain);
                    setCell(dr, 3, str(m.get("type")), zebra ? st.cellZebra : st.cellPlain);
                    setCell(dr, 4, str(m.get("status")), zebra ? st.cellZebra : st.cellPlain);
                    Double hours = toDouble(m.get("responseHours"));
                    if (hours == null) {
                        setCell(dr, 5, "—", zebra ? st.dashZebra : st.dashPlain);
                    } else {
                        setNumericCell(dr, 5, hours, zebra ? st.rateZebra : st.ratePlain);
                    }
                    String desc = str(m.get("description"));
                    setCell(dr, 6, desc, zebra ? st.wrapZebra : st.wrapPlain);
                    dr.setHeightInPoints((float) Math.min(110, contentLines(desc, 46) * 15 + 8));
                }
            }
            addSpacer(sheet, r, 6);

            // 底部说明
            org.apache.poi.ss.usermodel.Row noteRow = sheet.createRow(r[0]++);
            noteRow.setHeightInPoints(18);
            mergeStyled(sheet, noteRow, 0, 6, st.note,
                    "本表由系统自动生成；比率/时长类指标保留 1 位小数，「—」表示暂无数据。");

            // 打印设置：A4 横向、宽度自适应一页
            org.apache.poi.ss.usermodel.PrintSetup ps = sheet.getPrintSetup();
            ps.setLandscape(true);
            ps.setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            sheet.setFitToPage(true);
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin, 0.5);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin, 0.5);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.45);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.45);
            sheet.getFooter().setCenter("第 &P 页 / 共 &N 页");

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("帮扶成效报表导出失败：" + e.getMessage(), e);
        }
    }

    /** 两列 KPI 区块：深绿色标题带 + 表头 + 若干「名称 / 数值」行 */
    private static void appendKpiBlock(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int[] r, XlsStyles st,
                                       String band, String col1, String col2,
                                       Object[][] defs, java.util.Map<String, Object> src) {
        appendBand(sheet, r, st, band);
        org.apache.poi.ss.usermodel.Row h = sheet.createRow(r[0]++);
        h.setHeightInPoints(21);
        mergeStyled(sheet, h, 0, 2, st.head, col1);
        mergeStyled(sheet, h, 3, 6, st.head, col2);
        int i = 0;
        for (Object[] def : defs) {
            boolean zebra = i++ % 2 == 1;
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r[0]++);
            row.setHeightInPoints(20);
            mergeStyled(sheet, row, 0, 2, zebra ? st.labelZebra : st.labelPlain, String.valueOf(def[0]));
            Double num = toDouble(src.get(String.valueOf(def[1])));
            if (num == null) {
                mergeStyled(sheet, row, 3, 6, zebra ? st.dashZebra : st.dashPlain, "—");
            } else if ("rate".equals(def[2])) {
                mergeNumeric(sheet, row, 3, 6, num, zebra ? st.rateZebra : st.ratePlain);
            } else {
                mergeNumeric(sheet, row, 3, 6, (double) num.longValue(), zebra ? st.intZebra : st.intPlain);
            }
        }
        addSpacer(sheet, r, 8);
    }

    /** 分布类区块统一输出成「名称 / 计数」两列 */
    private static void appendDistBlock(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int[] r, XlsStyles st,
                                        String band, String col1, String col2, Object rows, String nameKey) {
        appendBand(sheet, r, st, band);
        org.apache.poi.ss.usermodel.Row h = sheet.createRow(r[0]++);
        h.setHeightInPoints(21);
        mergeStyled(sheet, h, 0, 2, st.head, col1);
        mergeStyled(sheet, h, 3, 6, st.head, col2);
        java.util.List<?> list = rows instanceof java.util.List<?> l ? l : java.util.Collections.emptyList();
        if (list.isEmpty()) {
            appendEmptyRow(sheet, r, st, "暂无数据");
        } else {
            int i = 0;
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) {
                    continue;
                }
                boolean zebra = i++ % 2 == 1;
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r[0]++);
                row.setHeightInPoints(20);
                mergeStyled(sheet, row, 0, 2, zebra ? st.labelZebra : st.labelPlain, str(m.get(nameKey)));
                Double count = toDouble(m.get("count"));
                if (count == null) {
                    mergeStyled(sheet, row, 3, 6, zebra ? st.dashZebra : st.dashPlain, "—");
                } else {
                    mergeNumeric(sheet, row, 3, 6, (double) count.longValue(), zebra ? st.intZebra : st.intPlain);
                }
            }
        }
        addSpacer(sheet, r, 8);
    }

    /** 深绿色区块标题带（合并 A:G） */
    private static void appendBand(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int[] r, XlsStyles st, String text) {
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(r[0]++);
        row.setHeightInPoints(23);
        mergeStyled(sheet, row, 0, 6, st.band, text);
    }

    private static void appendEmptyRow(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int[] r, XlsStyles st, String text) {
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(r[0]++);
        row.setHeightInPoints(20);
        mergeStyled(sheet, row, 0, 6, st.empty, text);
    }

    private static void addSpacer(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int[] r, float points) {
        sheet.createRow(r[0]++).setHeightInPoints(points);
    }

    /** 合并区域并写入文本（区域内每个单元格都套同一套样式，合并后的边框才完整） */
    private static void mergeStyled(org.apache.poi.xssf.usermodel.XSSFSheet sheet, org.apache.poi.ss.usermodel.Row row,
                                    int c1, int c2, org.apache.poi.ss.usermodel.CellStyle style, String text) {
        for (int c = c1; c <= c2; c++) {
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                    org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            cell.setCellStyle(style);
        }
        row.getCell(c1).setCellValue(text == null ? "" : text);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row.getRowNum(), row.getRowNum(), c1, c2));
    }

    /** 合并区域并写入数值（数值型单元格才能在 Excel 里直接求和/做图） */
    private static void mergeNumeric(org.apache.poi.xssf.usermodel.XSSFSheet sheet, org.apache.poi.ss.usermodel.Row row,
                                     int c1, int c2, double value, org.apache.poi.ss.usermodel.CellStyle style) {
        for (int c = c1; c <= c2; c++) {
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                    org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            cell.setCellStyle(style);
        }
        row.getCell(c1).setCellValue(value);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row.getRowNum(), row.getRowNum(), c1, c2));
    }

    private static void setCell(org.apache.poi.ss.usermodel.Row row, int c, String value,
                                org.apache.poi.ss.usermodel.CellStyle style) {
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void setNumericCell(org.apache.poi.ss.usermodel.Row row, int c, double value,
                                       org.apache.poi.ss.usermodel.CellStyle style) {
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** COUNT/SUM/AVG 可能是 BigInteger/BigDecimal/Long/Double，统一转 Double；无法转时返回 null */
    private static Double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** 明细内容按列宽估算行数，用于设置行高，避免自动换行后文字被裁切 */
    private static int contentLines(String s, int widthUnits) {
        int weight = 0;
        for (int i = 0; i < s.length(); i++) {
            weight += s.charAt(i) > 0x2E7F ? 2 : 1;
        }
        int capacity = Math.max(10, (widthUnits - 4) * 2);
        return Math.max(1, (int) Math.ceil(weight / (double) capacity));
    }

    /** 报表用到的全部单元格样式（集中创建，避免到处散落 new XSSFCellStyle） */
    private static final class XlsStyles {
        private org.apache.poi.ss.usermodel.CellStyle title;
        private org.apache.poi.ss.usermodel.CellStyle meta;
        private org.apache.poi.ss.usermodel.CellStyle band;
        private org.apache.poi.ss.usermodel.CellStyle head;
        private org.apache.poi.ss.usermodel.CellStyle labelPlain;
        private org.apache.poi.ss.usermodel.CellStyle labelZebra;
        private org.apache.poi.ss.usermodel.CellStyle intPlain;
        private org.apache.poi.ss.usermodel.CellStyle intZebra;
        private org.apache.poi.ss.usermodel.CellStyle ratePlain;
        private org.apache.poi.ss.usermodel.CellStyle rateZebra;
        private org.apache.poi.ss.usermodel.CellStyle dashPlain;
        private org.apache.poi.ss.usermodel.CellStyle dashZebra;
        private org.apache.poi.ss.usermodel.CellStyle cellPlain;
        private org.apache.poi.ss.usermodel.CellStyle cellZebra;
        private org.apache.poi.ss.usermodel.CellStyle wrapPlain;
        private org.apache.poi.ss.usermodel.CellStyle wrapZebra;
        private org.apache.poi.ss.usermodel.CellStyle empty;
        private org.apache.poi.ss.usermodel.CellStyle note;

        static XlsStyles of(org.apache.poi.xssf.usermodel.XSSFWorkbook wb) {
            XlsStyles s = new XlsStyles();
            org.apache.poi.ss.usermodel.HorizontalAlignment left =
                    org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT;
            org.apache.poi.ss.usermodel.HorizontalAlignment center =
                    org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER;
            org.apache.poi.ss.usermodel.HorizontalAlignment right =
                    org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT;
            org.apache.poi.ss.usermodel.VerticalAlignment vcenter =
                    org.apache.poi.ss.usermodel.VerticalAlignment.CENTER;
            org.apache.poi.ss.usermodel.VerticalAlignment top =
                    org.apache.poi.ss.usermodel.VerticalAlignment.TOP;

            s.title = build(wb, null, false, 16, 0x2D6A4F, center, vcenter, false, null, null);
            s.meta = build(wb, null, false, 10, 0x85948B, right, vcenter, false, null, null);
            s.band = build(wb, 0x2D6A4F, true, 11, 0xFFFFFF, left, vcenter, false, null, null);
            s.band.setIndention((short) 1);
            s.head = build(wb, 0xE7F2EA, true, 10, 0x2D6A4F, center, vcenter, false, 0xC9DCCF, null);

            s.labelPlain = build(wb, null, false, 10, 0x26382D, left, vcenter, false, 0xC9DCCF, null);
            s.labelPlain.setIndention((short) 1);
            s.labelZebra = build(wb, 0xF5FAF7, false, 10, 0x26382D, left, vcenter, false, 0xC9DCCF, null);
            s.labelZebra.setIndention((short) 1);

            s.intPlain = build(wb, null, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, "#,##0");
            s.intZebra = build(wb, 0xF5FAF7, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, "#,##0");
            s.ratePlain = build(wb, null, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, "0.0");
            s.rateZebra = build(wb, 0xF5FAF7, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, "0.0");
            s.dashPlain = build(wb, null, false, 10, 0xA6B3AA, center, vcenter, false, 0xC9DCCF, null);
            s.dashZebra = build(wb, 0xF5FAF7, false, 10, 0xA6B3AA, center, vcenter, false, 0xC9DCCF, null);

            s.cellPlain = build(wb, null, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, null);
            s.cellZebra = build(wb, 0xF5FAF7, false, 10, 0x26382D, center, vcenter, false, 0xC9DCCF, null);
            s.wrapPlain = build(wb, null, false, 10, 0x26382D, left, top, true, 0xC9DCCF, null);
            s.wrapZebra = build(wb, 0xF5FAF7, false, 10, 0x26382D, left, top, true, 0xC9DCCF, null);

            s.empty = build(wb, null, false, 10, 0xA6B3AA, center, vcenter, false, 0xC9DCCF, null);
            s.note = build(wb, null, false, 9, 0x85948B, right, vcenter, false, null, null);
            return s;
        }

        private static org.apache.poi.xssf.usermodel.XSSFCellStyle build(
                org.apache.poi.xssf.usermodel.XSSFWorkbook wb, Integer fillRgb, boolean bold, int fontPt,
                int fontRgb, org.apache.poi.ss.usermodel.HorizontalAlignment h,
                org.apache.poi.ss.usermodel.VerticalAlignment v, boolean wrap, Integer borderRgb, String fmt) {
            org.apache.poi.xssf.usermodel.XSSFCellStyle style = wb.createCellStyle();
            if (fillRgb != null) {
                style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                style.setFillForegroundColor(color(fillRgb));
            }
            org.apache.poi.xssf.usermodel.XSSFFont font = wb.createFont();
            font.setFontName("微软雅黑");
            font.setFontHeightInPoints((short) fontPt);
            font.setBold(bold);
            font.setColor(color(fontRgb));
            style.setFont(font);
            style.setAlignment(h);
            style.setVerticalAlignment(v);
            style.setWrapText(wrap);
            if (borderRgb != null) {
                org.apache.poi.ss.usermodel.BorderStyle thin = org.apache.poi.ss.usermodel.BorderStyle.THIN;
                org.apache.poi.xssf.usermodel.XSSFColor bc = color(borderRgb);
                style.setBorderTop(thin);
                style.setBorderBottom(thin);
                style.setBorderLeft(thin);
                style.setBorderRight(thin);
                style.setTopBorderColor(bc);
                style.setBottomBorderColor(bc);
                style.setLeftBorderColor(bc);
                style.setRightBorderColor(bc);
            }
            if (fmt != null) {
                style.setDataFormat(wb.createDataFormat().getFormat(fmt));
            }
            return style;
        }

        private static org.apache.poi.xssf.usermodel.XSSFColor color(int rgb) {
            return new org.apache.poi.xssf.usermodel.XSSFColor(
                    new java.awt.Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF), null);
        }
    }

    /** 原生查询回来的 COUNT/SUM 可能是 BigInteger/BigDecimal/Long，统一转 long */
    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 百分比（1 位小数）；分母为 0 时给 null，前端区分「没数据」和「0%」 */
    private static Double rate(long part, long total) {
        return total > 0 ? Math.round(part * 1000.0 / total) / 10.0 : null;
    }

    // ========== Village ==========

    @Override
    public List<Village> listVillages() {
        return villageRepository.findAll();
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_VILLAGES}, allEntries = true)
    public Village createVillage(Village village) {
        // 经纬度与 adcode 是天气查询的唯一坐标来源（经纬度给 Open-Meteo 做镇级查询、adcode 给高德）。
        // 表单里通过地图选址带回这两个字段；若没选点，只能退回默认坐标，天气会指向错误地点，所以这里提示一下。
        if (village.getLatitude() == null || village.getLongitude() == null) {
            log.warn("新建村镇 {} 未提供经纬度，天气查询将退回默认坐标；请在表单中通过地图选址", village.getCode());
        }
        if (village.getAdcode() == null || village.getAdcode().isBlank()) {
            log.warn("新建村镇 {} 未提供 adcode，高德天气查询将退回市级", village.getCode());
        }
        // 这三个是 NOT NULL 列，缺了会在数据库层直接报错；在这里先给出人话提示
        if (village.getCode() == null || village.getCode().isBlank()) {
            throw new BusinessException("村镇编码不能为空");
        }
        if (village.getName() == null || village.getName().isBlank()) {
            throw new BusinessException("村镇名称不能为空");
        }
        if (village.getMainCrops() == null || village.getMainCrops().isBlank()) {
            throw new BusinessException("主要作物不能为空");
        }
        return villageRepository.save(village);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_VILLAGES}, allEntries = true)
    public Village updateVillage(Long id, Village village) {
        Village existing = villageRepository.findById(id)
                .orElseThrow(() -> new BusinessException("村落不存在"));
        // 村镇管理员只能改本村（adcode / 经纬度被别村的人动过，整条天气链会静默失真）
        assertVillageInScope(existing);
        // 只覆盖提交了的字段：name / code / mainCrops 是 NOT NULL，无条件 set 会清成 null 报 500
        if (village.getName() != null) existing.setName(village.getName());
        if (village.getMainCrops() != null) existing.setMainCrops(village.getMainCrops());
        if (village.getTerrain() != null) existing.setTerrain(village.getTerrain());
        if (village.getAssistanceLevel() != null) existing.setAssistanceLevel(village.getAssistanceLevel());
        // code 是关联键（农情 / 农户 / 台账都按它归属），改了会让这批数据静默脱离村镇范围，直接拒绝
        if (village.getCode() != null && !village.getCode().equals(existing.getCode())) {
            throw new BusinessException("村镇编码是数据关联键，不支持修改");
        }
        // 经纬度和行政区编码：编辑时重新地图选址可更新（未提交则保持原值）
        if (village.getLatitude() != null) existing.setLatitude(village.getLatitude());
        if (village.getLongitude() != null) existing.setLongitude(village.getLongitude());
        if (village.getAdcode() != null && !village.getAdcode().isBlank()) existing.setAdcode(village.getAdcode());
        return villageRepository.save(existing);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.CACHE_VILLAGES}, allEntries = true)
    public void deleteVillage(Long id, boolean force) {
        Village village = villageRepository.findById(id)
                .orElseThrow(() -> new BusinessException("村镇不存在或已删除"));

        // 删除前检查外键引用：作物档案/农事建议/灾害记录/天气预报/气象观测/预警通知
        // （这 6 张表通过 village_id 外键关联，直接删会被数据库约束拒绝，前端只能看到"删除失败"）
        List<String> conflicts = new ArrayList<>();
        long cropCount = cropProfileRepository.countByVillage(village);
        if (cropCount > 0) conflicts.add("作物档案 " + cropCount + " 条");
        long adviceCount = farmAdviceRepository.countByVillage(village);
        if (adviceCount > 0) conflicts.add("农事建议 " + adviceCount + " 条");
        long disasterCount = disasterRecordRepository.countByVillage(village);
        if (disasterCount > 0) conflicts.add("灾害记录 " + disasterCount + " 条");
        long forecastCount = weatherForecastRepository.countByVillage(village);
        if (forecastCount > 0) conflicts.add("天气预报 " + forecastCount + " 条");
        long observationCount = weatherObservationRepository.countByVillage(village);
        if (observationCount > 0) conflicts.add("气象观测 " + observationCount + " 条");
        long warningCount = warningNoticeRepository.countByVillage(village);
        if (warningCount > 0) conflicts.add("预警通知 " + warningCount + " 条");

        if (!conflicts.isEmpty() && !force) {
            throw new BusinessException("无法删除「" + village.getName() + "」：该村镇下存在 "
                    + String.join("、", conflicts) + "，请先删除相关数据或选择强制删除");
        }

        // 强制删除：级联清掉 6 张关联表的数据
        if (!conflicts.isEmpty()) {
            cropProfileRepository.deleteByVillage(village);
            farmAdviceRepository.deleteByVillage(village);
            disasterRecordRepository.deleteByVillage(village);
            weatherForecastRepository.deleteByVillage(village);
            weatherObservationRepository.deleteByVillage(village);
            warningNoticeRepository.deleteByVillage(village);
        }

        // 该村镇的用户解除归属关系（villageCode 为普通字符串列，无外键约束）
        userRepository.findByVillageCode(village.getCode())
                .forEach(u -> u.setVillageCode(null));

        villageRepository.deleteById(id);
    }

    // ========== CropProfile ==========

    @Override
    public List<CropProfile> listCrops() {
        return cropProfileRepository.findAll();
    }

    @Override
    public PageResult<CropProfile> listCropsPaged(int page, int size, String villageCode, String cropType) {
        // 数据范围优先：村镇管理员传什么都只能看本村，前端参数翻不出去
        String scope = currentVillageScope();
        String effectiveVillage = scope != null ? scope : blankToNull(villageCode);
        String effectiveType = blankToNull(cropType);

        Specification<CropProfile> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (effectiveVillage != null) {
                conditions.add(cb.equal(root.get("village").get("code"), effectiveVillage));
            }
            if (effectiveType != null) {
                conditions.add(cb.equal(root.get("cropType"), effectiveType));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
        return PageResult.of(cropProfileRepository.findAll(spec, pageable(page, size)));
    }

    /** 空白字符串一律当「没传」，避免 ?cropType= 这种空参数被当成真条件筛出 0 条 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_CROP_PROFILES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public CropProfile createCrop(CropProfile crop) {
        if (crop.getCropName() == null || crop.getCropName().isBlank()) {
            throw new BusinessException("作物名称不能为空");
        }
        assertVillageInScope(crop.getVillage());
        crop.setVillage(resolveVillage(crop.getVillage()));
        return cropProfileRepository.save(crop);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_CROP_PROFILES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public CropProfile updateCrop(Long id, CropProfile crop) {
        CropProfile existing = cropProfileRepository.findById(id)
                .orElseThrow(() -> new BusinessException("作物不存在"));
        assertUpdateInScope(existing.getVillage(), crop.getVillage());
        if (crop.getVillage() != null) {
            existing.setVillage(resolveVillage(crop.getVillage()));
        }
        // 只覆盖提交了的字段：这些列全是 NOT NULL，无条件 set 会把没提交的清成 null → 数据库报错。
        // 前端目前传完整表单所以看不出问题，但接口裸调用「只改一个字段」会直接 500。
        setIfPresent(existing::setCropName, crop.getCropName());
        setIfPresent(existing::setCropType, crop.getCropType());
        setIfPresent(existing::setPlantingArea, crop.getPlantingArea());
        setIfPresent(existing::setGrowthStage, crop.getGrowthStage());
        setIfPresent(existing::setSuitableWeather, crop.getSuitableWeather());
        setIfPresent(existing::setRiskWeather, crop.getRiskWeather());
        setIfPresent(existing::setFarmingNotes, crop.getFarmingNotes());
        if (crop.getIsMainCrop() != null) existing.setIsMainCrop(crop.getIsMainCrop());
        if (crop.getRiskLevel() != null) existing.setRiskLevel(crop.getRiskLevel());
        return cropProfileRepository.save(existing);
    }

    /**
     * 把请求体里只带 id 的村镇引用替换为库中的完整托管实体：既能在写库前校验村镇存在
     * （而不是等外键约束报错），也让响应里带出完整村镇信息 —— 否则前端拿到的只有请求回显的 id 桩对象。
     */
    private Village resolveVillage(Village village) {
        if (village == null || village.getId() == null) {
            throw new BusinessException("请选择所属村镇");
        }
        return villageRepository.findById(village.getId())
                .orElseThrow(() -> new BusinessException("所属村镇不存在"));
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_CROP_PROFILES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public void deleteCrop(Long id) {
        // 村镇管理员只能删本村镇的作物
        CropProfile existing = cropProfileRepository.findById(id)
                .orElseThrow(() -> new BusinessException("作物档案不存在"));
        assertVillageInScope(existing.getVillage());
        cropProfileRepository.deleteById(id);
    }

    // ========== DisasterRecord ==========

    @Override
    public List<DisasterRecord> listDisasters() {
        return disasterRecordRepository.findAll();
    }

    @Override
    public PageResult<DisasterRecord> listDisastersPaged(int page, int size) {
        String scope = currentVillageScope();
        return scope == null
                ? PageResult.of(disasterRecordRepository.findAll(pageable(page, size)))
                : PageResult.of(disasterRecordRepository.findByVillage_Code(scope, pageable(page, size)));
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_DISASTERS, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public DisasterRecord createDisaster(DisasterRecord disaster) {
        if (disaster.getDisasterName() == null || disaster.getDisasterName().isBlank()) {
            throw new BusinessException("灾害名称不能为空");
        }
        assertVillageInScope(disaster.getVillage());
        return disasterRecordRepository.save(disaster);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_DISASTERS, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public DisasterRecord updateDisaster(Long id, DisasterRecord disaster) {
        DisasterRecord existing = disasterRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("灾害记录不存在"));
        assertUpdateInScope(existing.getVillage(), disaster.getVillage());
        // 同上：只覆盖提交了的字段，避免把 NOT NULL 列清成 null
        setIfPresent(existing::setDisasterName, disaster.getDisasterName());
        setIfPresent(existing::setAffectedArea, disaster.getAffectedArea());
        setIfPresent(existing::setDisasterType, disaster.getDisasterType());
        setIfPresent(existing::setTriggerWeather, disaster.getTriggerWeather());
        setIfPresent(existing::setDescription, disaster.getDescription());
        setIfPresent(existing::setRemedy, disaster.getRemedy());
        if (disaster.getSeverity() != null) existing.setSeverity(disaster.getSeverity());
        if (disaster.getVillage() != null) existing.setVillage(disaster.getVillage());
        return disasterRecordRepository.save(existing);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_DISASTERS, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public void deleteDisaster(Long id) {
        // 同上：村镇管理员只能删本村镇的灾害记录
        DisasterRecord existing = disasterRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("灾害记录不存在"));
        assertVillageInScope(existing.getVillage());
        disasterRecordRepository.deleteById(id);
    }

    // ========== FarmAdvice ==========

    @Override
    public List<FarmAdvice> listFarmAdvices() {
        return farmAdviceRepository.findAll();
    }

    @Override
    public PageResult<FarmAdvice> listFarmAdvicesPaged(int page, int size) {
        String scope = currentVillageScope();
        return scope == null
                ? PageResult.of(farmAdviceRepository.findAll(pageable(page, size)))
                : PageResult.of(farmAdviceRepository.findByVillage_Code(scope, pageable(page, size)));
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_FARM_ADVICES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public FarmAdvice createFarmAdvice(FarmAdvice advice) {
        if (advice.getTitle() == null || advice.getTitle().isBlank()) {
            throw new BusinessException("建议标题不能为空");
        }
        assertVillageInScope(advice.getVillage());
        // adviceCode 为非空列，后台/AI 发布未显式填写时自动生成唯一编码
        if (advice.getAdviceCode() == null || advice.getAdviceCode().isBlank()) {
            advice.setAdviceCode("ADV" + System.currentTimeMillis());
        }
        return farmAdviceRepository.save(advice);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_FARM_ADVICES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public FarmAdvice updateFarmAdvice(Long id, FarmAdvice advice) {
        FarmAdvice existing = farmAdviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("农事建议不存在"));
        assertUpdateInScope(existing.getVillage(), advice.getVillage());
        // 同上：只覆盖提交了的字段，避免把 NOT NULL 列清成 null
        setIfPresent(existing::setTitle, advice.getTitle());
        setIfPresent(existing::setSummary, advice.getSummary());
        setIfPresent(existing::setRecommendation, advice.getRecommendation());
        setIfPresent(existing::setPriorityTag, advice.getPriorityTag());
        setIfPresent(existing::setIconKey, advice.getIconKey());
        setIfPresent(existing::setAdviceCode, advice.getAdviceCode());
        if (advice.getPriority() != null) existing.setPriority(advice.getPriority());
        if (advice.getVillage() != null) existing.setVillage(advice.getVillage());
        return farmAdviceRepository.save(existing);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_FARM_ADVICES, CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public void deleteFarmAdvice(Long id) {
        farmAdviceRepository.deleteById(id);
    }

    // ========== AssistanceRecord ==========

    @Override
    public List<AssistanceRecord> listAssistanceRecords() {
        return assistanceRecordRepository.findAll();
    }

    @Override
    public PageResult<AssistanceRecord> listAssistanceRecordsPaged(int page, int size) {
        String scope = currentVillageScope();
        org.springframework.data.domain.Page<AssistanceRecord> result = scope == null
                ? assistanceRecordRepository.findAll(pageable(page, size))
                : assistanceRecordRepository.findByUser_VillageCode(scope, pageable(page, size));
        fillReportTitles(result.getContent());
        return PageResult.of(result);
    }

    /** 批量补关联农情上报标题，避免台账列表逐条查库 */
    private void fillReportTitles(List<AssistanceRecord> records) {
        List<Long> reportIds = records.stream()
                .map(AssistanceRecord::getReportId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (reportIds.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> titles = new java.util.HashMap<>();
        for (FarmReport r : farmReportRepository.findAllById(reportIds)) {
            titles.put(r.getId(), r.getTitle());
        }
        records.forEach(rec -> {
            if (rec.getReportId() != null) {
                rec.setReportTitle(titles.get(rec.getReportId()));
            }
        });
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public AssistanceRecord createAssistanceRecord(AssistanceRecord record) {
        // 台账必须归属农户：后台补录只传 reportId，由关联农情上报反查上报人
        if (record.getUser() == null) {
            Long reportId = record.getReportId();
            if (reportId == null) {
                throw new BusinessException("请选择关联农情上报后再登记台账");
            }
            if (assistanceRecordRepository.existsByReportId(reportId)) {
                throw new BusinessException("该农情上报已登记帮扶台账，请勿重复登记");
            }
            FarmReport report = farmReportRepository.findById(reportId)
                    .orElseThrow(() -> new BusinessException("关联农情上报不存在"));
            User farmer = userRepository.findByOpenid(report.getOpenid()).orElse(null);
            if (farmer == null) {
                throw new BusinessException("上报农户账号不存在，无法登记台账");
            }
            record.setUser(farmer);
        }
        assertUserVillageInScope(record.getUser());
        return assistanceRecordRepository.save(record);
    }

    @Override
    public AssistanceRecord updateAssistanceRecord(Long id, AssistanceRecord record) {
        AssistanceRecord existing = assistanceRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("帮扶记录不存在"));
        assertUserVillageInScope(existing.getUser());
        if (record.getUser() != null) {
            assertUserVillageInScope(record.getUser());
        }
        existing.setUser(record.getUser());
        existing.setAssistanceType(record.getAssistanceType());
        existing.setDescription(record.getDescription());
        existing.setStatus(record.getStatus());
        return assistanceRecordRepository.save(existing);
    }

    @Override
    public void deleteAssistanceRecord(Long id) {
        assistanceRecordRepository.deleteById(id);
    }

    // ========== User ==========

    @Override
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Override
    public PageResult<User> listUsersPaged(int page, int size) {
        return listUsersPaged(page, size, null, null, null);
    }

    @Override
    public PageResult<User> listUsersPaged(int page, int size, String keyword, Integer status, String villageCode) {
        // 数据范围优先于页面传来的村镇筛选：村镇管理员不能靠改请求参数看到别的村镇
        String scope = currentVillageScope();
        String effectiveVillage = scope != null ? scope : villageCode;
        return PageResult.of(userRepository.findAll(userSpec(keyword, status, effectiveVillage), pageable(page, size)));
    }

    /**
     * 拼农户查询条件：三个条件都为「空」时返回 null（JPA 里 null Specification = 不过滤，
     * 直接走 findAll 的索引扫描，比拼一个恒真条件更省）。
     */
    private static org.springframework.data.jpa.domain.Specification<User> userSpec(
            String keyword, Integer status, String villageCode) {
        org.springframework.data.jpa.domain.Specification<User> spec = null;

        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        if (kw != null) {
            // 纯数字时顺便按主键查，方便运营贴 ID 直接定位
            Long id = null;
            if (kw.chars().allMatch(Character::isDigit)) {
                try {
                    id = Long.valueOf(kw);
                } catch (NumberFormatException ignored) {
                    id = null; // 超长数字串，退化为只做文本匹配
                }
            }
            final String pattern = "%" + kw.toLowerCase() + "%";
            final Long idEq = id;
            org.springframework.data.jpa.domain.Specification<User> s =
                    (root, query, cb) -> cb.or(
                            cb.like(cb.lower(root.get("nickname")), pattern),
                            cb.like(cb.lower(root.get("openid")), pattern),
                            cb.like(cb.lower(root.get("phone")), pattern),
                            idEq == null ? cb.disjunction() : cb.equal(root.get("id"), idEq)
                    );
            spec = s;
        }

        if (status != null) {
            org.springframework.data.jpa.domain.Specification<User> s =
                    (root, query, cb) -> cb.equal(root.get("status"), status);
            spec = spec == null ? s : spec.and(s);
        }

        String vc = (villageCode == null || villageCode.isBlank()) ? null : villageCode.trim();
        if (vc != null) {
            org.springframework.data.jpa.domain.Specification<User> s =
                    (root, query, cb) -> cb.equal(root.get("villageCode"), vc);
            spec = spec == null ? s : spec.and(s);
        }
        return spec;
    }

    @Override
    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    @Override
    public User updateUserStatus(Long id, Integer status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        user.setStatus(status);
        return userRepository.save(user);
    }

    /**
     * 删除农户账号：先清引用它的子表，再删用户本身。整段在一个事务里，中途任何一步失败会整体回滚 ——
     * 否则会出现「帮扶台账删了、用户还在」这种半删除的脏状态。
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.CACHE_ADMIN_STATS, CacheConfig.CACHE_OVERVIEW}, allEntries = true)
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        // 1) 有外键指向 app_user 的表：不先删，最后删用户会撞外键约束
        assistanceRecordRepository.deleteByUser(user);
        feedbackRepository.deleteByUser(user);
        userCropRepository.deleteByUserId(id);

        // 2) 按 openid 关联、没有外键的表：不删不会报错，但会留下永远读不到的脏数据，
        //    而且同一个微信用户下次登录会看到上一次的打卡记录
        farmingTaskRecordRepository.deleteByOpenid(user.getOpenid());

        // 3) 农情上报刻意保留：属于村镇公共台账，且已快照昵称/头像，不随个人销户消失
        userRepository.delete(user);
        log.info("超级管理员删除农户账号: id={}, openid={}, nickname={}",
                id, user.getOpenid(), user.getNickname());
    }

    // ========== Feedback ==========

    @Override
    public List<Feedback> listFeedbacks() {
        return feedbackRepository.findAll();
    }

    @Override
    public PageResult<Feedback> listFeedbacksPaged(int page, int size, String status) {
        String s = normalizeStatus(status);
        return PageResult.of(s == null
                ? feedbackRepository.findAll(pageable(page, size))
                : feedbackRepository.findByStatus(s, pageable(page, size)));
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public Feedback updateFeedback(Long id, Feedback feedback) {
        Feedback existing = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException("反馈不存在"));
        existing.setStatus(feedback.getStatus());
        // 仅当本次请求携带回复内容时才覆盖，避免"标记解决"(只传 status)把已有回复清空
        if (feedback.getReply() != null) {
            existing.setReply(feedback.getReply());
        }
        return feedbackRepository.save(existing);
    }

    // ========== AdminUser ==========

    @Override
    public List<AdminUser> listAdmins() {
        return adminUserRepository.findAll();
    }

    @Override
    public AdminUser createAdmin(AdminUser admin) {
        // 角色合法性 + 数据范围校验放在服务层而不是只靠前端：
        // 接口是可以直接被 curl 打的，前端隐藏菜单不算权限控制
        AdminRole role = AdminRole.normalize(admin.getRole());
        if (role.requiresVillage() && (admin.getVillageCode() == null || admin.getVillageCode().isBlank())) {
            throw new BusinessException("村镇管理员必须绑定所属村镇");
        }
        admin.setRole(role.name());
        if (!role.requiresVillage()) {
            admin.setVillageCode(null); // 非村镇管理员绑了也不生效，避免留下误导性数据
        }
        admin.setPassword(passwordEncoder.encode(admin.getPassword()));
        return adminUserRepository.save(admin);
    }

    @Override
    public AdminUser updateAdmin(Long id, AdminUser admin) {
        AdminUser existing = adminUserRepository.findById(id)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        existing.setRealName(admin.getRealName());
        existing.setPhone(admin.getPhone());
        existing.setEmail(admin.getEmail());
        existing.setStatus(admin.getStatus());

        // 角色：空表示本次不改角色（前端编辑时可能只改姓名/手机号）
        if (admin.getRole() != null && !admin.getRole().isBlank()) {
            AdminRole role = AdminRole.normalize(admin.getRole());
            if (role.requiresVillage() && (admin.getVillageCode() == null || admin.getVillageCode().isBlank())) {
                // 编辑时没重选村镇 → 沿用原值，只有原值也为空才报错
                if (existing.getVillageCode() == null || existing.getVillageCode().isBlank()) {
                    throw new BusinessException("村镇管理员必须绑定所属村镇");
                }
            } else {
                existing.setRole(role.name());
                existing.setVillageCode(role.requiresVillage() ? admin.getVillageCode() : null);
            }
        }
        if (admin.getPassword() != null && !admin.getPassword().isEmpty()) {
            existing.setPassword(passwordEncoder.encode(admin.getPassword()));
        }
        return adminUserRepository.save(existing);
    }

    @Override
    public void deleteAdmin(Long id) {
        // 不允许删掉最后一个超级管理员：否则系统会进入「没人能管账号」的死局
        AdminUser target = adminUserRepository.findById(id)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        if (AdminRole.SUPER_ADMIN == AdminRole.normalize(target.getRole())) {
            long superCount = adminUserRepository.findAll().stream()
                    .filter(a -> AdminRole.SUPER_ADMIN == AdminRole.normalize(a.getRole()))
                    .count();
            if (superCount <= 1) {
                throw new BusinessException("至少保留一个超级管理员");
            }
        }
        adminUserRepository.deleteById(id);
    }

    // ========== OperationLog ==========

    @Override
    public org.springframework.data.domain.Page<OperationLog> listOperationLogsPaged(
            int page, int size, String operator, String module, String type, String startDate, String endDate) {
        org.springframework.data.jpa.domain.Specification<OperationLog> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (operator != null && !operator.isBlank()) {
                predicates.add(cb.like(root.get("operatorName"), "%" + operator + "%"));
            }
            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("operationType"), type));
            }
            try {
                if (startDate != null && !startDate.isBlank()) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                            java.time.LocalDate.parse(startDate).atStartOfDay()));
                }
                if (endDate != null && !endDate.isBlank()) {
                    predicates.add(cb.lessThan(root.get("createdAt"),
                            java.time.LocalDate.parse(endDate).plusDays(1).atStartOfDay()));
                }
            } catch (Exception ignored) {
                // 日期格式不合法就忽略该条件（退回查全部），但要留日志：
                // 用户反馈「筛选了没反应」时，能分清是参数问题还是查询问题。
                log.warn("操作日志日期筛选参数不合法，已忽略该条件: start={}, end={}", startDate, endDate);
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return operationLogRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    @Override
    public void clearOperationLogs() {
        operationLogRepository.deleteAll();
    }

    // ========== FarmReport ==========

    @Override
    public List<FarmReport> listFarmReports() {
        return farmReportService.getReports(null);
    }

    @Override
    public PageResult<FarmReport> listFarmReportsPaged(int page, int size, String status, String type) {
        String s = normalizeStatus(status);
        String t = normalizeStatus(type); // 同一归一化规则：null/空白/「全部」即不过滤
        String scope = currentVillageScope(); // 村镇管理员只看到本村镇，其余角色为 null

        // 用 Specification 而不是继续堆派生方法：三个条件（状态/类型/村镇）任意组合，
        // 派生方法要写 2^3 个，组合一多必然漏
        org.springframework.data.jpa.domain.Specification<FarmReport> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (s != null) ps.add(cb.equal(root.get("status"), s));
            if (t != null) ps.add(cb.equal(root.get("reportType"), t));
            if (scope != null) ps.add(cb.equal(root.get("villageCode"), scope));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return PageResult.of(farmReportRepository.findAll(spec, pageable(page, size)));
    }

    /**
     * 当前登录后台账号的数据范围：村镇管理员返回其绑定村镇编码，其余角色返回 null（不限）。
     * <p>
     * 取名 scope 而不是 filter，是提醒自己这是「权限」而不是「搜索条件」——
     * 用户不能通过传参绕过它。
     * </p>
     */
    private String currentVillageScope() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean isVillageAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> AdminRole.VILLAGE_ADMIN.authority().equals(a.getAuthority()));
        if (!isVillageAdmin) {
            return null;
        }
        return adminUserRepository.findByUsername(auth.getName())
                .map(AdminUser::getVillageCode)
                .orElse(null);
    }

    /**
     * 写操作越权校验：村镇管理员只能操作本村镇的数据，其余角色不限制。
     * <p>
     * 和 {@link #currentVillageScope()} 配套使用 —— 前者管「看不见」，这里管「改不了」。
     * 只有列表过滤是不够的：接口可以直接被 curl 打到，前端藏了按钮拦不住。
     * </p>
     */
    private void assertVillageInScope(Village village) {
        String scope = currentVillageScope();
        if (scope == null) {
            return;
        }
        if (village == null || village.getCode() == null) {
            throw new BusinessException("请选择所属村镇");
        }
        if (!scope.equals(village.getCode())) {
            throw new BusinessException("村镇管理员只能操作本村镇的数据");
        }
    }

    /** 农情上报的村镇是字符串列（不是 Village 关联），单独一套校验 */
    private void assertReportVillageInScope(String villageCode) {
        String scope = currentVillageScope();
        if (scope == null) {
            return;
        }
        if (villageCode == null || !scope.equals(villageCode)) {
            throw new BusinessException("村镇管理员只能操作本村镇的农情上报");
        }
    }

    /**
     * 帮扶台账没有直接的村镇字段，归属走「被帮扶农户的 villageCode」。
     * 与看板统计 {@code countGroupByVillageCode} 用的是同一套口径。
     */
    private void assertUserVillageInScope(User user) {
        String scope = currentVillageScope();
        if (scope == null) {
            return;
        }
        if (user == null || !scope.equals(user.getVillageCode())) {
            throw new BusinessException("村镇管理员只能操作本村镇农户的帮扶台账");
        }
    }

    /** 更新场景：既不能改别人的数据，也不能把数据改到别的村镇去 */
    private void assertUpdateInScope(Village existingVillage, Village incomingVillage) {
        assertVillageInScope(existingVillage);
        if (incomingVillage != null) {
            assertVillageInScope(incomingVillage);
        }
    }

    @Override
    public FarmReport getFarmReport(Long id) {
        return farmReportService.getReport(id);
    }

    @Override
    public FarmReport updateFarmReport(Long id, FarmReport report) {
        FarmReport existing = farmReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException("上报记录不存在"));
        assertReportVillageInScope(existing.getVillageCode());
        if (report.getVillageCode() != null && !report.getVillageCode().isBlank()) {
            assertReportVillageInScope(report.getVillageCode());
        }
        // 非空字段为null时保持原值，避免触发数据库NOT NULL约束
        if (report.getTitle() != null) existing.setTitle(report.getTitle());
        if (report.getDescription() != null) existing.setDescription(report.getDescription());
        if (report.getReportType() != null) existing.setReportType(report.getReportType());
        if (report.getStatus() != null) existing.setStatus(report.getStatus());
        if (report.getUrgency() != null) existing.setUrgency(report.getUrgency());
        if (report.getVillageCode() != null && !report.getVillageCode().isBlank()) {
            existing.setVillageCode(report.getVillageCode());
            // village_name列非空：名称缺失时按编码查库补齐
            if (report.getVillageName() == null || report.getVillageName().isBlank()) {
                villageRepository.findByCode(report.getVillageCode())
                        .ifPresent(v -> existing.setVillageName(v.getName()));
            } else {
                existing.setVillageName(report.getVillageName());
            }
        }
        if (report.getCropName() != null) existing.setCropName(report.getCropName());
        if (report.getGrowthStage() != null) existing.setGrowthStage(report.getGrowthStage());
        return farmReportRepository.save(existing);
    }

    @Override
    @Transactional // 先删帮扶响应再删上报，须在同一事务内完成
    @CacheEvict(cacheNames = {CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public void deleteFarmReport(Long id) {
        farmResponseRepository.findByReportIdOrderByCreatedAtAsc(id)
                .forEach(farmResponseRepository::delete);
        farmReportRepository.deleteById(id);
    }

    @Override
    @CacheEvict(cacheNames = {CacheConfig.CACHE_ADMIN_STATS}, allEntries = true)
    public FarmReport updateFarmReportStatus(Long id, String status) {
        FarmReport report = farmReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException("上报记录不存在"));
        assertReportVillageInScope(report.getVillageCode());
        String oldStatus = report.getStatus();
        report.setStatus(status);
        FarmReport saved = farmReportRepository.save(report);

        // 状态变更为 resolved 时，自动创建帮扶记录
        if ("resolved".equals(status) && !"resolved".equals(oldStatus)) {
            createAssistanceFromFarmReport(report);
        }

        return saved;
    }

    /**
     * 根据农情上报自动创建帮扶记录
     * <p>
     * 根据上报类型映射帮扶类型，关联上报用户（若无则取第一个用户）。
     * </p>
     *
     * @param report 农情上报记录
     */
    private void createAssistanceFromFarmReport(FarmReport report) {
        try {
            // 手工补录可能已建档，避免自动建档产生重复台账
            if (assistanceRecordRepository.existsByReportId(report.getId())) {
                return;
            }
            User user = userRepository.findByOpenid(report.getOpenid()).orElse(null);
            if (user == null) {
                List<User> users = userRepository.findAll();
                if (users.isEmpty()) return;
                user = users.get(0);
            }
            AssistanceRecord record = new AssistanceRecord();
            record.setUser(user);
            record.setReportId(report.getId());

            String assistanceType = switch (report.getReportType()) {
                case "pest" -> "病虫害防治";
                case "disaster" -> "灾损救助";
                case "growth" -> "长势指导";
                case "help" -> "诉求帮扶";
                default -> "综合帮扶";
            };
            record.setAssistanceType(assistanceType);
            record.setDescription(buildLedgerDescription(report.getTitle(), report.getDescription()));
            record.setStatus("已完成");
            assistanceRecordRepository.save(record);
        } catch (Exception e) {
            // 台账生成失败时留下日志，不影响状态更新主流程
            log.error("农情上报(id={})自动生成帮扶记录失败: {}", report.getId(), e.getMessage(), e);
        }
    }

    /** 台账事由：描述已含标题时不重复拼接，限长 250 */
    private static String buildLedgerDescription(String title, String detail) {
        String t = title == null ? "" : title.trim();
        String d = detail == null ? "" : detail.trim();
        String text;
        if (d.isEmpty()) {
            text = t;
        } else if (t.isEmpty() || d.contains(t)) {
            text = d;
        } else {
            text = t + "：" + d;
        }
        return text.length() > 250 ? text.substring(0, 250) : text;
    }

    // ========== FarmResponse ==========

    @Override
    public List<FarmResponse> listFarmResponses(Long reportId) {
        if (reportId != null) {
            return farmReportService.getResponses(reportId);
        }
        return farmReportService.getAllResponses();
    }

    @Override
    public FarmResponse addFarmReportResponse(Long reportId, FarmResponse response) {
        // responder_openid / response_type 为数据库非空列，缺失时提前校验，避免落到 SQL 约束 500
        if (response.getResponderOpenid() == null || response.getResponderOpenid().isBlank()) {
            throw new BusinessException("响应人openid不能为空");
        }
        if (response.getResponseType() == null || response.getResponseType().isBlank()) {
            throw new BusinessException("响应类型不能为空");
        }
        if (response.getContent() == null || response.getContent().isBlank()) {
            throw new BusinessException("响应内容不能为空");
        }
        farmReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("上报记录不存在"));
        response.setReportId(reportId);
        return farmReportService.addResponse(response);
    }

    @Override
    public void deleteFarmResponse(Long id) {
        farmResponseRepository.deleteById(id);
    }

    // ========== AppConfig ==========

    @Override
    public List<AppConfig> listConfigs() {
        return appConfigRepository.findAllByOrderByCategoryAscConfigKeyAsc();
    }

    /** 改配置后必须清缓存，否则页面显示「保存成功」、运行时还在用旧值 */
    @Override
    public AppConfig updateConfig(Long id, AppConfig config) {
        AppConfig existing = appConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException("配置项不存在"));
        existing.setConfigValue(config.getConfigValue());
        existing.setDescription(config.getDescription());
        AppConfig saved = appConfigRepository.save(existing);
        appConfigService.evictAll();
        return saved;
    }

    @Override
    public AppConfig createConfig(AppConfig config) {
        AppConfig saved = appConfigRepository.save(config);
        appConfigService.evictAll();
        return saved;
    }

    @Override
    public void deleteConfig(Long id) {
        appConfigRepository.deleteById(id);
        appConfigService.evictAll();
    }

    // ========== AI 生成记录 ==========

    /**
     * 保存 AI 生成结果。
     * <p>
     * 操作人从安全上下文取：前端传什么都不可信，而且页面上本来也不该让调用方随意署名。
     * 取不到（理论上不会，接口都要登录）就记 unknown，不让插入因非空约束失败。
     * </p>
     */
    @Override
    public AiGenerationRecord saveAiGeneration(AiGenerationRecord record) {
        String operator = "unknown";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            operator = auth.getName();
            if (operator != null && operator.length() > 100) {
                operator = operator.substring(0, 100);
            }
        }
        record.setOperatorName(operator);
        if (record.getPublished() == null) {
            record.setPublished(false);
        }
        return aiGenerationRecordRepository.save(record);
    }

    @Override
    public List<AiGenerationRecord> listAiGenerations() {
        return aiGenerationRecordRepository.findTop50ByOrderByCreatedAtDesc();
    }

    @Override
    public void deleteAiGeneration(Long id) {
        aiGenerationRecordRepository.deleteById(id);
    }

    @Override
    public AiGenerationRecord markAiGenerationPublished(Long id, Long adviceId) {
        AiGenerationRecord record = aiGenerationRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("生成记录不存在"));
        record.setPublished(true);
        record.setPublishedAdviceId(adviceId);
        return aiGenerationRecordRepository.save(record);
    }

    // ========== WarningNotice ==========

    @Override
    public List<WarningNotice> listWarnings() {
        return warningNoticeRepository.findAll();
    }

    @Override
    public PageResult<WarningNotice> listWarningsPaged(int page, int size) {
        return PageResult.of(warningNoticeRepository.findAll(pageable(page, size)));
    }

    @Override
    public WarningNotice createWarning(WarningNotice warning) {
        warning.setVillage(resolveVillage(warning.getVillage()));
        return warningNoticeRepository.save(warning);
    }

    @Override
    public WarningNotice updateWarning(Long id, WarningNotice warning) {
        WarningNotice existing = warningNoticeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("预警通知不存在"));
        if (warning.getVillage() != null) {
            existing.setVillage(resolveVillage(warning.getVillage()));
        }
        existing.setWarningType(warning.getWarningType());
        existing.setSeverity(warning.getSeverity());
        existing.setTitle(warning.getTitle());
        existing.setContent(warning.getContent());
        existing.setIssuedAt(warning.getIssuedAt());
        existing.setExpiresAt(warning.getExpiresAt());
        existing.setStatus(warning.getStatus());
        existing.setImpactArea(warning.getImpactArea());
        existing.setDefenseGuidance(warning.getDefenseGuidance());
        return warningNoticeRepository.save(existing);
    }

    @Override
    public void deleteWarning(Long id) {
        warningNoticeRepository.deleteById(id);
    }
}
