package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.PageResult;
import com.wqb.springboot.entity.*;
import com.wqb.springboot.repository.VillageRepository;
import com.wqb.springboot.repository.WeatherObservationRepository;
import com.wqb.springboot.scheduler.WeatherSyncScheduler;
import com.wqb.springboot.service.AdminCrudService;
import com.wqb.springboot.service.AppConfigService;
import com.wqb.springboot.service.WechatPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台增删改查控制器
 * <p>
 * 提供管理端对村落、作物档案、灾害记录、预警通知、用户、反馈、
 * 管理员、系统参数、操作日志、农情上报、帮扶响应等实体的CRUD操作接口。
 * 业务逻辑已下沉至 {@link AdminCrudService}，控制器仅负责参数校验和请求转发。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCrudController {

    private static final Logger log = LoggerFactory.getLogger(AdminCrudController.class);

    private final AdminCrudService adminCrudService;
    private final VillageRepository villageRepository;
    private final WeatherSyncScheduler weatherSyncScheduler;
    private final AppConfigService appConfigService;
    private final WeatherObservationRepository weatherObservationRepository;
    private final DataSource dataSource;

    public AdminCrudController(AdminCrudService adminCrudService, VillageRepository villageRepository,
                              WeatherSyncScheduler weatherSyncScheduler,
                              AppConfigService appConfigService,
                              WeatherObservationRepository weatherObservationRepository,
                              DataSource dataSource) {
        this.adminCrudService = adminCrudService;
        this.villageRepository = villageRepository;
        this.weatherSyncScheduler = weatherSyncScheduler;
        this.appConfigService = appConfigService;
        this.weatherObservationRepository = weatherObservationRepository;
        this.dataSource = dataSource;
    }

    // ========== Village CRUD ==========

    @GetMapping("/villages")
    public ApiResponse<List<Village>> listVillages() {
        return ApiResponse.ok(adminCrudService.listVillages());
    }

    @PostMapping("/villages")
    public ApiResponse<Village> createVillage(@RequestBody Village village) {
        Village created = adminCrudService.createVillage(village);
        // 新建村镇后立即异步同步一次天气数据，无需等待下一个定时周期(30分钟)
        weatherSyncScheduler.syncSingleVillageAsync(created.getId());
        return ApiResponse.ok(created);
    }

    @PutMapping("/villages/{id}")
    public ApiResponse<Village> updateVillage(@PathVariable Long id, @RequestBody Village village) {
        return ApiResponse.ok(adminCrudService.updateVillage(id, village));
    }

    @DeleteMapping("/villages/{id}")
    public ApiResponse<Void> deleteVillage(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        adminCrudService.deleteVillage(id, force);
        return ApiResponse.ok(null);
    }

    // ========== Crop Profile CRUD ==========

    /** 分页查询作物档案（响应结构同 disasters） */
    @GetMapping("/crops")
    public ApiResponse<PageResult<CropProfile>> listCrops(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String villageCode,
            @RequestParam(required = false) String cropType) {
        return ApiResponse.ok(adminCrudService.listCropsPaged(page, size, villageCode, cropType));
    }

    // 作物档案也收 Map，不能像 Village 那样直接收实体：
    // 前端表单里 village 是村镇 code 字符串，而 CropProfile.village 是 Village 关联对象，
    // 直接 @RequestBody CropProfile 会在 Jackson 反序列化阶段就 400（连 service 都进不去）。
    @PostMapping("/crops")
    public ApiResponse<CropProfile> createCrop(@RequestBody Map<String, Object> body) {
        CropProfile crop = new CropProfile();
        applyCropFields(crop, body, false);
        return ApiResponse.ok(adminCrudService.createCrop(crop));
    }

    @PutMapping("/crops/{id}")
    public ApiResponse<CropProfile> updateCrop(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        CropProfile crop = new CropProfile();
        applyCropFields(crop, body, true);
        return ApiResponse.ok(adminCrudService.updateCrop(id, crop));
    }

    /** 取值：这个 key 压根没提交就返回 null（要区分「提交的是空串」和「没提交」） */
    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 只在需要时赋值；值为 null 时兜成空串（目标列 NOT NULL） */
    private static void apply(boolean shouldSet, java.util.function.Consumer<String> setter, String value) {
        if (shouldSet) {
            setter.accept(value == null ? "" : value);
        }
    }

    /**
     * 从 Map 提取作物字段，同时把 village code 解析成完整的 Village 实体。
     * <p>
     * crop_profile 除 id / 审计字段外<b>全是 NOT NULL 列</b>：
     * 新建（partial=false）时没提交的字段要用类型默认值兜底，
     * 更新（partial=true）时只覆盖提交了的字段。
     * </p>
     */
    private void applyCropFields(CropProfile crop, Map<String, Object> body, boolean partial) {
        boolean all = !partial;
        apply(all || body.get("cropName") != null, crop::setCropName, text(body, "cropName"));
        apply(all || body.get("cropType") != null, crop::setCropType, text(body, "cropType"));
        apply(all || body.get("plantingArea") != null, crop::setPlantingArea, text(body, "plantingArea"));
        apply(all || body.get("growthStage") != null, crop::setGrowthStage, text(body, "growthStage"));
        apply(all || body.get("suitableWeather") != null, crop::setSuitableWeather, text(body, "suitableWeather"));
        apply(all || body.get("riskWeather") != null, crop::setRiskWeather, text(body, "riskWeather"));
        apply(all || body.get("farmingNotes") != null, crop::setFarmingNotes, text(body, "farmingNotes"));
        Object main = body.get("isMainCrop");
        if (all || main != null) {
            crop.setIsMainCrop(main != null ? Boolean.valueOf(main.toString()) : Boolean.FALSE);
        }
        Object risk = body.get("riskLevel");
        if (all || risk != null) {
            crop.setRiskLevel(risk != null ? Integer.valueOf(risk.toString()) : 1);
        }
        Object v = body.get("village");
        if (v != null) {
            if (v instanceof String code) {
                villageRepository.findByCode(code).ifPresent(crop::setVillage);
            } else if (v instanceof Map<?, ?> vm && vm.get("id") != null) {
                villageRepository.findById(Long.valueOf(vm.get("id").toString())).ifPresent(crop::setVillage);
            }
        }
    }

    @DeleteMapping("/crops/{id}")
    public ApiResponse<Void> deleteCrop(@PathVariable Long id) {
        adminCrudService.deleteCrop(id);
        return ApiResponse.ok(null);
    }

    // ========== Disaster Record CRUD ==========

    /**
     * 分页查询灾害记录。
     * 响应结构：{items, total, page, size}（前端 admin.ts 的 asRows() 会取 items，
     * 旧调用方无感知；分页器需要的 total 也在其中）
     */
    @GetMapping("/disasters")
    public ApiResponse<PageResult<DisasterRecord>> listDisasters(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCrudService.listDisastersPaged(page, size));
    }

    @PostMapping("/disasters")
    public ApiResponse<DisasterRecord> createDisaster(@RequestBody Map<String, Object> body) {
        DisasterRecord d = new DisasterRecord();
        applyDisasterFields(d, body, false);
        return ApiResponse.ok(adminCrudService.createDisaster(d));
    }

    @PutMapping("/disasters/{id}")
    public ApiResponse<DisasterRecord> updateDisaster(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        DisasterRecord d = new DisasterRecord();
        applyDisasterFields(d, body, true);
        return ApiResponse.ok(adminCrudService.updateDisaster(id, d));
    }

    /**
     * 从 Map 提取灾害字段，同时处理 village code → Village 对象。
     * disaster_record 除 id / 审计字段外全是 NOT NULL，规则同 {@link #applyCropFields}：
     * 新建用默认值兜底，更新只覆盖提交了的字段。
     */
    private void applyDisasterFields(DisasterRecord d, Map<String, Object> body, boolean partial) {
        boolean all = !partial;
        apply(all || body.get("disasterName") != null, d::setDisasterName, text(body, "disasterName"));
        apply(all || body.get("affectedArea") != null, d::setAffectedArea, text(body, "affectedArea"));
        apply(all || body.get("disasterType") != null, d::setDisasterType, text(body, "disasterType"));
        apply(all || body.get("triggerWeather") != null, d::setTriggerWeather, text(body, "triggerWeather"));
        apply(all || body.get("description") != null, d::setDescription, text(body, "description"));
        apply(all || body.get("remedy") != null, d::setRemedy, text(body, "remedy"));
        Object sev = body.get("severity");
        if (all || sev != null) {
            d.setSeverity(sev != null ? Integer.valueOf(sev.toString()) : 1);
        }
        Object v = body.get("village");
        if (v != null) {
            if (v instanceof String code) {
                villageRepository.findByCode(code).ifPresent(d::setVillage);
            } else if (v instanceof Map<?, ?> vm && vm.get("id") != null) {
                villageRepository.findById(Long.valueOf(vm.get("id").toString())).ifPresent(d::setVillage);
            }
        }
    }

    @DeleteMapping("/disasters/{id}")
    public ApiResponse<Void> deleteDisaster(@PathVariable Long id) {
        adminCrudService.deleteDisaster(id);
        return ApiResponse.ok(null);
    }

    // ========== Farm Advice CRUD ==========

    /** 分页查询农事建议（响应结构同 disasters） */
    @GetMapping("/farm-advices")
    public ApiResponse<PageResult<FarmAdvice>> listFarmAdvices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCrudService.listFarmAdvicesPaged(page, size));
    }

    @PostMapping("/farm-advices")
    public ApiResponse<FarmAdvice> createFarmAdvice(@RequestBody Map<String, Object> body) {
        FarmAdvice advice = new FarmAdvice();
        applyFarmAdviceFields(advice, body, false);
        return ApiResponse.ok(adminCrudService.createFarmAdvice(advice));
    }

    @PutMapping("/farm-advices/{id}")
    public ApiResponse<FarmAdvice> updateFarmAdvice(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        FarmAdvice advice = new FarmAdvice();
        applyFarmAdviceFields(advice, body, true);
        return ApiResponse.ok(adminCrudService.updateFarmAdvice(id, advice));
    }

    /**
     * 从 Map 提取农事建议字段，同时处理 village code → Village 对象。
     * farm_advice 除 id / 审计字段外全是 NOT NULL，规则同 {@link #applyCropFields}：
     * 新建用默认值兜底，更新只覆盖提交了的字段。
     */
    private void applyFarmAdviceFields(FarmAdvice advice, Map<String, Object> body, boolean partial) {
        boolean all = !partial;
        apply(all || body.get("title") != null, advice::setTitle, text(body, "title"));
        apply(all || body.get("summary") != null, advice::setSummary, text(body, "summary"));
        apply(all || body.get("recommendation") != null, advice::setRecommendation, text(body, "recommendation"));
        apply(all || body.get("priorityTag") != null, advice::setPriorityTag, text(body, "priorityTag"));
        apply(all || body.get("iconKey") != null, advice::setIconKey, text(body, "iconKey"));
        apply(all || body.get("adviceCode") != null, advice::setAdviceCode, text(body, "adviceCode"));
        Object priority = body.get("priority");
        if (all || priority != null) {
            advice.setPriority(priority != null ? Integer.valueOf(priority.toString()) : 5);
        }
        Object v = body.get("village");
        if (v != null) {
            if (v instanceof String code) {
                villageRepository.findByCode(code).ifPresent(advice::setVillage);
            } else if (v instanceof Map<?, ?> vm && vm.get("id") != null) {
                villageRepository.findById(Long.valueOf(vm.get("id").toString())).ifPresent(advice::setVillage);
            }
        }
    }

    @DeleteMapping("/farm-advices/{id}")
    public ApiResponse<Void> deleteFarmAdvice(@PathVariable Long id) {
        adminCrudService.deleteFarmAdvice(id);
        return ApiResponse.ok(null);
    }

    // ========== Assistance Record CRUD ==========

    /** 分页查询帮扶台账（响应结构同 disasters） */
    @GetMapping("/assistance-records")
    public ApiResponse<PageResult<AssistanceRecord>> listAssistanceRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCrudService.listAssistanceRecordsPaged(page, size));
    }

    @PostMapping("/assistance-records")
    public ApiResponse<AssistanceRecord> createAssistanceRecord(@RequestBody AssistanceRecord record) {
        return ApiResponse.ok(adminCrudService.createAssistanceRecord(record));
    }

    @PutMapping("/assistance-records/{id}")
    public ApiResponse<AssistanceRecord> updateAssistanceRecord(@PathVariable Long id, @RequestBody AssistanceRecord record) {
        return ApiResponse.ok(adminCrudService.updateAssistanceRecord(id, record));
    }

    @DeleteMapping("/assistance-records/{id}")
    public ApiResponse<Void> deleteAssistanceRecord(@PathVariable Long id) {
        adminCrudService.deleteAssistanceRecord(id);
        return ApiResponse.ok(null);
    }

    // ========== User Management ==========

    /**
     * 分页查询农户列表（响应结构同 disasters）。
     * 关键词/状态/村镇三个筛选条件下推到 SQL，前端不再拉全量后再 filter。
     */
    @GetMapping("/users")
    public ApiResponse<PageResult<User>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String villageCode) {
        return ApiResponse.ok(adminCrudService.listUsersPaged(page, size, keyword, status, villageCode));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<User> getUser(@PathVariable Long id) {
        return ApiResponse.ok(adminCrudService.getUser(id));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<User> updateUserStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) {
            return ApiResponse.fail("状态参数不能为空");
        }
        return ApiResponse.ok(adminCrudService.updateUserStatus(id, status));
    }

    /**
     * 删除农户账号。
     * <p>
     * 权限：SecurityConfig 里 {@code DELETE /api/admin/users/**} 只对超级管理员放行
     * （比通用的「DELETE /api/admin/** → 超管+专家」更严格，删农户是有代价的操作）。
     * 连带清掉帮扶台账、反馈、我的作物与农事任务记录；农情上报按公共台账保留。
     * </p>
     */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        adminCrudService.deleteUser(id);
        return ApiResponse.ok(null);
    }

    // ========== Feedback Management ==========

    /** 分页查询反馈列表，支持按状态过滤（响应结构同 disasters） */
    @GetMapping("/feedbacks")
    public ApiResponse<PageResult<Feedback>> listFeedbacks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(adminCrudService.listFeedbacksPaged(page, size, status));
    }

    @PutMapping("/feedbacks/{id}")
    public ApiResponse<Feedback> updateFeedback(@PathVariable Long id, @RequestBody Feedback feedback) {
        return ApiResponse.ok(adminCrudService.updateFeedback(id, feedback));
    }

    // ========== Admin User CRUD ==========

    @GetMapping("/admins")
    public ApiResponse<List<AdminUser>> listAdmins() {
        return ApiResponse.ok(adminCrudService.listAdmins());
    }

    @PostMapping("/admins")
    public ApiResponse<AdminUser> createAdmin(@RequestBody AdminUser admin) {
        return ApiResponse.ok(adminCrudService.createAdmin(admin));
    }

    @PutMapping("/admins/{id}")
    public ApiResponse<AdminUser> updateAdmin(@PathVariable Long id, @RequestBody AdminUser admin) {
        return ApiResponse.ok(adminCrudService.updateAdmin(id, admin));
    }

    @DeleteMapping("/admins/{id}")
    public ApiResponse<Void> deleteAdmin(@PathVariable Long id) {
        adminCrudService.deleteAdmin(id);
        return ApiResponse.ok(null);
    }

    // ========== Operation Logs ==========

    /**
     * 分页查询操作日志（支持操作人/模块/类型/日期范围筛选）
     * 返回 Spring Page 结构（content/totalElements），与前端日志页对齐
     */
    @GetMapping("/operation-logs")
    public ApiResponse<org.springframework.data.domain.Page<OperationLog>> listOperationLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(adminCrudService.listOperationLogsPaged(page, size, operator, module, type, startDate, endDate));
    }

    @DeleteMapping("/operation-logs")
    public ApiResponse<Void> clearOperationLogs() {
        adminCrudService.clearOperationLogs();
        return ApiResponse.ok(null);
    }

    // ========== 农事任务统计 ==========

    /**
     * 农事任务闭环统计（后台看板用）。
     * 返回：{ today: {total, completed, rate}, byCrop: [...], trend: [...] }
     */
    @GetMapping("/farming-task-stats")
    public ApiResponse<Map<String, Object>> farmingTaskStats() {
        return ApiResponse.ok(adminCrudService.farmingTaskStats());
    }

    /**
     * 帮扶成效看板聚合统计。
     * 返回：{ overview: {...}, byType: [...], byVillage: [...], trend: [...]（近 30 天） }
     */
    @GetMapping("/assistance-dashboard")
    public ApiResponse<Map<String, Object>> assistanceDashboard() {
        return ApiResponse.ok(adminCrudService.assistanceDashboard());
    }

    /**
     * 导出帮扶成效看板为带排版的 Excel（.xlsx）。
     * 直接返回字节流（不走 ApiResponse 包装），前端用 blob 下载。
     * 文件名带日期，避免多次导出互相覆盖。
     */
    @GetMapping("/assistance-dashboard/export")
    public ResponseEntity<byte[]> exportAssistanceDashboard() {
        byte[] xlsx = adminCrudService.exportAssistanceReport();
        String filename = "assistance-report-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + ".xlsx";
        // RFC 5987：中文文件名必须走 filename*，否则各浏览器编码不一致
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (RuntimeException e) {
            encoded = filename;
        }
        return ResponseEntity.ok()
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition",
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded)
                .body(xlsx);
    }

    // ========== Farm Report CRUD ==========

    /** 分页查询农情上报，支持按状态/类型过滤（响应结构同 disasters） */
    @GetMapping("/farm-reports")
    public ApiResponse<PageResult<FarmReport>> listFarmReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(adminCrudService.listFarmReportsPaged(page, size, status, type));
    }

    @GetMapping("/farm-reports/{id}")
    public ApiResponse<FarmReport> getFarmReport(@PathVariable Long id) {
        return ApiResponse.ok(adminCrudService.getFarmReport(id));
    }

    @PutMapping("/farm-reports/{id}")
    public ApiResponse<FarmReport> updateFarmReport(@PathVariable Long id, @RequestBody FarmReport report) {
        return ApiResponse.ok(adminCrudService.updateFarmReport(id, report));
    }

    @DeleteMapping("/farm-reports/{id}")
    public ApiResponse<Void> deleteFarmReport(@PathVariable Long id) {
        adminCrudService.deleteFarmReport(id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/farm-reports/{id}/status")
    public ApiResponse<FarmReport> updateFarmReportStatus(
            @PathVariable Long id,
            @RequestParam(required = false) String status) {
        // status 只通过 query 参数接收（前端 axios 以 params 传递）。
        // 这里不能加 @RequestBody：axios 对空 body 的 PUT 会默认带 application/x-www-form-urlencoded 头，
        // 一旦声明 @RequestBody，Spring 会因无法把表单解析成对象而返回 415。
        if (status == null || status.isBlank()) {
            return ApiResponse.fail("状态参数不能为空");
        }
        return ApiResponse.ok(adminCrudService.updateFarmReportStatus(id, status));
    }

    // ========== Farm Response CRUD ==========

    @GetMapping("/farm-responses")
    public ApiResponse<List<FarmResponse>> listFarmResponses(@RequestParam(required = false) Long reportId) {
        return ApiResponse.ok(adminCrudService.listFarmResponses(reportId));
    }

    @PostMapping("/farm-reports/{id}/responses")
    public ApiResponse<FarmResponse> addFarmReportResponse(@PathVariable Long id, @RequestBody FarmResponse response) {
        return ApiResponse.ok(adminCrudService.addFarmReportResponse(id, response));
    }

    @DeleteMapping("/farm-responses/{id}")
    public ApiResponse<Void> deleteFarmResponse(@PathVariable Long id) {
        adminCrudService.deleteFarmResponse(id);
        return ApiResponse.ok(null);
    }

    // ========== App Config (System Settings) ==========

    @GetMapping("/config")
    public ApiResponse<List<AppConfig>> listConfigs() {
        return ApiResponse.ok(adminCrudService.listConfigs());
    }

    @PostMapping("/config")
    public ApiResponse<AppConfig> createConfig(@RequestBody AppConfig config) {
        return ApiResponse.ok(adminCrudService.createConfig(config));
    }

    @PutMapping("/config/{id}")
    public ApiResponse<AppConfig> updateConfig(@PathVariable Long id, @RequestBody AppConfig config) {
        return ApiResponse.ok(adminCrudService.updateConfig(id, config));
    }

    @DeleteMapping("/config/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) {
        adminCrudService.deleteConfig(id);
        return ApiResponse.ok(null);
    }

    // ========== AI 生成记录 ==========

    /**
     * 保存 AI 生成结果
     * <p>落库而不是只存浏览器 localStorage：全端共享、可追溯。操作人由后端从 token 取，前端传了也不认。</p>
     */
    @PostMapping("/ai-generations")
    public ApiResponse<AiGenerationRecord> saveAiGeneration(@RequestBody AiGenerationRecord record) {
        return ApiResponse.ok(adminCrudService.saveAiGeneration(record));
    }

    @GetMapping("/ai-generations")
    public ApiResponse<List<AiGenerationRecord>> listAiGenerations() {
        return ApiResponse.ok(adminCrudService.listAiGenerations());
    }

    @DeleteMapping("/ai-generations/{id}")
    public ApiResponse<Void> deleteAiGeneration(@PathVariable Long id) {
        adminCrudService.deleteAiGeneration(id);
        return ApiResponse.ok(null);
    }

    /** 发布为农事建议后回写标记，页面上就能看出哪条已经发过 */
    @PutMapping("/ai-generations/{id}/publish")
    public ApiResponse<AiGenerationRecord> markAiGenerationPublished(
            @PathVariable Long id,
            @RequestParam(required = false) Long adviceId) {
        return ApiResponse.ok(adminCrudService.markAiGenerationPublished(id, adviceId));
    }

    // ========== 系统健康状态 ==========

    /**
     * 系统运行状况（给「系统设置」页的状态卡用）
     * <p>每一项都真查一次：数据库真连一次、JVM 真读一次、天气数据真取最新一条观测时间。</p>
     */
    @GetMapping("/system/health")
    public ApiResponse<Map<String, Object>> systemHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "UP");
        data.put("database", checkDatabase() ? "UP" : "DOWN");
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        Runtime runtime = Runtime.getRuntime();
        data.put("jvmUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        data.put("jvmMaxMb", runtime.maxMemory() / 1024 / 1024);
        data.put("weatherLastSyncAt", latestObservationTime());
        data.put("version", appConfigService.get(AppConfigService.K_SYSTEM_VERSION, "v1.0.0"));
        return ApiResponse.ok(data);
    }

    /** 真连一次数据库，只判断连通性，不做业务查询 */
    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return true;
        } catch (Exception e) {
            log.error("[健康检查] 数据库连接失败：{}", e.getMessage());
            return false;
        }
    }

    /** 最新一条实况观测时间，用来判断天气同步是不是真的在跑 */
    private String latestObservationTime() {
        try {
            return weatherObservationRepository
                    .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "observationTime")))
                    .getContent()
                    .stream()
                    .findFirst()
                    .map(WeatherObservation::getObservationTime)
                    .map(t -> t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[健康检查] 取最新观测时间失败：{}", e.getMessage());
            return null;
        }
    }

    // ========== Warning Notice CRUD ==========

    /** 分页查询预警通知（响应结构同 disasters） */
    @GetMapping("/warnings")
    public ApiResponse<PageResult<WarningNotice>> listWarnings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCrudService.listWarningsPaged(page, size));
    }

    @PostMapping("/warnings")
    public ApiResponse<WarningNotice> createWarning(@RequestBody WarningNotice warning) {
        return ApiResponse.ok(adminCrudService.createWarning(warning));
    }

    @PutMapping("/warnings/{id}")
    public ApiResponse<WarningNotice> updateWarning(@PathVariable Long id, @RequestBody WarningNotice warning) {
        return ApiResponse.ok(adminCrudService.updateWarning(id, warning));
    }

    @DeleteMapping("/warnings/{id}")
    public ApiResponse<Void> deleteWarning(@PathVariable Long id) {
        adminCrudService.deleteWarning(id);
        return ApiResponse.ok(null);
    }
}
