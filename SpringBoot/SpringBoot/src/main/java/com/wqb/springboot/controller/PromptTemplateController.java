package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.PromptTemplateDto;
import com.wqb.springboot.entity.PromptTemplate;
import com.wqb.springboot.service.PromptTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词模板管理接口
 * <p>
 * 把系统提示词从代码里搬到数据库，后台就能改、能留版本、能回滚，
 * 做提示词对照实验时也能说清「哪组数据用的是哪一版提示词」。
 * </p>
 *
 * <p>写操作限制为超级管理员（与 {@code /api/admin/config/**} 同级）：
 * 提示词属于系统级配置，被改成无关内容会让整个 AI 模块跑偏。</p>
 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /** 全部模板（按编码、版本号倒序） */
    @GetMapping
    public ApiResponse<List<PromptTemplate>> listAll() {
        return ApiResponse.ok(promptTemplateService.listAll());
    }

    /** 某个编码下的所有版本 */
    @GetMapping("/{code}/versions")
    public ApiResponse<List<PromptTemplate>> listVersions(@PathVariable String code) {
        return ApiResponse.ok(promptTemplateService.listByCode(code));
    }

    /** 新增一个版本（自动启用，同编码旧版本自动停用） */
    @PostMapping
    public ApiResponse<PromptTemplate> create(@RequestBody PromptTemplateDto.UpsertRequest request) {
        try {
            return ApiResponse.ok(promptTemplateService.create(request));
        } catch (Exception e) {
            return ApiResponse.fail("新增提示词模板失败: " + e.getMessage(), null);
        }
    }

    /** 编辑指定版本 */
    @PutMapping("/{id}")
    public ApiResponse<PromptTemplate> update(@PathVariable Long id,
                                              @RequestBody PromptTemplateDto.UpsertRequest request) {
        try {
            return ApiResponse.ok(promptTemplateService.update(id, request));
        } catch (Exception e) {
            return ApiResponse.fail("修改提示词模板失败: " + e.getMessage(), null);
        }
    }

    /** 启用指定版本（回滚到旧版本就调它） */
    @PostMapping("/{id}/activate")
    public ApiResponse<PromptTemplate> activate(@PathVariable Long id) {
        try {
            return ApiResponse.ok(promptTemplateService.activate(id));
        } catch (Exception e) {
            return ApiResponse.fail("启用提示词模板失败: " + e.getMessage(), null);
        }
    }

    /** 删除指定版本 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            promptTemplateService.delete(id);
            return ApiResponse.ok(null);
        } catch (Exception e) {
            return ApiResponse.fail("删除提示词模板失败: " + e.getMessage(), null);
        }
    }
}
