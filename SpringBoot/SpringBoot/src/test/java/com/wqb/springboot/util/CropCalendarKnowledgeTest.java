package com.wqb.springboot.util;

import com.wqb.springboot.util.CropCalendarKnowledge.FarmTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CropCalendarKnowledge} 单元测试
 * <p>
 * 覆盖：日历精确/模糊/未命中三种查找、今日任务命中的浮动范围语义、
 * FarmTask.matches 的边界值。纯函数，无需 Spring 容器。
 * </p>
 */
@DisplayName("作物农事日历")
class CropCalendarKnowledgeTest {

    // ========== 日历查找 ==========

    @Test
    @DisplayName("精确命中：丝苗米有完整日历")
    void exactMatch() {
        List<FarmTask> calendar = CropCalendarKnowledge.getCalendar("丝苗米");
        assertNotNull(calendar);
        assertEquals(11, calendar.size(), "丝苗米应有 11 个农事节点");
    }

    @Test
    @DisplayName("早稻/晚稻复用丝苗米日历")
    void riceAliases() {
        List<FarmTask> base = CropCalendarKnowledge.getCalendar("丝苗米");
        assertEquals(base.size(), CropCalendarKnowledge.getCalendar("早稻").size());
        assertEquals(base.size(), CropCalendarKnowledge.getCalendar("晚稻").size());
    }

    @Test
    @DisplayName("模糊命中：名称包含关系也能找到日历")
    void fuzzyMatch() {
        // 名称比库里长：包含"丝苗米"
        assertFalse(CropCalendarKnowledge.getCalendar("丝苗米（优质稻）").isEmpty());
        // 名称比库里短：被"黑皮冬瓜"包含
        assertFalse(CropCalendarKnowledge.getCalendar("冬瓜").isEmpty());
    }

    @Test
    @DisplayName("未收录与空输入返回空列表")
    void unknownCrop() {
        assertTrue(CropCalendarKnowledge.getCalendar("不存在作物").isEmpty());
        assertTrue(CropCalendarKnowledge.getCalendar("").isEmpty());
        assertTrue(CropCalendarKnowledge.getCalendar(null).isEmpty());
    }

    @Test
    @DisplayName("hasCalendar 与 getCalendar 口径一致")
    void hasCalendarConsistency() {
        assertTrue(CropCalendarKnowledge.hasCalendar("黑皮冬瓜"));
        assertTrue(CropCalendarKnowledge.hasCalendar("桂花鱼"));
        assertFalse(CropCalendarKnowledge.hasCalendar("不存在"));
        assertFalse(CropCalendarKnowledge.hasCalendar(null));
    }

    // ========== 今日任务命中 ==========

    @Test
    @DisplayName("播种当天（第 0 天）命中「播种/移栽」")
    void dayZeroTasks() {
        List<FarmTask> tasks = CropCalendarKnowledge.getTodayTasks("丝苗米", 0);
        assertEquals(1, tasks.size());
        assertEquals("播种/移栽", tasks.get(0).getTaskName());
        assertEquals("high", tasks.get(0).getPriority());
    }

    @Test
    @DisplayName("第 10 天命中「施分蘖肥」")
    void dayTenTasks() {
        List<FarmTask> tasks = CropCalendarKnowledge.getTodayTasks("丝苗米", 10);
        assertEquals(1, tasks.size());
        assertEquals("施分蘖肥", tasks.get(0).getTaskName());
    }

    @Test
    @DisplayName("浮动范围叠加：某些天会同时命中两个节点")
    void overlappingTasks() {
        // 第 7 天：返青期管理(day5±2 → 3~7) 命中；施分蘖肥(day10±2 → 8~12) 不命中
        assertEquals(1, CropCalendarKnowledge.getTodayTasks("丝苗米", 7).size());
        // 第 8 天：返青期(3~7) 刚结束，分蘖肥(8~12) 开始 → 恰好 1 个
        assertEquals(1, CropCalendarKnowledge.getTodayTasks("丝苗米", 8).size());
        // 节点稀疏的日子返回空
        assertTrue(CropCalendarKnowledge.getTodayTasks("丝苗米", 100).isEmpty());
    }

    // ========== FarmTask.matches 边界 ==========

    @Test
    @DisplayName("matches：浮动范围的闭区间边界")
    void matchesBoundaries() {
        FarmTask task = new FarmTask(10, 2, "测试", "描述", "high");
        assertTrue(task.matches(8), "下边界 day-dayRange 应命中");
        assertTrue(task.matches(12), "上边界 day+dayRange 应命中");
        assertTrue(task.matches(10), "中心日应命中");
        assertFalse(task.matches(7), "下边界外 1 天不应命中");
        assertFalse(task.matches(13), "上边界外 1 天不应命中");
    }

    @Test
    @DisplayName("matches：浮动为 0 表示仅当天命中")
    void matchesExactDayOnly() {
        FarmTask task = new FarmTask(5, 0, "测试", "描述", "high");
        assertTrue(task.matches(5));
        assertFalse(task.matches(4));
        assertFalse(task.matches(6));
    }
}
