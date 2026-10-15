package com.wqb.springboot.dto;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果包装类。
 * <p>
 * 结构刻意保持简单（items + total + page + size），不直接暴露 Spring Data 的 Page 对象，
 * 原因有二：
 * <ol>
 *   <li>序列化 Spring 的 Page 实现类时会带上一大堆前端用不到的字段
 *       （sort/pageable 等），将来 Spring 升级还可能改字段名导致前端隐性破坏；</li>
 *   <li>业务层有时拿到的并不是真正的数据库分页结果（比如先过滤再内存分页），
 *       统一包装后调用方不必关心实现细节。</li>
 * </ol>
 * </p>
 *
 * @param <T> 列表元素类型
 */
public record PageResult<T>(
        /** 当前页数据 */
        List<T> items,
        /** 总记录数（用于分页器显示总条数） */
        long total,
        /** 当前页码（从 0 开始，与前端 el-pagination 的 current-page-1 对齐） */
        int page,
        /** 每页大小 */
        int size
) {

    /**
     * 从 Spring Data 的 Page 转换。
     *
     * @param page 数据库分页结果
     * @param <T> 元素类型
     * @return 统一包装后的分页结果
     */
    public static <T> PageResult<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements(),
                page.getNumber(), page.getSize());
    }

    /**
     * 把内存里的完整列表切出指定页（用于必须先过滤、无法直接下推 SQL 的场景）。
     *
     * @param all  完整列表
     * @param page 页码（从 0 开始）
     * @param size 每页大小
     * @param <T>  元素类型
     * @return 统一包装后的分页结果；页码超出范围时 items 为空
     */
    public static <T> PageResult<T> ofList(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), page, size);
    }

    /**
     * 元素类型映射（保留分页元信息），例如 Entity → DTO。
     *
     * @param mapper 元素转换函数
     * @param <R>    目标元素类型
     * @return 元素转换后的分页结果
     */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, page, size);
    }
}
