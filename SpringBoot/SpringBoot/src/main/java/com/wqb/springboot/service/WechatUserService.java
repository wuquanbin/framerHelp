package com.wqb.springboot.service;

import com.wqb.springboot.entity.AssistanceRecord;
import com.wqb.springboot.entity.Feedback;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 微信用户服务接口
 * <p>
 * 提供微信小程序用户的登录认证、资料管理、通知设置、头像上传、
 * 反馈提交、帮扶记录查询和用户清理等业务功能。
 * </p>
 */
public interface WechatUserService {

    /**
     * 微信登录
     * <p>
     * 通过 code 调用微信 API 获取 openid,查找或创建用户,生成 JWT 令牌。
     * </p>
     *
     * @param code 微信登录凭证
     * @return 登录响应信息,包含 token、userId、nickname、avatarUrl、openid 等
     */
    Map<String, Object> login(String code);

    /**
     * 获取当前用户的帮扶记录
     *
     * @param openid 用户 openid
     * @return 帮扶记录列表
     */
    List<AssistanceRecord> listAssistance(String openid);

    /**
     * 获取当前用户的反馈列表
     *
     * @param openid 用户 openid
     * @return 反馈信息列表,每项包含 id、feedbackType、content、status、reply、createdAt、updatedAt
     */
    List<Map<String, Object>> listMyFeedbacks(String openid);

    /**
     * 更新用户资料(昵称、头像)
     *
     * @param openid 用户 openid
     * @param body   待更新的资料字段
     * @return 更新后的用户信息
     */
    Map<String, Object> updateProfile(String openid, Map<String, String> body);

    /**
     * 更新通知设置(预警通知、农事提醒)
     *
     * @param openid 用户 openid
     * @param body   待更新的通知设置字段
     * @return 更新后的通知设置
     */
    Map<String, Object> updateNotifySettings(String openid, Map<String, Object> body);

    /**
     * 获取通知设置
     *
     * @param openid 用户 openid
     * @return 通知设置信息
     */
    Map<String, Object> getNotifySettings(String openid);

    /**
     * 上传用户头像
     * <p>
     * 保存头像文件到服务器,更新用户 avatarUrl 字段。
     * 文件大小和类型校验由 controller 完成。
     * </p>
     *
     * @param openid 用户 openid
     * @param file   头像文件
     * @return 包含 avatarUrl 的结果
     */
    Map<String, Object> uploadAvatar(String openid, MultipartFile file);

    /**
     * 提交用户反馈
     *
     * @param openid   用户 openid
     * @param feedback 反馈内容
     * @return 保存后的反馈实体
     */
    Feedback submitFeedback(String openid, Feedback feedback);

    /**
     * 清理所有昵称为 "WeChat User" 的用户及其关联数据
     *
     * @return 清理结果,包含 deletedUsers、deletedAssistanceRecords、message
     */
    Map<String, Object> cleanupWeChatUsers();

    /**
     * 获取当前用户收到的所有管理员回复（意见反馈回复 + 农情上报响应），按时间倒序
     *
     * @param openid 用户 openid
     * @return 统一格式的回复列表
     */
    List<Map<String, Object>> listMyResponses(String openid);

    /**
     * 获取当前用户未读的管理员回复数量（反馈回复 + 农情响应）
     *
     * @param openid 用户 openid
     * @return 包含 unread 字段的结果，unread 为未读条数
     */
    Map<String, Object> getUnreadResponseCount(String openid);

    /**
     * 将当前用户的全部管理员回复标记为已读（记录本次查看时间）
     *
     * @param openid 用户 openid
     */
    void markResponsesRead(String openid);

    /**
     * 更新用户自选的种植作物
     *
     * @param openid 用户 openid
     * @param crops  作物名称列表
     * @return 更新结果，包含 myCrops 字段
     */
    Map<String, Object> updateMyCrops(String openid, List<String> crops);

    /**
     * 获取用户自选的种植作物
     *
     * @param openid 用户 openid
     * @return 作物名称列表
     */
    List<String> getMyCrops(String openid);

    /**
     * 获取用户今日农事任务（根据作物播种日期自动计算）
     *
     * @param openid 用户 openid
     * @return 今日农事任务列表，每项包含 cropName、currentDay、taskName、description、priority
     */
    List<Map<String, Object>> getTodayFarmingTasks(String openid);

    /**
     * 农事任务打卡（闭环的「执行」环节）。
     * <p>
     * 幂等：重复打卡返回现状不报错；只能操作自己的记录。
     * </p>
     *
     * @param recordId 任务记录 ID（来自 getTodayFarmingTasks 的 recordId 字段）
     * @param openid   当前登录用户
     * @return {recordId, status, completedAt}
     */
    Map<String, Object> completeFarmingTask(Long recordId, String openid);

    /**
     * 农事档案（闭环的「记录」环节）：最近 N 天的任务执行记录 + 完成概况。
     * <p>
     * 返回结构：
     * <pre>
     * {
     *   "days": 30,                       // 查询窗口
     *   "summary": { total, completed, rate, activeDays, streak, bestDay },
     *   "calendar": [ { date, total, completed, rate } ],  // 连续日历（含无记录日），供热力图
     *   "groups":   [ { date, weekday, total, completed, items: [...] } ]  // 有记录的日子，倒序
     * }
     * </pre>
     * 完成率 rate 在没有任何记录时为 null —— 前端据此区分「0%」和「还没开始」。
     * </p>
     *
     * @param openid 用户 openid
     * @param days   回看天数（1~90，超范围自动收敛）
     * @return 档案数据
     */
    Map<String, Object> getFarmingRecords(String openid, int days);

    /**
     * 取消打卡（误触回退）。幂等，同样只能操作自己的记录。
     *
     * @param recordId 任务记录 ID
     * @param openid   当前登录用户
     * @return {recordId, status}
     */
    Map<String, Object> uncompleteFarmingTask(Long recordId, String openid);

    /**
     * 更新指定作物的播种日期
     *
     * @param openid       用户 openid
     * @param cropName     作物名称
     * @param plantingDate 播种日期（yyyy-MM-dd）
     * @return 更新结果
     */
    Map<String, Object> updateCropPlantingDate(String openid, String cropName, String plantingDate);

    /**
     * 获取用户所有作物的播种日期
     *
     * @param openid 用户 openid
     * @return 作物名称 -> 播种日期 的映射
     */
    Map<String, String> getCropPlantingDates(String openid);

    /**
     * 获取用户个人种植面积（user_crop 表）
     *
     * @param openid 用户 openid
     * @return 作物名称 -> 面积（亩） 的映射，只包含已填写面积的作物
     */
    Map<String, Double> getUserCropAreas(String openid);

    /**
     * 更新用户指定作物的个人种植面积（亩）
     * <p>
     * areaMu 为 null 时删除该作物的面积记录；不存在则新增，存在则更新。
     * </p>
     *
     * @param openid   用户 openid
     * @param cropName 作物名称
     * @param areaMu   面积（亩），null 表示清空
     * @return 更新结果，包含 cropName 与 areaMu
     */
    Map<String, Object> updateCropArea(String openid, String cropName, Double areaMu);
}
