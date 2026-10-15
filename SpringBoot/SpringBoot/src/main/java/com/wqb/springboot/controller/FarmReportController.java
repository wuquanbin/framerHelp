package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.FarmReportAiDto;
import com.wqb.springboot.entity.FarmReport;
import com.wqb.springboot.entity.FarmResponse;
import com.wqb.springboot.entity.Village;
import com.wqb.springboot.repository.VillageRepository;
import com.wqb.springboot.service.FarmReportAiService;
import com.wqb.springboot.service.FarmReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/farm-reports")
public class FarmReportController {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    /** 上传根目录，取自配置项 {@code app.upload.path}（写死路径在 Linux 部署时会因目录不存在导致上传失败） */
    @Value("${app.upload.path:D:/weather-uploads}")
    private String uploadBaseDir;

    private final FarmReportService farmReportService;
    private final FarmReportAiService farmReportAiService;
    private final VillageRepository villageRepository;

    public FarmReportController(FarmReportService farmReportService,
                                FarmReportAiService farmReportAiService,
                                VillageRepository villageRepository) {
        this.farmReportService = farmReportService;
        this.farmReportAiService = farmReportAiService;
        this.villageRepository = villageRepository;
    }

    /** 上传农情上报图片 */
    @PostMapping("/upload-image")
    public ApiResponse<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ApiResponse.fail("文件为空");
        }

        // 文件大小校验
        if (file.getSize() > MAX_FILE_SIZE) {
            return ApiResponse.fail("文件大小不能超过5MB");
        }

        // 文件类型校验
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_IMAGE_TYPES.contains(ext)) {
            return ApiResponse.fail("仅支持 jpg、png、gif、webp、bmp 格式的图片");
        }

        try {
            String uploadDir = uploadBaseDir + "/farm-reports/";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            String fileName = UUID.randomUUID().toString().substring(0, 8) + "_" + System.currentTimeMillis() + ext;
            File destFile = new File(uploadDir + fileName);
            file.transferTo(destFile);

            Map<String, String> result = new HashMap<>();
            result.put("url", "/uploads/farm-reports/" + fileName);
            return ApiResponse.ok(result);
        } catch (IOException e) {
            return ApiResponse.fail("图片上传失败: " + e.getMessage());
        }
    }

    /** 提交农情上报 */
    @PostMapping
    public ResponseEntity<ApiResponse<FarmReport>> createReport(@RequestBody FarmReport report) {
        if (report.getOpenid() == null || report.getOpenid().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("请先登录后再上报"));
        }
        if (report.getVillageCode() == null || report.getVillageCode().isBlank()) {
            report.setVillageCode("baini");
            report.setVillageName("白坭镇");
        }
        // 传了村编码但没传村名：按编码查库补齐，避免 village_name 非空约束报错
        if (report.getVillageName() == null || report.getVillageName().isBlank()) {
            String villageName = villageRepository.findByCode(report.getVillageCode())
                    .map(Village::getName).orElse("白坭镇");
            report.setVillageName(villageName);
        }
        if (report.getReportType() == null) report.setReportType("other");
        if (report.getTitle() == null) report.setTitle("农情上报");
        if (report.getDescription() == null) report.setDescription("");
        return ResponseEntity.ok(ApiResponse.ok(farmReportService.createReport(report)));
    }

    /**
     * 智能受理：把农户原话整理成工单草稿
     * <p>
     * 小程序端在提交前调它做「一键整理」，后台受理时也能调。
     * 只返回草稿，不写库——是否采纳由人决定（农户可改、专家可改），
     * 模型在这里是辅助分拣，不是替人做决定。
     * </p>
     */
    @PostMapping("/ai-parse")
    public ApiResponse<FarmReportAiDto.ParseResult> aiParse(
            @RequestBody FarmReportAiDto.ParseRequest request) {
        try {
            return ApiResponse.ok(farmReportAiService.parse(request));
        } catch (Exception e) {
            return ApiResponse.fail("智能受理失败: " + e.getMessage(), null);
        }
    }

    /** 查询农情上报列表 */
    @GetMapping
    public ApiResponse<List<FarmReport>> getReports(
            @RequestParam(required = false) String villageCode,
            @RequestParam(required = false) String reportType) {
        if (reportType != null && !reportType.isBlank()) {
            return ApiResponse.ok(farmReportService.getReportsByType(villageCode, reportType));
        }
        return ApiResponse.ok(farmReportService.getReports(villageCode));
    }

    /** 查询上报详情 */
    @GetMapping("/{id}")
    public ApiResponse<FarmReport> getReport(@PathVariable Long id) {
        return ApiResponse.ok(farmReportService.getReport(id));
    }

    /** 更新上报状态 */
    @PutMapping("/{id}/status")
    public ApiResponse<FarmReport> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return ApiResponse.ok(farmReportService.updateStatus(id, status));
    }

    /** 删除上报 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteReport(@PathVariable Long id) {
        farmReportService.deleteReport(id);
        return ApiResponse.ok(null);
    }

    /** 添加帮扶响应 */
    @PostMapping("/{id}/responses")
    public ResponseEntity<ApiResponse<FarmResponse>> addResponse(
            @PathVariable Long id,
            @RequestBody FarmResponse response) {
        if (response.getResponderOpenid() == null || response.getResponderOpenid().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("请先登录"));
        }
        response.setReportId(id);
        return ResponseEntity.ok(ApiResponse.ok(farmReportService.addResponse(response)));
    }

    /** 查询上报的响应列表 */
    @GetMapping("/{id}/responses")
    public ApiResponse<List<FarmResponse>> getResponses(@PathVariable Long id) {
        return ApiResponse.ok(farmReportService.getResponses(id));
    }

    /** 删除响应 */
    @DeleteMapping("/responses/{id}")
    public ApiResponse<Void> deleteResponse(@PathVariable Long id) {
        farmReportService.deleteResponse(id);
        return ApiResponse.ok(null);
    }
}
