package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "farm_response")
public class FarmResponse extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false, length = 64)
    private String responderOpenid;

    @Column(length = 64)
    private String responderName;

    @Column(length = 255)
    private String responderAvatar;

    /** 响应类型: reply-回复, guidance-技术指导, dispatch-派人处理, close-关闭 */
    @Column(nullable = false, length = 20)
    private String responseType;

    @Column(nullable = false, length = 1000)
    private String content;

    public FarmResponse() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getResponderOpenid() { return responderOpenid; }
    public void setResponderOpenid(String responderOpenid) { this.responderOpenid = responderOpenid; }
    public String getResponderName() { return responderName; }
    public void setResponderName(String responderName) { this.responderName = responderName; }
    public String getResponderAvatar() { return responderAvatar; }
    public void setResponderAvatar(String responderAvatar) { this.responderAvatar = responderAvatar; }
    public String getResponseType() { return responseType; }
    public void setResponseType(String responseType) { this.responseType = responseType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
