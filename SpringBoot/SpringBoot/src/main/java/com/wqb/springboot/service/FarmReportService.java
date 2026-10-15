package com.wqb.springboot.service;

import com.wqb.springboot.entity.FarmReport;
import com.wqb.springboot.entity.FarmResponse;

import java.util.List;

/**
 * 农情上报服务接口
 * <p>
 * 提供农情上报的创建、查询、状态更新、删除，以及帮扶响应的管理功能。
 * </p>
 */
public interface FarmReportService {

    FarmReport createReport(FarmReport report);

    List<FarmReport> getReports(String villageCode);

    List<FarmReport> getReportsByType(String villageCode, String reportType);

    FarmReport getReport(Long id);

    FarmReport updateStatus(Long id, String status);

    void deleteReport(Long id);

    FarmResponse addResponse(FarmResponse response);

    List<FarmResponse> getResponses(Long reportId);

    List<FarmResponse> getAllResponses();

    void deleteResponse(Long id);
}
