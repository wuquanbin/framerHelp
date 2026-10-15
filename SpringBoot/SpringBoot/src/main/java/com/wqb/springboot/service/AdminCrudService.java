package com.wqb.springboot.service;

import com.wqb.springboot.dto.PageResult;
import com.wqb.springboot.entity.*;

import java.util.List;

/**
 * 管理后台增删改查服务接口
 * <p>
 * 提供管理端对村落、作物档案、灾害记录、农事建议、帮扶记录、
 * 用户、反馈、管理员、系统配置、操作日志、农情上报、帮扶响应、
 * 预警通知等实体的CRUD业务操作。
 * </p>
 */
public interface AdminCrudService {

    // ========== Village ==========

    List<Village> listVillages();

    Village createVillage(Village village);

    Village updateVillage(Long id, Village village);

    /**
     * 删除村镇
     *
     * @param id    村镇ID
     * @param force 是否级联删除关联数据（作物档案/农事建议/灾害记录/天气预报/气象观测/预警通知），
     *              并清空该村镇用户的归属村镇；false 时存在关联数据则拒绝删除
     */
    void deleteVillage(Long id, boolean force);

    // ========== CropProfile ==========

    List<CropProfile> listCrops();

    /**
     * 分页查询作物档案（后台列表页用，避免首屏读整表）。筛选下推到 SQL：在「当前页」里过滤的话，
     * 选了类型只能筛出本页那几条，计数和分页器会对不上。两个参数都可为空，为空表示不筛。
     *
     * @param villageCode 村镇编码，空表示全部（村镇管理员的 scope 会覆盖它）
     * @param cropType    作物类型，空表示全部
     */
    PageResult<CropProfile> listCropsPaged(int page, int size, String villageCode, String cropType);

    CropProfile createCrop(CropProfile crop);

    CropProfile updateCrop(Long id, CropProfile crop);

    void deleteCrop(Long id);

    // ========== DisasterRecord ==========

    List<DisasterRecord> listDisasters();

    /**
     * 分页查询灾害记录（按创建时间倒序）。
     *
     * @param page 页码，从 0 开始
     * @param size 每页大小（上限 100）
     */
    PageResult<DisasterRecord> listDisastersPaged(int page, int size);

    DisasterRecord createDisaster(DisasterRecord disaster);

    DisasterRecord updateDisaster(Long id, DisasterRecord disaster);

    void deleteDisaster(Long id);

    // ========== FarmAdvice ==========

    List<FarmAdvice> listFarmAdvices();

    /** 分页查询农事建议（后台列表页用，避免首屏读整表） */
    PageResult<FarmAdvice> listFarmAdvicesPaged(int page, int size);

    FarmAdvice createFarmAdvice(FarmAdvice advice);

    FarmAdvice updateFarmAdvice(Long id, FarmAdvice advice);

    void deleteFarmAdvice(Long id);

    // ========== AssistanceRecord ==========

    List<AssistanceRecord> listAssistanceRecords();

    /**
     * 分页查询帮扶台账（按帮扶时间倒序）。
     *
     * @param page 页码，从 0 开始
     * @param size 每页大小（上限 100）
     */
    PageResult<AssistanceRecord> listAssistanceRecordsPaged(int page, int size);

    AssistanceRecord createAssistanceRecord(AssistanceRecord record);

    AssistanceRecord updateAssistanceRecord(Long id, AssistanceRecord record);

    void deleteAssistanceRecord(Long id);

    // ========== User ==========

    List<User> listUsers();

    /**
     * 分页查询用户列表（按注册时间倒序）。
     *
     * @param page 页码，从 0 开始
     * @param size 每页大小（上限 100，防止恶意拉大拖垮库）
     */
    PageResult<User> listUsersPaged(int page, int size);

    /**
     * 分页查询用户列表，筛选条件下推到 SQL。
     * <p>
     * 早期实现是「拉全量 + 前端 filter」，农户上千后每次进页面都要把整表读出来，
     * 而且前端 filter 只作用于当前页 —— 翻到第 3 页再搜索会以为没数据。
     * 这里改成 Specification 拼条件，关键词/状态/村镇三个条件可任意组合。
     * </p>
     *
     * @param page        页码，从 0 开始
     * @param size        每页大小
     * @param keyword     昵称 / openid / 手机号 / ID 模糊匹配，空表示不过滤
     * @param status      状态（1 正常 / 0 禁用），null 表示不过滤
     * @param villageCode 村镇编码，空表示不过滤
     */
    PageResult<User> listUsersPaged(int page, int size, String keyword, Integer status, String villageCode);

    User getUser(Long id);

    User updateUserStatus(Long id, Integer status);

    /**
     * 删除农户账号（仅超级管理员，见 SecurityConfig 对 DELETE /api/admin/users/** 的限制）。
     * <p>
     * 连带清理范围：
     * <ul>
     *   <li>帮扶台账、意见反馈、我的作物 —— 有外键指向 app_user，不先删就删不掉用户；</li>
     *   <li>农事任务记录 —— 按 openid 关联（无外键），不清会残留到下次同 openid 注册；</li>
     *   <li>农情上报<b>刻意保留</b>：它属于村镇的公共台账（带图片证据），
     *       且已快照 nickname/avatarUrl，不因个人销户而消失。</li>
     * </ul>
     * </p>
     *
     * @param id 用户 ID
     * @throws com.wqb.springboot.exception.BusinessException 用户不存在
     */
    void deleteUser(Long id);

    // ========== Feedback ==========

    List<Feedback> listFeedbacks();

    /**
     * 分页查询反馈列表（按创建时间倒序）。
     *
     * @param page   页码，从 0 开始
     * @param size   每页大小（上限 100）
     * @param status 可选状态过滤；null 或空白表示全部
     */
    PageResult<Feedback> listFeedbacksPaged(int page, int size, String status);

    Feedback updateFeedback(Long id, Feedback feedback);

    // ========== AdminUser ==========

    List<AdminUser> listAdmins();

    AdminUser createAdmin(AdminUser admin);

    AdminUser updateAdmin(Long id, AdminUser admin);

    void deleteAdmin(Long id);

    // ========== 农事任务统计 ==========

    /**
     * 农事任务闭环统计（后台看板用）。
     * <p>
     * 返回结构：{ today: {total, completed, rate}, byCrop: [{cropName, total, completed}],
     * trend: [{date, total, completed}] }（trend 为最近 7 天）。
     * </p>
     */
    java.util.Map<String, Object> farmingTaskStats();

    /**
     * 帮扶成效看板聚合统计。
     * <p>
     * 返回结构：{ overview: { totalRecords, helpedFarmers, monthNew, warningTotal,
     * reportTotal, reportResolved, reportResolveRate, feedbackTotal, feedbackHandled,
     * feedbackHandleRate, taskTodayTotal, taskTodayDone, taskTodayRate },
     * byType: [{type, count}], byVillage: [{villageCode, villageName, count}],
     * byCrop: [{cropName, count}],
     * response: { reportTotal, respondedReports, coverageRate, avgHours, within24hCount, within24hRate },
     * trend: [{date, count}]（最近 30 天） }。比率/时长无数据时给 null 而非 0。
     * </p>
     */
    java.util.Map<String, Object> assistanceDashboard();

    /**
     * 把帮扶成效看板导出成带排版的 Excel（.xlsx）。
     * <p>
     * 内容按「总览 → 响应时效 → 四类分布 → 最近明细」分区块，含合并标题、统一列宽、
     * 边框、居中对齐与斑马纹，Excel/WPS 直接打开即可用于汇报，无需再手工调格式。
     * </p>
     *
     * @return xlsx 字节内容
     */
    byte[] exportAssistanceReport();

    // ========== OperationLog ==========

    org.springframework.data.domain.Page<OperationLog> listOperationLogsPaged(
            int page, int size, String operator, String module, String type, String startDate, String endDate);

    void clearOperationLogs();

    // ========== FarmReport ==========

    List<FarmReport> listFarmReports();

    /**
     * 分页查询农情上报（按创建时间倒序）。
     *
     * @param page   页码，从 0 开始
     * @param size   每页大小（上限 100）
     * @param status 可选状态过滤；null 或空白表示全部
     * @param type   可选上报类型过滤（pest/disaster/growth/help/other）；null 或空白表示全部
     */
    PageResult<FarmReport> listFarmReportsPaged(int page, int size, String status, String type);

    FarmReport getFarmReport(Long id);

    FarmReport updateFarmReport(Long id, FarmReport report);

    void deleteFarmReport(Long id);

    /**
     * 更新农情上报状态
     * <p>
     * 当状态变更为 resolved 时，自动创建对应的帮扶记录。
     * </p>
     *
     * @param id     上报记录ID
     * @param status 新状态
     * @return 更新后的上报记录
     */
    FarmReport updateFarmReportStatus(Long id, String status);

    // ========== FarmResponse ==========

    List<FarmResponse> listFarmResponses(Long reportId);

    FarmResponse addFarmReportResponse(Long reportId, FarmResponse response);

    void deleteFarmResponse(Long id);

    // ========== AppConfig ==========

    List<AppConfig> listConfigs();

    AppConfig updateConfig(Long id, AppConfig config);

    AppConfig createConfig(AppConfig config);

    void deleteConfig(Long id);

    // ========== AI 生成记录 ==========

    /**
     * 保存一条 AI 生成结果。操作人由后端从安全上下文取，不采信前端传值。
     */
    AiGenerationRecord saveAiGeneration(AiGenerationRecord record);

    /** 最近 50 条生成记录（全部管理员共享，便于追溯与复用） */
    List<AiGenerationRecord> listAiGenerations();

    void deleteAiGeneration(Long id);

    /** 标记为「已发布为农事建议」，记下对应的建议 id */
    AiGenerationRecord markAiGenerationPublished(Long id, Long adviceId);

    // ========== WarningNotice ==========

    List<WarningNotice> listWarnings();

    /**
     * 分页查询预警通知（按发布时间倒序）。
     *
     * @param page 页码，从 0 开始
     * @param size 每页大小（上限 100）
     */
    PageResult<WarningNotice> listWarningsPaged(int page, int size);

    WarningNotice createWarning(WarningNotice warning);

    WarningNotice updateWarning(Long id, WarningNotice warning);

    void deleteWarning(Long id);
}
