package com.wqb.springboot.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wqb.springboot.entity.*;
import com.wqb.springboot.exception.BusinessException;
import com.wqb.springboot.repository.*;
import com.wqb.springboot.security.JwtUtil;
import com.wqb.springboot.service.WechatUserService;
import com.wqb.springboot.util.CropCalendarKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 微信用户服务实现类
 * <p>
 * 提供微信小程序用户的登录认证、资料管理、通知设置、头像上传、
 * 反馈提交、帮扶记录查询和用户清理等业务功能。
 * </p>
 */
@Service
public class WechatUserServiceImpl implements WechatUserService {

    private static final Logger log = LoggerFactory.getLogger(WechatUserServiceImpl.class);

    @Value("${wechat.appid:}")
    private String appId;

    @Value("${wechat.secret:}")
    private String appSecret;

    @Value("${wechat.dev-mode:false}")
    private boolean devMode;

    @Value("${app.upload.path:D:/weather-uploads}")
    private String uploadBaseDir;

    private final UserRepository userRepository;
    private final AssistanceRecordRepository assistanceRecordRepository;
    private final FeedbackRepository feedbackRepository;
    private final FarmReportRepository farmReportRepository;
    private final FarmResponseRepository farmResponseRepository;
    private final VillageRepository villageRepository;
    private final UserCropRepository userCropRepository;
    private final FarmingTaskRecordRepository farmingTaskRecordRepository;
    private final JwtUtil jwtUtil;
    private final RestTemplate restTemplate;
    private final SecureRandom random = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 星期中文缩写，下标与 DayOfWeek.getValue()-1 对齐（周一在前） */
    private static final String[] WEEKDAY_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    public WechatUserServiceImpl(UserRepository userRepository,
                                 AssistanceRecordRepository assistanceRecordRepository,
                                 FeedbackRepository feedbackRepository,
                                 FarmReportRepository farmReportRepository,
                                 FarmResponseRepository farmResponseRepository,
                                 VillageRepository villageRepository,
                                 UserCropRepository userCropRepository,
                                 FarmingTaskRecordRepository farmingTaskRecordRepository,
                                 JwtUtil jwtUtil,
                                 RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.assistanceRecordRepository = assistanceRecordRepository;
        this.feedbackRepository = feedbackRepository;
        this.farmReportRepository = farmReportRepository;
        this.farmResponseRepository = farmResponseRepository;
        this.villageRepository = villageRepository;
        this.userCropRepository = userCropRepository;
        this.farmingTaskRecordRepository = farmingTaskRecordRepository;
        this.jwtUtil = jwtUtil;
        this.restTemplate = restTemplate;
    }

    @Override
    public Map<String, Object> login(String code) {
        final String openid = resolveOpenid(code);

        User user = userRepository.findByOpenid(openid).orElseGet(() -> {
            User newUser = new User();
            newUser.setOpenid(openid);
            newUser.setNickname(generateNickname());
            newUser.setStatus(1);
            newUser.setNotifyWarning(true);
            newUser.setNotifyFarming(true);
            return userRepository.save(newUser);
        });

        user.setLastLoginTime(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getOpenid(), "USER");

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("nickname", user.getNickname());
        result.put("avatarUrl", user.getAvatarUrl());
        result.put("openid", openid);
        result.put("phone", user.getPhone());
        // 通过 villageCode 查询村庄名称
        String villageName = null;
        if (user.getVillageCode() != null && !user.getVillageCode().isEmpty()) {
            villageName = villageRepository.findByCode(user.getVillageCode())
                    .map(Village::getName)
                    .orElse(null);
        }
        result.put("villageName", villageName);
        result.put("villageCode", user.getVillageCode());
        result.put("myCrops", parseMyCrops(user.getMyCrops()));
        result.put("notifyWarning", user.getNotifyWarning() != null ? user.getNotifyWarning() : true);
        result.put("notifyFarming", user.getNotifyFarming() != null ? user.getNotifyFarming() : true);
        return result;
    }

    @Override
    public List<AssistanceRecord> listAssistance(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return assistanceRecordRepository.findByUserId(user.getId());
    }

    @Override
    public List<Map<String, Object>> listMyFeedbacks(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        List<Feedback> feedbacks = feedbackRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Feedback fb : feedbacks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", fb.getId());
            item.put("feedbackType", fb.getFeedbackType());
            item.put("content", fb.getContent());
            item.put("status", fb.getStatus());
            item.put("reply", fb.getReply());
            item.put("createdAt", fb.getCreatedAt());
            item.put("updatedAt", fb.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> updateProfile(String openid, Map<String, String> body) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (body.containsKey("nickname") && body.get("nickname") != null) {
            user.setNickname(body.get("nickname"));
        }
        if (body.containsKey("avatarUrl") && body.get("avatarUrl") != null) {
            user.setAvatarUrl(body.get("avatarUrl"));
        }
        if (body.containsKey("phone") && body.get("phone") != null) {
            user.setPhone(body.get("phone"));
        }
        if (body.containsKey("villageCode") && body.get("villageCode") != null) {
            user.setVillageCode(body.get("villageCode"));
        }
        userRepository.save(user);

        // 通过 villageCode 查询村庄名称
        String villageName = null;
        if (user.getVillageCode() != null && !user.getVillageCode().isEmpty()) {
            villageName = villageRepository.findByCode(user.getVillageCode())
                    .map(Village::getName)
                    .orElse(null);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("nickname", user.getNickname());
        result.put("avatarUrl", user.getAvatarUrl());
        result.put("phone", user.getPhone());
        result.put("villageCode", user.getVillageCode());
        result.put("villageName", villageName);
        result.put("notifyWarning", user.getNotifyWarning());
        result.put("notifyFarming", user.getNotifyFarming());
        return result;
    }

    @Override
    public Map<String, Object> updateNotifySettings(String openid, Map<String, Object> body) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (body.containsKey("notifyWarning")) {
            Object val = body.get("notifyWarning");
            if (val instanceof Boolean) {
                user.setNotifyWarning((Boolean) val);
            }
        }
        if (body.containsKey("notifyFarming")) {
            Object val = body.get("notifyFarming");
            if (val instanceof Boolean) {
                user.setNotifyFarming((Boolean) val);
            }
        }
        userRepository.save(user);

        Map<String, Object> result = new HashMap<>();
        result.put("notifyWarning", user.getNotifyWarning());
        result.put("notifyFarming", user.getNotifyFarming());
        return result;
    }

    @Override
    public Map<String, Object> getNotifySettings(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Map<String, Object> result = new HashMap<>();
        result.put("notifyWarning", user.getNotifyWarning() != null ? user.getNotifyWarning() : true);
        result.put("notifyFarming", user.getNotifyFarming() != null ? user.getNotifyFarming() : true);
        return result;
    }

    @Override
    public Map<String, Object> uploadAvatar(String openid, MultipartFile file) {
        // 文件大小校验
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过5MB");
        }

        // 文件类型校验
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_IMAGE_TYPES.contains(ext)) {
            throw new BusinessException("仅支持 jpg、png、gif、webp、bmp 格式的图片");
        }

        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        try {
            // 确保上传目录存在
            String uploadDir = uploadBaseDir + "/avatars/";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 删除该用户所有旧头像文件(兼容两种命名格式:userId.ext 和 userId_*.ext)
            File dirFile = new File(uploadDir);
            File[] oldFiles = dirFile.listFiles((d, name) ->
                    name.startsWith(user.getId() + ".") || name.startsWith(user.getId() + "_")
            );
            if (oldFiles != null) {
                for (File oldFile : oldFiles) {
                    // 删旧头像失败不能中断上传（残留只占点磁盘），但要留日志，否则磁盘占用/权限问题会永远静默。
                    try {
                        Files.deleteIfExists(oldFile.toPath());
                    } catch (Exception e) {
                        log.warn("[头像] 清理旧头像文件失败，文件: {}, 原因: {}",
                                oldFile.getAbsolutePath(), e.getMessage());
                    }
                }
            }

            // 使用固定文件名:userId.ext(覆盖式更新,避免堆积旧文件)
            String fileName = user.getId() + ext;
            File destFile = new File(uploadDir + fileName);

            // 保存文件
            file.transferTo(destFile);

            // 更新用户头像URL,加时间戳参数防止缓存
            long ts = System.currentTimeMillis();
            String avatarUrl = "/uploads/avatars/" + fileName + "?t=" + ts;
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("avatarUrl", avatarUrl);
            return result;
        } catch (IOException e) {
            log.error("头像上传失败", e);
            throw new RuntimeException("头像上传失败,请重试");
        }
    }

    @Override
    public Feedback submitFeedback(String openid, Feedback feedback) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        feedback.setUser(user);
        feedback.setStatus("pending");
        if (feedback.getFeedbackType() == null || feedback.getFeedbackType().isBlank()) {
            feedback.setFeedbackType("suggestion");
        }
        return feedbackRepository.save(feedback);
    }

    @Override
    @Transactional
    public Map<String, Object> cleanupWeChatUsers() {
        List<User> wechatUsers = userRepository.findByNickname("WeChat User");
        int userCount = wechatUsers.size();
        int arCount = 0;

        for (User user : wechatUsers) {
            // 先删除关联的帮扶记录
            List<AssistanceRecord> records = assistanceRecordRepository.findByUserId(user.getId());
            arCount += records.size();
            assistanceRecordRepository.deleteByUser(user);

            // 删除关联的反馈
            feedbackRepository.deleteByUser(user);
        }

        // 删除用户
        userRepository.deleteAll(wechatUsers);

        Map<String, Object> result = new HashMap<>();
        result.put("deletedUsers", userCount);
        result.put("deletedAssistanceRecords", arCount);
        result.put("message", "已清理 " + userCount + " 个 WeChat User 用户");
        return result;
    }

    // ========== 内部辅助方法 ==========

    /**
     * 调用微信 code2session 接口获取 openid
     * <p>
     * 开发模式下若调用失败,使用虚拟 openid dev_{code} 作为降级。
     * </p>
     *
     * @param code 微信登录凭证
     * @return openid
     */
    @SuppressWarnings("unchecked")
    private String resolveOpenid(String code) {
        try {
            String url = String.format(
                    "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                    appId, appSecret, code
            );
            // 微信返回 Content-Type 为 text/plain，RestTemplate 默认 JSON 转换器不处理，
            // 改用 String 接收后手动用 ObjectMapper 解析
            String responseBody = restTemplate.getForObject(url, String.class);
            Map<String, Object> result = OBJECT_MAPPER.readValue(responseBody, Map.class);
            String openid = (String) result.get("openid");
            if (openid != null && !openid.isEmpty()) {
                return openid;
            }
            String errMsg = result != null ? String.valueOf(result.get("errmsg")) : "unknown";
            throw new RuntimeException("微信登录失败,请重试(errmsg: " + errMsg + ")");
        } catch (Exception e) {
            log.error("微信 code2session 调用失败,code={}", code, e);
            if (devMode) {
                log.warn("开发模式:使用虚拟 openid dev_{}", code);
                return "dev_" + code;
            }
            throw new RuntimeException("登录服务异常,请稍后重试");
        }
    }

    /**
     * 生成随机中文昵称:姓氏 + 称呼后缀
     * <p>
     * 如:陈大叔、李阿姨、王师傅、张伯
     * </p>
     *
     * @return 随机昵称
     */
    private String generateNickname() {
        String[] surnames = {"陈", "李", "王", "张", "刘", "黄", "何", "周", "吴", "郑",
                "林", "杨", "赵", "马", "孙", "朱", "胡", "郭", "罗", "梁",
                "宋", "谢", "唐", "韩", "冯", "董", "萧", "程", "曹", "袁"};
        String[] suffixes = {"大叔", "伯", "哥", "师傅", "阿姨", "婶", "姐", "嫂", "老农", "园丁"};
        return surnames[random.nextInt(surnames.length)] + suffixes[random.nextInt(suffixes.length)];
    }

    @Override
    public List<Map<String, Object>> listMyResponses(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        List<Map<String, Object>> result = new ArrayList<>();

        // 1. 意见反馈中有回复的
        List<Feedback> feedbacks = feedbackRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        for (Feedback fb : feedbacks) {
            if (fb.getReply() == null || fb.getReply().isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", "feedback");
            item.put("title", fb.getFeedbackType());
            item.put("originalContent", fb.getContent());
            item.put("reply", fb.getReply());
            item.put("responderName", "");
            item.put("createdAt", fb.getUpdatedAt() != null ? fb.getUpdatedAt() : fb.getCreatedAt());
            item.put("feedbackId", fb.getId());
            result.add(item);
        }

        // 2. 农情上报的管理员响应
        List<FarmReport> reports = farmReportRepository.findByOpenidOrderByCreatedAtDesc(openid);
        if (!reports.isEmpty()) {
            List<Long> reportIds = reports.stream().map(FarmReport::getId).toList();
            List<FarmResponse> responses = farmResponseRepository.findByReportIdInOrderByCreatedAtDesc(reportIds);
            // 建立 reportId -> title 映射
            Map<Long, String> reportTitles = new HashMap<>();
            for (FarmReport r : reports) {
                reportTitles.put(r.getId(), r.getTitle());
            }
            for (FarmResponse resp : responses) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", "farm-report");
                item.put("title", reportTitles.getOrDefault(resp.getReportId(), "农情上报"));
                item.put("originalContent", "");
                item.put("reply", resp.getContent());
                item.put("responderName", resp.getResponderName() != null ? resp.getResponderName() : "");
                item.put("createdAt", resp.getCreatedAt());
                item.put("reportId", resp.getReportId());
                result.add(item);
            }
        }

        // 按时间倒序合并排序
        result.sort((a, b) -> {
            Object ta = a.get("createdAt");
            Object tb = b.get("createdAt");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.toString().compareTo(ta.toString());
        });
        return result;
    }

    @Override
    public Map<String, Object> getUnreadResponseCount(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", countUnreadResponses(user));
        return result;
    }

    @Override
    @Transactional
    public void markResponsesRead(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        // 记录本次查看时间：此后管理员新产生的回复才会计入未读
        user.setLastReadResponseAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * 统计当前用户的未读管理员回复数
     * = 已回复且回复更新时间晚于上次查看时间的反馈 + 同时间条件的农情上报响应；
     * 从未查看过（lastReadResponseAt 为空）时，已经产生的全部回复都算未读。
     */
    private long countUnreadResponses(User user) {
        LocalDateTime lastRead = user.getLastReadResponseAt();
        long feedbackUnread = (lastRead == null)
                ? feedbackRepository.countByUserIdAndReplyIsNotNull(user.getId())
                : feedbackRepository.countByUserIdAndReplyIsNotNullAndUpdatedAtAfter(user.getId(), lastRead);

        long reportUnread = 0;
        List<FarmReport> reports = farmReportRepository.findByOpenidOrderByCreatedAtDesc(user.getOpenid());
        if (!reports.isEmpty()) {
            List<Long> reportIds = reports.stream().map(FarmReport::getId).toList();
            reportUnread = (lastRead == null)
                    ? farmResponseRepository.countByReportIdIn(reportIds)
                    : farmResponseRepository.countByReportIdInAndCreatedAtAfter(reportIds, lastRead);
        }
        return feedbackUnread + reportUnread;
    }

    @Override
    public Map<String, Object> updateMyCrops(String openid, List<String> crops) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        String cropsStr = (crops == null || crops.isEmpty()) ? "" : String.join(",", crops);
        user.setMyCrops(cropsStr);
        userRepository.save(user);
        Map<String, Object> result = new HashMap<>();
        result.put("myCrops", parseMyCrops(cropsStr));
        return result;
    }

    @Override
    public List<String> getMyCrops(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        return parseMyCrops(user.getMyCrops());
    }

    /**
     * 将逗号分隔的作物字符串解析为列表
     */
    private List<String> parseMyCrops(String cropsStr) {
        if (cropsStr == null || cropsStr.isBlank()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String s : cropsStr.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getTodayFarmingTasks(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        List<String> myCrops = parseMyCrops(user.getMyCrops());
        Map<String, String> plantingDates = parsePlantingDates(user.getCropPlantingDates());
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();

        for (String cropName : myCrops) {
            String dateStr = plantingDates.get(cropName);
            if (dateStr == null || dateStr.isBlank()) {
                continue; // 未设置播种日期的作物不计算
            }
            try {
                LocalDate plantingDate = LocalDate.parse(dateStr, DATE_FMT);
                int currentDay = (int) ChronoUnit.DAYS.between(plantingDate, today);
                if (currentDay < 0) {
                    continue; // 播种日期在未来，跳过
                }
                List<CropCalendarKnowledge.FarmTask> tasks = CropCalendarKnowledge.getTodayTasks(cropName, currentDay);
                for (CropCalendarKnowledge.FarmTask task : tasks) {
                    // ========== 懒物化：看得见的任务才落记录 ==========
                    // 同一 openid+crop+task+date 只建一条（唯一索引兜底），
                    // 已存在则带上它的 status / recordId，前端才能显示"已打卡"。
                    FarmingTaskRecord record = materializeTaskRecord(
                            openid, cropName, task, today);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("recordId", record != null ? record.getId() : null);
                    item.put("status", record != null ? record.getStatus() : "pending");
                    item.put("completedAt", record != null ? record.getCompletedAt() : null);
                    item.put("cropName", cropName);
                    item.put("currentDay", currentDay);
                    item.put("taskName", task.getTaskName());
                    item.put("description", task.getDescription());
                    item.put("priority", task.getPriority());
                    item.put("targetDay", task.getDay());
                    result.add(item);
                }
            } catch (Exception e) {
                log.warn("解析作物 {} 播种日期失败: {}", cropName, e.getMessage());
            }
        }

        // 按优先级排序：high > medium > low；同优先级未完成的排前面
        result.sort((a, b) -> {
            int doneA = "completed".equals(a.get("status")) ? 1 : 0;
            int doneB = "completed".equals(b.get("status")) ? 1 : 0;
            if (doneA != doneB) {
                return Integer.compare(doneA, doneB);
            }
            String pa = (String) a.get("priority");
            String pb = (String) b.get("priority");
            int ia = "high".equals(pa) ? 0 : ("medium".equals(pa) ? 1 : 2);
            int ib = "high".equals(pb) ? 0 : ("medium".equals(pb) ? 1 : 2);
            return Integer.compare(ia, ib);
        });
        return result;
    }

    /**
     * 懒物化任务记录：存在即复用，不存在即建 pending。
     * <p>
     * 并发下两个请求同时 insert 会撞唯一索引 —— 捕获后重新读一次即可，
     * 不能让列表接口因为竞态而 500。
     * </p>
     *
     * @return 已存在的或新建的记录；数据库写入持续失败时返回 null（列表仍可用，只是不能打卡）
     */
    private FarmingTaskRecord materializeTaskRecord(String openid, String cropName,
                                                    CropCalendarKnowledge.FarmTask task, LocalDate today) {
        try {
            return farmingTaskRecordRepository
                    .findByOpenidAndCropNameAndTaskNameAndTaskDate(openid, cropName, task.getTaskName(), today)
                    .orElseGet(() -> farmingTaskRecordRepository.save(
                            new FarmingTaskRecord(openid, cropName, task.getTaskName(),
                                    task.getDescription(), task.getDay(), today, task.getPriority())));
        } catch (DataIntegrityViolationException race) {
            // 并发撞唯一索引：另一个请求已经建了，读回来用
            return farmingTaskRecordRepository
                    .findByOpenidAndCropNameAndTaskNameAndTaskDate(openid, cropName, task.getTaskName(), today)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("物化农事任务记录失败 {}/{}/{}: {}", openid, cropName, task.getTaskName(), e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public Map<String, Object> completeFarmingTask(Long recordId, String openid) {
        FarmingTaskRecord record = farmingTaskRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException("任务记录不存在"));
        // 越权校验：只能打自己的卡
        if (!record.getOpenid().equals(openid)) {
            throw new BusinessException("无权操作他人的农事任务");
        }
        // 幂等：重复打卡直接返回现状，不算错误（前端双击/重试很常见）
        if (!"completed".equals(record.getStatus())) {
            record.setStatus("completed");
            record.setCompletedAt(LocalDateTime.now());
            farmingTaskRecordRepository.save(record);
            log.info("农事任务打卡: {} 完成 {}-{}", openid, record.getCropName(), record.getTaskName());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("recordId", record.getId());
        result.put("status", record.getStatus());
        result.put("completedAt", record.getCompletedAt());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> uncompleteFarmingTask(Long recordId, String openid) {
        FarmingTaskRecord record = farmingTaskRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException("任务记录不存在"));
        if (!record.getOpenid().equals(openid)) {
            throw new BusinessException("无权操作他人的农事任务");
        }
        // 取消打卡（误触回退）：同样幂等
        if ("completed".equals(record.getStatus())) {
            record.setStatus("pending");
            record.setCompletedAt(null);
            farmingTaskRecordRepository.save(record);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("recordId", record.getId());
        result.put("status", record.getStatus());
        return result;
    }

    @Override
    public Map<String, Object> getFarmingRecords(String openid, int days) {
        // 窗口收敛：负数/0 当 30 天，超过 90 天截断（档案页最多回看一个季度）
        int window = days <= 0 ? 30 : Math.min(days, 90);
        LocalDate today = LocalDate.now();
        // (window - 1) 天：含今天一共 window 天
        LocalDate from = today.minusDays(window - 1L);

        List<FarmingTaskRecord> records = farmingTaskRecordRepository.findArchive(openid, from, today);

        // ---- 按日分组（记录已按日期倒序，LinkedHashMap 保持这个顺序）----
        Map<LocalDate, List<FarmingTaskRecord>> byDate = new LinkedHashMap<>();
        for (FarmingTaskRecord r : records) {
            byDate.computeIfAbsent(r.getTaskDate(), k -> new ArrayList<>()).add(r);
        }

        // ---- 连续日历：即使某天没有记录也补一格，热力图才不会出现空洞 ----
        List<Map<String, Object>> calendar = new ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            List<FarmingTaskRecord> day = byDate.getOrDefault(d, List.of());
            long done = day.stream().filter(r -> "completed".equals(r.getStatus())).count();
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("date", d.format(DATE_FMT));
            cell.put("total", day.size());
            cell.put("completed", (int) done);
            cell.put("rate", day.isEmpty() ? null : Math.round(done * 100.0 / day.size()));
            calendar.add(cell);
        }

        // ---- 明细分组：只返回有记录的日子 ----
        List<Map<String, Object>> groups = new ArrayList<>();
        byDate.forEach((d, list) -> {
            long done = list.stream().filter(r -> "completed".equals(r.getStatus())).count();
            List<Map<String, Object>> items = new ArrayList<>();
            for (FarmingTaskRecord r : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("recordId", r.getId());
                item.put("cropName", r.getCropName());
                item.put("taskName", r.getTaskName());
                item.put("description", r.getDescription());
                item.put("targetDay", r.getTargetDay());
                item.put("priority", r.getPriority());
                item.put("status", r.getStatus());
                item.put("completedAt", r.getCompletedAt());
                items.add(item);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("date", d.format(DATE_FMT));
            g.put("weekday", WEEKDAY_CN[d.getDayOfWeek().getValue() - 1]);
            g.put("total", list.size());
            g.put("completed", (int) done);
            g.put("rate", Math.round(done * 100.0 / list.size()));
            g.put("items", items);
            groups.add(g);
        });

        // ---- 总览 ----
        long total = records.size();
        long completed = records.stream().filter(r -> "completed".equals(r.getStatus())).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", (int) total);
        summary.put("completed", (int) completed);
        summary.put("pending", (int) (total - completed));
        summary.put("rate", total == 0 ? null : Math.round(completed * 100.0 / total));
        summary.put("activeDays", byDate.size());
        summary.put("streak", streakDays(byDate, today));
        summary.put("bestDay", byDate.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .max(Comparator.comparingLong(e ->
                        e.getValue().stream().filter(r -> "completed".equals(r.getStatus())).count()))
                .map(e -> e.getKey().format(DATE_FMT))
                .orElse(null));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", window);
        result.put("summary", summary);
        result.put("calendar", calendar);
        result.put("groups", groups);
        return result;
    }

    /**
     * 连续完成天数：从今天往回数「当天有任务且全部完成」的连续天数；
     * 今天还没做完就从昨天起算，否则早上打开档案永远显示 0。
     */
    private static int streakDays(Map<LocalDate, List<FarmingTaskRecord>> byDate, LocalDate today) {
        LocalDate cursor = today;
        List<FarmingTaskRecord> todayList = byDate.getOrDefault(cursor, List.of());
        boolean todayAllDone = !todayList.isEmpty()
                && todayList.stream().allMatch(r -> "completed".equals(r.getStatus()));
        if (!todayAllDone) {
            cursor = today.minusDays(1);
        }
        int streak = 0;
        while (true) {
            List<FarmingTaskRecord> day = byDate.get(cursor);
            if (day == null || day.isEmpty()) {
                break;
            }
            if (day.stream().allMatch(r -> "completed".equals(r.getStatus()))) {
                streak++;
                cursor = cursor.minusDays(1);
            } else {
                break;
            }
        }
        return streak;
    }

    @Override
    @Transactional
    public Map<String, Object> updateCropPlantingDate(String openid, String cropName, String plantingDate) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        Map<String, String> dates = parsePlantingDates(user.getCropPlantingDates());
        if (plantingDate == null || plantingDate.isBlank()) {
            dates.remove(cropName);
        } else {
            // 校验日期格式
            LocalDate.parse(plantingDate, DATE_FMT);
            dates.put(cropName, plantingDate);
        }
        user.setCropPlantingDates(toJson(dates));
        userRepository.save(user);
        Map<String, Object> result = new HashMap<>();
        result.put("cropPlantingDates", dates);
        return result;
    }

    @Override
    public Map<String, String> getCropPlantingDates(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        return parsePlantingDates(user.getCropPlantingDates());
    }

    /**
     * 解析播种日期JSON
     */
    private Map<String, String> parsePlantingDates(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析播种日期JSON失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 对象转JSON字符串
     */
    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public Map<String, Double> getUserCropAreas(String openid) {
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        Map<String, Double> result = new LinkedHashMap<>();
        for (UserCrop uc : userCropRepository.findByUserId(user.getId())) {
            if (uc.getAreaMu() != null) {
                result.put(uc.getCropName(), uc.getAreaMu());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> updateCropArea(String openid, String cropName, Double areaMu) {
        if (cropName == null || cropName.isBlank()) {
            throw new BusinessException("作物名称不能为空");
        }
        if (areaMu != null && (areaMu <= 0 || areaMu > 1_000_000)) {
            throw new BusinessException("种植面积需在 0 ~ 1000000 亩之间");
        }
        User user = userRepository.findByOpenid(openid)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        if (areaMu == null) {
            // 面积清空 → 删除该行记录
            userCropRepository.deleteByUserIdAndCropName(user.getId(), cropName);
        } else {
            UserCrop uc = userCropRepository.findByUserIdAndCropName(user.getId(), cropName)
                    .orElseGet(() -> {
                        UserCrop created = new UserCrop();
                        created.setUserId(user.getId());
                        created.setCropName(cropName);
                        return created;
                    });
            uc.setAreaMu(areaMu);
            userCropRepository.save(uc);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("cropName", cropName);
        result.put("areaMu", areaMu);
        return result;
    }
}
