package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.AuthDto;
import com.wqb.springboot.entity.AssistanceRecord;
import com.wqb.springboot.entity.Feedback;
import com.wqb.springboot.service.WechatUserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 微信用户控制器
 * <p>
 * 提供微信小程序用户的登录认证、资料管理、通知设置、头像上传、
 * 反馈提交、帮扶记录查询和用户清理等接口。
 * 业务逻辑已下沉至 {@link WechatUserService},控制器仅负责参数校验、
 * 安全上下文提取和请求转发。
 * </p>
 */
@RestController
@RequestMapping("/api/wechat")
public class WechatUserController {

    private final WechatUserService wechatUserService;

    public WechatUserController(WechatUserService wechatUserService) {
        this.wechatUserService = wechatUserService;
    }

    /**
     * 微信登录
     *
     * @param request 登录请求,包含 code
     * @return 登录响应,包含 token、userId、nickname、avatarUrl、openid 等
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody AuthDto.WechatLoginRequest request) {
        return ApiResponse.ok(wechatUserService.login(request.getCode()));
    }

    /**
     * 获取当前用户的帮扶记录
     *
     * @return 帮扶记录列表
     */
    @GetMapping("/assistance")
    public ApiResponse<List<AssistanceRecord>> listAssistance() {
        String openid = getCurrentOpenid();
        return ApiResponse.ok(wechatUserService.listAssistance(openid));
    }

    /**
     * 获取当前用户的反馈列表
     *
     * @return 反馈信息列表
     */
    @GetMapping("/feedbacks")
    public ApiResponse<List<Map<String, Object>>> listMyFeedbacks() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.listMyFeedbacks(openid));
    }

    /**
     * 获取当前用户收到的所有管理员回复（意见反馈回复 + 农情上报响应），按时间倒序
     *
     * @return 统一格式的回复列表
     */
    @GetMapping("/my-responses")
    public ApiResponse<List<Map<String, Object>>> listMyResponses() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.listMyResponses(openid));
    }

    /**
     * 获取当前用户未读的管理员回复数量（用于小程序"我的回复"入口红点角标）
     *
     * @return 包含 unread 未读条数的结果
     */
    @GetMapping("/unread-responses-count")
    public ApiResponse<Map<String, Object>> unreadResponsesCount() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getUnreadResponseCount(openid));
    }

    /**
     * 标记当前用户的管理员回复全部已读（进入"我的回复"页时调用，清除红点）
     *
     * @return 固定返回 unread=0
     */
    @PostMapping("/read-responses")
    public ApiResponse<Map<String, Object>> markResponsesRead() {
        String openid = requireAuthenticatedOpenid();
        wechatUserService.markResponsesRead(openid);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("unread", 0);
        return ApiResponse.ok(result);
    }

    /**
     * 更新用户资料(昵称、头像)
     *
     * @param body 待更新的资料字段
     * @return 更新后的用户信息
     */
    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody Map<String, String> body) {
        String openid = getCurrentOpenid();
        return ApiResponse.ok(wechatUserService.updateProfile(openid, body));
    }

    /**
     * 更新通知设置(预警通知、农事提醒)
     *
     * @param body 待更新的通知设置字段
     * @return 更新后的通知设置
     */
    @PutMapping("/notify-settings")
    public ApiResponse<Map<String, Object>> updateNotifySettings(@RequestBody Map<String, Object> body) {
        String openid = getCurrentOpenid();
        return ApiResponse.ok(wechatUserService.updateNotifySettings(openid, body));
    }

    /**
     * 获取通知设置
     *
     * @return 通知设置信息
     */
    @GetMapping("/notify-settings")
    public ApiResponse<Map<String, Object>> getNotifySettings() {
        String openid = getCurrentOpenid();
        return ApiResponse.ok(wechatUserService.getNotifySettings(openid));
    }

    /**
     * 上传用户头像
     * <p>
     * 文件校验和保存逻辑均由 service 处理。
     * </p>
     *
     * @param file 头像文件
     * @return 包含 avatarUrl 的结果
     */
    @PostMapping("/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String openid = getCurrentOpenid();
        return ApiResponse.ok(wechatUserService.uploadAvatar(openid, file));
    }

    /**
     * 提交用户反馈
     *
     * @param feedback 反馈内容
     * @return 保存后的反馈实体
     */
    @PostMapping("/feedback")
    public ApiResponse<Feedback> submitFeedback(@RequestBody Feedback feedback) {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.submitFeedback(openid, feedback));
    }

    /**
     * 清理所有昵称为 "WeChat User" 的用户及其关联数据
     *
     * @return 清理结果
     */
    @DeleteMapping("/cleanup-wechat-users")
    public ApiResponse<Map<String, Object>> cleanupWeChatUsers() {
        return ApiResponse.ok(wechatUserService.cleanupWeChatUsers());
    }

    /**
     * 获取用户自选的种植作物
     *
     * @return 作物名称列表
     */
    @GetMapping("/my-crops")
    public ApiResponse<List<String>> getMyCrops() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getMyCrops(openid));
    }

    /**
     * 更新用户自选的种植作物
     *
     * @param body 包含 crops 字段（作物名称列表）
     * @return 更新结果
     */
    @PutMapping("/my-crops")
    public ApiResponse<Map<String, Object>> updateMyCrops(@RequestBody Map<String, Object> body) {
        String openid = requireAuthenticatedOpenid();
        @SuppressWarnings("unchecked")
        List<String> crops = (List<String>) body.get("crops");
        return ApiResponse.ok(wechatUserService.updateMyCrops(openid, crops));
    }

    /**
     * 获取今日农事任务（根据作物播种日期自动计算）
     * <p>
     * 返回项含 recordId / status / completedAt —— 任务在查询时懒物化到
     * farming_task_record 表，农户打卡后列表能显示「已完成」。
     * </p>
     *
     * @return 今日农事任务列表
     */
    @GetMapping("/today-tasks")
    public ApiResponse<List<Map<String, Object>>> getTodayTasks() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getTodayFarmingTasks(openid));
    }

    /**
     * 农事任务打卡（闭环的「执行」环节）。
     * 幂等：重复打卡返回现状；只能操作自己的记录。
     *
     * @param id 任务记录 ID
     * @return 更新后的任务状态
     */
    @PutMapping("/farming-tasks/{id}/complete")
    public ApiResponse<Map<String, Object>> completeFarmingTask(@PathVariable Long id) {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.completeFarmingTask(id, openid));
    }

    /**
     * 取消农事任务打卡（误触回退）。幂等。
     *
     * @param id 任务记录 ID
     * @return 更新后的任务状态
     */
    @PutMapping("/farming-tasks/{id}/uncomplete")
    public ApiResponse<Map<String, Object>> uncompleteFarmingTask(@PathVariable Long id) {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.uncompleteFarmingTask(id, openid));
    }

    /**
     * 农事档案（闭环的「记录」环节）：最近 N 天的任务执行记录 + 完成概况。
     *
     * @param days 回看天数，默认 30，最大 90
     * @return { days, summary, calendar, groups }
     */
    @GetMapping("/farming-records")
    public ApiResponse<Map<String, Object>> getFarmingRecords(@RequestParam(defaultValue = "30") int days) {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getFarmingRecords(openid, days));
    }

    /**
     * 获取用户所有作物的播种日期
     *
     * @return 作物名称 -> 播种日期 的映射
     */
    @GetMapping("/crop-planting-dates")
    public ApiResponse<Map<String, String>> getCropPlantingDates() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getCropPlantingDates(openid));
    }

    /**
     * 更新指定作物的播种日期
     *
     * @param body 包含 cropName 和 plantingDate 字段
     * @return 更新结果
     */
    @PutMapping("/crop-planting-date")
    public ApiResponse<Map<String, Object>> updateCropPlantingDate(@RequestBody Map<String, Object> body) {
        String openid = requireAuthenticatedOpenid();
        String cropName = (String) body.get("cropName");
        String plantingDate = (String) body.get("plantingDate");
        return ApiResponse.ok(wechatUserService.updateCropPlantingDate(openid, cropName, plantingDate));
    }

    /**
     * 获取用户个人种植面积（user_crop 表，仅本人数据，不含村镇总体档案）
     *
     * @return 作物名称 -> 面积（亩） 的映射
     */
    @GetMapping("/crop-areas")
    public ApiResponse<Map<String, Double>> getCropAreas() {
        String openid = requireAuthenticatedOpenid();
        return ApiResponse.ok(wechatUserService.getUserCropAreas(openid));
    }

    /**
     * 更新用户指定作物的个人种植面积
     *
     * @param body 包含 cropName 与 areaMu 字段；areaMu 为 null/空 表示清空该作物的面积
     * @return 更新结果
     */
    @PutMapping("/crop-area")
    public ApiResponse<Map<String, Object>> updateCropArea(@RequestBody Map<String, Object> body) {
        String openid = requireAuthenticatedOpenid();
        String cropName = (String) body.get("cropName");
        Object areaObj = body.get("areaMu");
        Double areaMu = null;
        if (areaObj instanceof Number num) {
            areaMu = num.doubleValue();
        } else if (areaObj instanceof String str && !str.isBlank()) {
            try {
                areaMu = Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                throw new com.wqb.springboot.exception.BusinessException("种植面积格式不正确");
            }
        }
        return ApiResponse.ok(wechatUserService.updateCropArea(openid, cropName, areaMu));
    }

    // ========== 安全上下文辅助方法 ==========

    /**
     * 从安全上下文获取当前登录用户的 openid
     * <p>公开接口未登录时 Authentication 是匿名用户，必须校验，否则会把 "anonymousUser" 当 openid 查库导致 500。</p>
     *
     * @return 当前用户 openid
     * @throws UnauthorizedException 未登录/token 过期时抛出(全局处理为 401)
     */
    private String getCurrentOpenid() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new com.wqb.springboot.exception.UnauthorizedException();
        }
        return auth.getName();
    }

    /**
     * 从安全上下文获取当前登录用户的 openid,要求必须已登录
     *
     * @return 当前用户 openid
     * @throws UnauthorizedException 未登录时抛出(全局处理为 401)
     */
    private String requireAuthenticatedOpenid() {
        return getCurrentOpenid();
    }
}
