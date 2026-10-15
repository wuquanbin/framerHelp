package com.wqb.springboot.service;

import java.util.List;

/**
 * 微信订阅消息推送服务接口
 * <p>
 * 用于推送灾害预警和农事提醒通知。
 * </p>
 */
public interface WechatPushService {

    /**
     * 推送灾害预警消息
     *
     * @param openid      接收用户openid
     * @param warningType 预警类型
     * @param area        影响区域
     * @param content     预警内容
     * @param severity    预警等级(red/orange/yellow/blue)
     */
    void pushWarningNotification(String openid, String warningType, String area, String content, String severity);

    /**
     * 推送农事提醒消息
     *
     * @param openid  接收用户openid
     * @param title   提醒标题
     * @param advice  农事建议
     * @param note    备注
     */
    void pushFarmingNotification(String openid, String title, String advice, String note);

    /**
     * 批量推送灾害预警
     *
     * @param openids      接收用户openid列表
     * @param warningType  预警类型
     * @param area         影响区域
     * @param content      预警内容
     * @param severity     预警等级(red/orange/yellow/blue)
     */
    void pushWarningToUsers(List<String> openids, String warningType, String area, String content, String severity);

    /**
     * 批量推送农事提醒
     *
     * @param openids 接收用户openid列表
     * @param title   提醒标题
     * @param advice  农事建议
     * @param note    备注
     */
    void pushFarmingToUsers(List<String> openids, String title, String advice, String note);
}
