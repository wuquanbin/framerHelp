package com.wqb.springboot.service.impl;
import com.wqb.springboot.exception.BusinessException;

import com.wqb.springboot.entity.FarmReport;
import com.wqb.springboot.entity.FarmResponse;
import com.wqb.springboot.entity.User;
import com.wqb.springboot.repository.FarmReportRepository;
import com.wqb.springboot.repository.FarmResponseRepository;
import com.wqb.springboot.repository.UserRepository;
import com.wqb.springboot.service.FarmReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 农情上报服务实现类
 * <p>
 * 提供农情上报的创建、查询、状态更新、删除，以及帮扶响应的管理功能。
 * </p>
 */
@Service
public class FarmReportServiceImpl implements FarmReportService {

    private final FarmReportRepository reportRepo;
    private final FarmResponseRepository responseRepo;
    private final UserRepository userRepo;

    public FarmReportServiceImpl(FarmReportRepository reportRepo, FarmResponseRepository responseRepo, UserRepository userRepo) {
        this.reportRepo = reportRepo;
        this.responseRepo = responseRepo;
        this.userRepo = userRepo;
    }

    @Override
    public FarmReport createReport(FarmReport report) {
        // 创建时从用户表获取最新头像和昵称
        if (report.getOpenid() != null) {
            userRepo.findByOpenid(report.getOpenid()).ifPresent(user -> {
                if (report.getNickname() == null || report.getNickname().isBlank()) {
                    report.setNickname(user.getNickname());
                }
                report.setAvatarUrl(user.getAvatarUrl());
            });
        }
        return reportRepo.save(report);
    }

    @Override
    public List<FarmReport> getReports(String villageCode) {
        List<FarmReport> reports;
        if (villageCode != null && !villageCode.isBlank()) {
            reports = reportRepo.findByVillageCodeOrderByCreatedAtDesc(villageCode);
        } else {
            reports = reportRepo.findAllByOrderByCreatedAtDesc();
        }
        // 动态更新所有上报的头像为最新
        enrichReportsWithLatestAvatars(reports);
        return reports;
    }

    @Override
    public List<FarmReport> getReportsByType(String villageCode, String reportType) {
        // 与"全部"查询保持一致：指定了村庄则按村庄+类型过滤，避免跨村显示数据
        List<FarmReport> reports;
        if (villageCode != null && !villageCode.isBlank()) {
            reports = reportRepo.findByVillageCodeAndReportTypeOrderByCreatedAtDesc(villageCode, reportType);
        } else {
            reports = reportRepo.findByReportTypeOrderByCreatedAtDesc(reportType);
        }
        enrichReportsWithLatestAvatars(reports);
        return reports;
    }

    @Override
    public FarmReport getReport(Long id) {
        FarmReport report = reportRepo.findById(id).orElseThrow(() -> new BusinessException("上报记录不存在"));
        // 动态更新头像为最新
        enrichReportWithLatestAvatar(report);
        return report;
    }

    @Override
    public FarmReport updateStatus(Long id, String status) {
        FarmReport report = getReport(id);
        report.setStatus(status);
        return reportRepo.save(report);
    }

    @Override
    @Transactional // 响应与上报须在同一事务内级联删除，避免删一半失败留下脏数据
    public void deleteReport(Long id) {
        // Also delete all responses
        responseRepo.findByReportIdOrderByCreatedAtAsc(id).forEach(r -> responseRepo.delete(r));
        reportRepo.deleteById(id);
    }

    @Override
    public FarmResponse addResponse(FarmResponse response) {
        // 添加响应时从用户表获取最新头像和昵称
        if (response.getResponderOpenid() != null) {
            userRepo.findByOpenid(response.getResponderOpenid()).ifPresent(user -> {
                if (response.getResponderName() == null || response.getResponderName().isBlank()) {
                    response.setResponderName(user.getNickname());
                }
                response.setResponderAvatar(user.getAvatarUrl());
            });
        }
        // If it's a guidance or dispatch, auto-update report status
        FarmReport report = reportRepo.findById(response.getReportId()).orElse(null);
        if (report != null) {
            if ("guidance".equals(response.getResponseType()) && "pending".equals(report.getStatus())) {
                report.setStatus("processing");
                reportRepo.save(report);
            } else if ("close".equals(response.getResponseType())) {
                report.setStatus("resolved");
                reportRepo.save(report);
            }
        }
        return responseRepo.save(response);
    }

    @Override
    public List<FarmResponse> getResponses(Long reportId) {
        List<FarmResponse> responses = responseRepo.findByReportIdOrderByCreatedAtAsc(reportId);
        // 动态更新所有响应的头像为最新
        enrichResponsesWithLatestAvatars(responses);
        return responses;
    }

    @Override
    public List<FarmResponse> getAllResponses() {
        List<FarmResponse> responses = responseRepo.findAll();
        enrichResponsesWithLatestAvatars(responses);
        return responses;
    }

    @Override
    public void deleteResponse(Long id) {
        responseRepo.deleteById(id);
    }

    /**
     * 批量更新农情上报列表的头像为用户最新头像
     */
    private void enrichReportsWithLatestAvatars(List<FarmReport> reports) {
        // 收集所有openid
        List<String> openids = reports.stream()
                .map(FarmReport::getOpenid)
                .filter(openid -> openid != null && !openid.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (openids.isEmpty()) return;

        // 批量查询用户
        List<User> users = userRepo.findByOpenidIn(openids);
        Map<String, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getOpenid, u -> u, (a, b) -> a));

        // 更新头像
        for (FarmReport report : reports) {
            User user = userMap.get(report.getOpenid());
            if (user != null) {
                report.setNickname(user.getNickname());
                report.setAvatarUrl(user.getAvatarUrl());
            }
        }
    }

    /**
     * 更新单个农情上报的头像为用户最新头像
     */
    private void enrichReportWithLatestAvatar(FarmReport report) {
        if (report.getOpenid() != null) {
            userRepo.findByOpenid(report.getOpenid()).ifPresent(user -> {
                report.setNickname(user.getNickname());
                report.setAvatarUrl(user.getAvatarUrl());
            });
        }
    }

    /**
     * 批量更新响应列表的头像为用户最新头像
     */
    private void enrichResponsesWithLatestAvatars(List<FarmResponse> responses) {
        // 收集所有openid
        List<String> openids = responses.stream()
                .map(FarmResponse::getResponderOpenid)
                .filter(openid -> openid != null && !openid.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (openids.isEmpty()) return;

        // 批量查询用户
        List<User> users = userRepo.findByOpenidIn(openids);
        Map<String, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getOpenid, u -> u, (a, b) -> a));

        // 更新头像
        for (FarmResponse response : responses) {
            User user = userMap.get(response.getResponderOpenid());
            if (user != null) {
                response.setResponderName(user.getNickname());
                response.setResponderAvatar(user.getAvatarUrl());
            }
        }
    }
}
