package com.wqb.springboot.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作物种植日历知识库
 * <p>
 * 内置主要作物的农事活动节点，根据播种/移栽日期自动计算"今天该做什么"。
 * 每个节点包含：天数（从播种日算起）、活动名称、详细描述、优先级。
 * </p>
 * <p>
 * 后续可扩展为数据库配置，支持管理员自定义农事日历。
 * </p>
 */
public class CropCalendarKnowledge {

    /**
     * 农事节点
     */
    public static class FarmTask {
        private final int day;            // 从播种日算起第几天
        private final int dayRange;       // 前后浮动天数（±dayRange 天都提醒）
        private final String taskName;     // 活动名称
        private final String description;  // 详细描述
        private final String priority;     // 优先级：high/medium/low

        public FarmTask(int day, int dayRange, String taskName, String description, String priority) {
            this.day = day;
            this.dayRange = dayRange;
            this.taskName = taskName;
            this.description = description;
            this.priority = priority;
        }

        public int getDay() { return day; }
        public int getDayRange() { return dayRange; }
        public String getTaskName() { return taskName; }
        public String getDescription() { return description; }
        public String getPriority() { return priority; }

        /**
         * 判断当前天数是否命中该节点（含浮动范围）
         */
        public boolean matches(int currentDay) {
            return currentDay >= day - dayRange && currentDay <= day + dayRange;
        }
    }

    // 作物名称 -> 农事节点列表
    private static final Map<String, List<FarmTask>> CALENDAR = new HashMap<>();

    static {
        // ========== 丝苗米（水稻）农事日历 ==========
        CALENDAR.put("丝苗米", Arrays.asList(
            new FarmTask(0, 1, "播种/移栽", "完成丝苗米播种或秧苗移栽，确保秧苗质量，移栽深度2-3cm", "high"),
            new FarmTask(5, 2, "返青期管理", "秧苗返青，检查成活情况，及时补苗，保持浅水层3-5cm", "medium"),
            new FarmTask(10, 2, "施分蘖肥", "分蘖初期追施分蘖肥，每亩尿素8-10kg，促进早分蘖", "high"),
            new FarmTask(15, 2, "分蘖盛期", "分蘖盛期，注意防治稻飞虱、纹枯病，保持浅水灌溉", "medium"),
            new FarmTask(22, 3, "晒田控苗", "分蘖末期晒田控苗，排水晒田7-10天，抑制无效分蘖", "high"),
            new FarmTask(30, 2, "拔节期管理", "拔节期复水，追施穗肥，每亩复合肥10-15kg", "high"),
            new FarmTask(38, 2, "孕穗期", "孕穗期保持浅水层，注意防治稻瘟病、稻曲病", "medium"),
            new FarmTask(45, 2, "抽穗扬花期", "抽穗扬花期，保持浅水层，高温天气注意深水降温", "high"),
            new FarmTask(55, 3, "灌浆期", "灌浆期干湿交替灌溉，切忌断水过早，注意防治稻飞虱", "medium"),
            new FarmTask(68, 3, "成熟期", "稻谷成熟，及时收割，收割前7天断水，确保稻谷品质", "high"),
            new FarmTask(75, 2, "晾晒入库", "稻谷晾晒至含水量13%以下，清选入库，注意防潮防虫", "low")
        ));

        // 早稻、晚稻复用丝苗米日历
        CALENDAR.put("早稻", CALENDAR.get("丝苗米"));
        CALENDAR.put("晚稻", CALENDAR.get("丝苗米"));

        // ========== 黑皮冬瓜农事日历 ==========
        CALENDAR.put("黑皮冬瓜", Arrays.asList(
            new FarmTask(0, 1, "播种育苗", "黑皮冬瓜播种育苗，苗龄30-35天，注意保温保湿", "high"),
            new FarmTask(30, 3, "定植", "幼苗定植，株距80-100cm，浇足定根水，覆盖地膜", "high"),
            new FarmTask(40, 3, "伸蔓期管理", "伸蔓期及时整枝引蔓，留主蔓和1-2条侧蔓，追施提苗肥", "medium"),
            new FarmTask(50, 3, "开花期", "开花期人工辅助授粉，上午8-10点进行，注意防治蚜虫", "high"),
            new FarmTask(60, 3, "坐果期", "坐果期每株留1-2个果，追施膨果肥，每亩复合肥20kg", "high"),
            new FarmTask(75, 5, "果实膨大期", "果实膨大期保持土壤湿润，注意防治疫病、白粉病", "medium"),
            new FarmTask(95, 5, "采收期", "冬瓜成熟采收，果皮变硬、颜色变深时采收，轻拿轻放", "high")
        ));

        // ========== 粉葛农事日历 ==========
        CALENDAR.put("粉葛", Arrays.asList(
            new FarmTask(0, 2, "种植", "粉葛种植，选择健壮种茎，株距60cm，种植深度10-15cm", "high"),
            new FarmTask(20, 5, "苗期管理", "苗期及时补苗，追施提苗肥，每亩尿素5kg，注意除草", "medium"),
            new FarmTask(45, 5, "搭架引蔓", "苗高30cm时搭架引蔓，架高1.5-2m，及时绑蔓", "high"),
            new FarmTask(70, 7, "块根膨大期", "块根膨大期追施膨大肥，每亩钾肥15kg，保持土壤湿润", "high"),
            new FarmTask(100, 10, "生长后期", "生长后期控制氮肥，注意防治葛根锈病、叶斑病", "medium"),
            new FarmTask(150, 15, "采收期", "粉葛成熟采收，块根充分膨大后采收，注意避免损伤", "high")
        ));

        // ========== 单丛茶农事日历 ==========
        CALENDAR.put("单丛茶", Arrays.asList(
            new FarmTask(0, 3, "种植/定植", "单丛茶苗定植，株距1.2m，行距1.5m，浇足定根水", "high"),
            new FarmTask(30, 7, "幼龄期管理", "幼龄茶树及时除草松土，追施提苗肥，注意抗旱保苗", "medium"),
            new FarmTask(90, 15, "定型修剪", "幼龄茶树定型修剪，促进分枝，培养丰产树冠", "high"),
            new FarmTask(180, 15, "春茶采摘", "春茶采摘，一芽二叶为标准，及时采摘，注意留叶养树", "high"),
            new FarmTask(240, 15, "夏茶管理", "夏茶采摘后追施夏肥，每亩复合肥20kg，注意防治小绿叶蝉", "medium"),
            new FarmTask(300, 15, "秋茶采摘", "秋茶采摘，品质最佳，注意精细采摘，提升茶叶品质", "high"),
            new FarmTask(360, 15, "冬季管理", "冬季深翻改土，施足基肥，每亩有机肥500kg，修剪清园", "high")
        ));

        // ========== 荔枝农事日历 ==========
        CALENDAR.put("荔枝", Arrays.asList(
            new FarmTask(0, 7, "定植", "荔枝苗定植，株距5-6m，挖大穴施足基肥，浇足定根水", "high"),
            new FarmTask(60, 15, "幼树管理", "幼树及时追肥，每次新梢期追施速效肥，注意整形修剪", "medium"),
            new FarmTask(180, 15, "秋梢培养", "培养结果母枝，秋梢期追施壮梢肥，注意防治蒂蛀虫", "high"),
            new FarmTask(270, 15, "花芽分化期", "花芽分化期控制水肥，促进花芽分化，注意防治霜疫霉病", "high"),
            new FarmTask(330, 15, "开花期", "开花期放蜂授粉，雨天摇花防沤花，注意防治荔枝蝽象", "high"),
            new FarmTask(365, 15, "果实发育期", "果实发育期追施壮果肥，注意保果，防治蒂蛀虫、霜疫霉病", "high"),
            new FarmTask(420, 15, "采收期", "荔枝成熟采收，果皮变红时采收，轻采轻放，及时上市", "high"),
            new FarmTask(450, 15, "采后管理", "采后及时修剪、施肥，恢复树势，清园防治病虫害", "medium")
        ));

        // ========== 兰花（蝴蝶兰/百合花/年桔）通用花卉农事日历 ==========
        List<FarmTask> flowerCalendar = Arrays.asList(
            new FarmTask(0, 3, "种植/上盆", "花卉上盆种植，选择疏松透气基质，浇透定根水，遮阴缓苗", "high"),
            new FarmTask(15, 7, "缓苗期管理", "缓苗期保持基质湿润，避免强光直射，注意通风", "medium"),
            new FarmTask(30, 7, "生长期追肥", "生长期追施稀薄液肥，每10-15天一次，注意氮磷钾配比", "medium"),
            new FarmTask(60, 10, "花芽分化期", "花芽分化期增加光照，控制温度，追施磷钾肥", "high"),
            new FarmTask(90, 10, "开花期", "开花期控制浇水，避免花朵沾水，注意防治灰霉病", "high"),
            new FarmTask(120, 10, "花后管理", "花后及时剪除残花，追施恢复肥，注意病虫害防治", "medium")
        );
        CALENDAR.put("蝴蝶兰", flowerCalendar);
        CALENDAR.put("百合花", flowerCalendar);
        CALENDAR.put("兰花", flowerCalendar);
        CALENDAR.put("年桔", Arrays.asList(
            new FarmTask(0, 3, "上盆定植", "年桔上盆，选择肥沃疏松土壤，浇足定根水", "high"),
            new FarmTask(20, 7, "春梢期", "春梢期追施促梢肥，及时整形修剪，培养树冠", "medium"),
            new FarmTask(60, 10, "夏梢期", "夏梢期控制氮肥，防治潜叶蛾、红蜘蛛", "medium"),
            new FarmTask(100, 10, "秋梢期", "培养结果母枝，追施壮梢肥，注意防治锈蜘蛛", "high"),
            new FarmTask(150, 15, "花芽分化期", "花芽分化期控水促花，追施磷钾肥", "high"),
            new FarmTask(180, 15, "开花期", "开花期放蜂授粉，雨天摇花，注意防治花蕾蛆", "high"),
            new FarmTask(220, 15, "果实发育期", "果实发育期追施壮果肥，注意保果，防治炭疽病", "high"),
            new FarmTask(280, 15, "成熟期/观赏期", "果实成熟转色，进入观赏期，控制浇水，延长观赏期", "high")
        ));

        // ========== 蔬菜通用（菜心/生菜/莲藕） ==========
        List<FarmTask> veggieCalendar = Arrays.asList(
            new FarmTask(0, 1, "播种", "蔬菜播种，选择适宜品种，浇足底水，覆盖薄土", "high"),
            new FarmTask(5, 2, "出苗期", "出苗期保持土壤湿润，及时间苗，去除弱苗病苗", "medium"),
            new FarmTask(15, 3, "生长期追肥", "生长期追施速效氮肥，每亩尿素5-8kg，注意浇水", "medium"),
            new FarmTask(25, 3, "莲座期/旺盛期", "旺盛生长期，追施复合肥，注意防治菜青虫、蚜虫", "high"),
            new FarmTask(35, 3, "采收期", "蔬菜成熟采收，及时采收，保证品质，采收后追施接力肥", "high")
        );
        CALENDAR.put("菜心", veggieCalendar);
        CALENDAR.put("生菜", veggieCalendar);
        CALENDAR.put("莲藕", Arrays.asList(
            new FarmTask(0, 3, "种植", "莲藕种植，选择健康藕种，株距1.5m，种植深度10-15cm", "high"),
            new FarmTask(20, 5, "萌芽期", "萌芽期保持浅水层5-10cm，注意保温，及时清除杂草", "medium"),
            new FarmTask(45, 7, "立叶期", "立叶期追施立叶肥，每亩复合肥15kg，水位加深至15-20cm", "high"),
            new FarmTask(70, 7, "开花期", "开花期追施壮藕肥，每亩钾肥10kg，注意防治蚜虫、斜纹夜蛾", "high"),
            new FarmTask(100, 10, "结藕期", "结藕期保持水位，注意防治腐败病，避免损伤叶片", "medium"),
            new FarmTask(140, 10, "采收期", "莲藕成熟采收，叶片枯黄后采收，注意避免损伤藕体", "high")
        ));

        // ========== 水产（桂花鱼） ==========
        CALENDAR.put("桂花鱼", Arrays.asList(
            new FarmTask(0, 3, "放苗", "桂花鱼苗种放养，选择健康苗种，每亩放养800-1200尾", "high"),
            new FarmTask(10, 5, "驯化期", "驯化期投喂适口饵料鱼，注意水质管理，保持溶氧充足", "high"),
            new FarmTask(30, 7, "生长期", "生长期定时定量投喂，每天投喂2-3次，注意防治寄生虫病", "medium"),
            new FarmTask(60, 10, "快速生长期", "快速生长期加大投喂量，定期换水，保持水质清新", "medium"),
            new FarmTask(90, 10, "育成期", "育成期注意分级饲养，避免大小悬殊，防治细菌性疾病", "high"),
            new FarmTask(120, 15, "收获期", "桂花鱼达到商品规格，及时捕捞上市，注意操作避免损伤", "high")
        ));
    }

    /**
     * 获取指定作物的农事日历
     *
     * @param cropName 作物名称
     * @return 农事节点列表，未收录的作物返回空列表
     */
    public static List<FarmTask> getCalendar(String cropName) {
        if (cropName == null || cropName.isBlank()) {
            return new ArrayList<>();
        }
        // 精确匹配
        List<FarmTask> tasks = CALENDAR.get(cropName);
        if (tasks != null) {
            return tasks;
        }
        // 模糊匹配
        for (Map.Entry<String, List<FarmTask>> entry : CALENDAR.entrySet()) {
            if (cropName.contains(entry.getKey()) || entry.getKey().contains(cropName)) {
                return entry.getValue();
            }
        }
        return new ArrayList<>();
    }

    /**
     * 根据当前天数获取今日农事任务
     *
     * @param cropName   作物名称
     * @param currentDay 当前天数（从播种日算起）
     * @return 今日命中的农事任务列表
     */
    public static List<FarmTask> getTodayTasks(String cropName, int currentDay) {
        List<FarmTask> all = getCalendar(cropName);
        List<FarmTask> result = new ArrayList<>();
        for (FarmTask task : all) {
            if (task.matches(currentDay)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 判断作物是否已收录农事日历
     */
    public static boolean hasCalendar(String cropName) {
        return !getCalendar(cropName).isEmpty();
    }
}
