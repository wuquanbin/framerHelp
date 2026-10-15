package com.wqb.springboot.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageResult} 单元测试。
 * <p>
 * 后台 7 个列表接口的返回形状都由这个类决定，切页边界错一格的后果是
 * 「最后一页点开是空的」，所以这里把边界单独钉住。纯函数，不起 Spring。
 * </p>
 */
@DisplayName("分页结果包装")
class PageResultTest {

    private static List<Integer> list(int n) {
        return java.util.stream.IntStream.range(0, n).boxed().toList();
    }

    @Test
    @DisplayName("切第 1 页：取前 size 条，total 是总量不是本页量")
    void firstPage() {
        PageResult<Integer> p = PageResult.ofList(list(45), 0, 20);
        assertEquals(20, p.items().size());
        assertEquals(45, p.total());
        assertEquals(0, p.page());
        assertEquals(20, p.size());
        assertEquals(0, p.items().get(0));
        assertEquals(19, p.items().get(19));
    }

    @Test
    @DisplayName("最后一页不足 size 条时只返回剩余部分")
    void lastPage() {
        PageResult<Integer> p = PageResult.ofList(list(45), 2, 20);
        assertEquals(5, p.items().size());
        assertEquals(40, p.items().get(0));
        assertEquals(45, p.total());
    }

    @Test
    @DisplayName("页码超出范围时 items 为空而不是抛异常")
    void pageOutOfRange() {
        PageResult<Integer> p = PageResult.ofList(list(10), 9, 20);
        assertTrue(p.items().isEmpty());
        assertEquals(10, p.total());
    }

    @Test
    @DisplayName("map 转换元素类型时保留分页元信息")
    void mapKeepsMeta() {
        PageResult<String> p = PageResult.ofList(list(30), 1, 10).map(String::valueOf);
        assertEquals(10, p.items().size());
        assertEquals(30, p.total());
        assertEquals(1, p.page());
        assertEquals(10, p.size());
        assertEquals("10", p.items().get(0));
    }

    @Test
    @DisplayName("空列表分页不炸，total 为 0")
    void emptyList() {
        PageResult<Integer> p = PageResult.ofList(List.of(), 0, 20);
        assertTrue(p.items().isEmpty());
        assertEquals(0, p.total());
    }
}
