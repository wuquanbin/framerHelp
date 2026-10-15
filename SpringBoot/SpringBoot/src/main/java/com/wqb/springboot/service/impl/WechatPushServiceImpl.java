package com.wqb.springboot.service.impl;

import com.wqb.springboot.service.WechatPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息推送服务实现类
 * <p>
 * 用于推送灾害预警和农事提醒通知。
 * </p>
 */
@Service
public class WechatPushServiceImpl implements WechatPushService {

    private static final Logger log = LoggerFactory.getLogger(WechatPushServiceImpl.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Value("${wechat.appid:}")
    private String appId;

    @Value("${wechat.secret:}")
    private String appSecret;

    @Value("${wechat.template.warning:}")
    private String warningTemplateId;

    @Value("${wechat.template.farming:}")
    private String farmingTemplateId;

    private final RestTemplate restTemplate;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // access_token 缓存
    private volatile String accessToken;
    private volatile long tokenExpireTime = 0;

    public WechatPushServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取access_token（带缓存）
     */
    private String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpireTime - 60000) {
            return accessToken;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (accessToken != null && now < tokenExpireTime - 60000) {
                return accessToken;
            }
            try {
                String url = String.format(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                        appId, appSecret
                );
                // 微信返回 Content-Type 为 text/plain，改用 String 接收后手动解析
                String responseBody = restTemplate.getForObject(url, String.class);
                Map<?, ?> result = OBJECT_MAPPER.readValue(responseBody, Map.class);
                if (result != null && result.get("access_token") != null) {
                    accessToken = (String) result.get("access_token");
                    int expiresIn = result.get("expires_in") != null ? ((Number) result.get("expires_in")).intValue() : 7200;
                    tokenExpireTime = now + expiresIn * 1000L;
                    log.info("获取微信access_token成功，有效期{}秒", expiresIn);
                    return accessToken;
                }
                String errmsg = result != null ? String.valueOf(result.get("errmsg")) : "unknown";
                log.error("获取微信access_token失败: {}", errmsg);
            } catch (Exception e) {
                log.error("获取微信access_token异常", e);
            }
            return null;
        }
    }

    @Override
    @Async
    public void pushWarningNotification(String openid, String warningType, String area, String content, String severity) {
        if (warningTemplateId == null || warningTemplateId.isBlank()) {
            log.info("[推送-开发模式] 灾害预警推送 openid={}, type={}, area={}, content={}", openid, warningType, area, content);
            return;
        }
        // 模板「救援预警通知」: time1(预警时间) + short_thing2(预警类型,最多5字)
        // + thing3(预警内容,最多20字) + short_thing4(预警等级,最多5字) + thing5(预警地点,最多20字)
        Map<String, Object> data = new HashMap<>();
        data.put("time1", createTimeValue(LocalDateTime.now().format(TIME_FMT)));
        data.put("short_thing2", createThingValue(truncate(warningType, 5)));
        data.put("thing3", createThingValue(truncate(content, 20)));
        data.put("short_thing4", createThingValue(truncate(severityLabel(severity), 5)));
        data.put("thing5", createThingValue(truncate(area, 20)));
        sendSubscribeMessage(openid, warningTemplateId, data, "灾害预警");
    }

    /** 预警等级英文标识转中文标签 */
    private String severityLabel(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "red" -> "红色";
            case "orange" -> "橙色";
            case "yellow" -> "黄色";
            case "blue" -> "蓝色";
            default -> severity;
        };
    }

    @Override
    @Async
    public void pushFarmingNotification(String openid, String title, String advice, String note) {
        if (farmingTemplateId == null || farmingTemplateId.isBlank()) {
            log.info("[推送-开发模式] 农事提醒推送 openid={}, title={}, advice={}, note={}", openid, title, advice, note);
            return;
        }
        // 模板「农事活动操作提醒」: time1(提醒时间) + thing2(操作事项,最多20字)
        // 标题/建议/备注合并为一句操作事项,超出截断
        String action = title;
        if (advice != null && !advice.isBlank()) {
            action += "：" + advice;
        }
        if (note != null && !note.isBlank()) {
            action += "（" + note + "）";
        }
        Map<String, Object> data = new HashMap<>();
        data.put("time1", createTimeValue(LocalDateTime.now().format(TIME_FMT)));
        data.put("thing2", createThingValue(truncate(action, 20)));
        sendSubscribeMessage(openid, farmingTemplateId, data, "农事提醒");
    }

    @Override
    @Async
    public void pushWarningToUsers(List<String> openids, String warningType, String area, String content, String severity) {
        log.info("开始批量推送灾害预警，用户数：{}，类型：{}，等级：{}", openids.size(), warningType, severity);
        for (String openid : openids) {
            try {
                pushWarningNotification(openid, warningType, area, content, severity);
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("推送预警给用户 {} 失败: {}", openid, e.getMessage());
            }
        }
        log.info("灾害预警批量推送完成");
    }

    @Override
    @Async
    public void pushFarmingToUsers(List<String> openids, String title, String advice, String note) {
        log.info("开始批量推送农事提醒，用户数：{}，标题：{}", openids.size(), title);
        for (String openid : openids) {
            try {
                pushFarmingNotification(openid, title, advice, note);
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("推送农事提醒给用户 {} 失败: {}", openid, e.getMessage());
            }
        }
        log.info("农事提醒批量推送完成");
    }

    /**
     * 发送订阅消息
     */
    private void sendSubscribeMessage(String openid, String templateId, Map<String, Object> data, String type) {
        String token = getAccessToken();
        if (token == null) {
            log.error("发送{}消息失败：无法获取access_token（未配置appid/secret时为开发模式）", type);
            log.info("[开发模式] 模拟发送{}消息: openid={}, data={}", type, openid, data);
            return;
        }
        try {
            String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token;
            Map<String, Object> body = new HashMap<>();
            body.put("touser", openid);
            body.put("template_id", templateId);
            body.put("page", "pages/index/index");
            body.put("data", data);
            body.put("miniprogram_state", "formal");

            // 微信返回 Content-Type 为 text/plain，改用 String 接收后手动解析
            String responseBody = restTemplate.postForObject(url, body, String.class);
            Map<?, ?> result = OBJECT_MAPPER.readValue(responseBody, Map.class);
            if (result != null) {
                Number errcode = result.get("errcode") != null ? ((Number) result.get("errcode")) : 0;
                if (errcode.intValue() == 0) {
                    log.info("{}消息推送成功 openid={}", type, openid);
                } else {
                    log.warn("{}消息推送失败 openid={}, errcode={}, errmsg={}", type, openid, errcode, result.get("errmsg"));
                }
            }
        } catch (Exception e) {
            log.error("{}消息推送异常 openid={}", type, openid, e);
        }
    }

    private Map<String, Object> createThingValue(String value) {
        Map<String, Object> map = new HashMap<>();
        map.put("value", value);
        return map;
    }

    private Map<String, Object> createTimeValue(String value) {
        Map<String, Object> map = new HashMap<>();
        map.put("value", value);
        return map;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        text = text.trim();
        return text.length() > maxLen ? text.substring(0, maxLen - 1) + "…" : text;
    }
}
