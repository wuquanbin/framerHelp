package com.wqb.springboot;

import com.wqb.springboot.dto.WeatherResponseDto;
import com.wqb.springboot.service.AgriWeatherService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 聚合看板集成测试。
 * <p>
 * 会真正启动 Spring 容器并连本机 MySQL + Redis，因此默认不参与 `mvn test`：
 * 机器上没起 Redis 时这条会直接 RedisConnectionFailure，把整个构建打红，
 * 反而掩盖了纯函数测试的结果。要跑它请先把 MySQL/Redis 起好，然后：
 * <pre>mvnw -o test -Dtest=AgriWeatherServiceTests -DfailIfNoSpecifiedTests=false</pre>
 * 日常的正确性保证靠 util / dto / service 下的纯函数测试（无需任何外部依赖）。
 * </p>
 */
@SpringBootTest
@Tag("integration")
@Disabled("需要本机 MySQL + Redis 的集成测试，默认跳过；纯函数测试见 util/dto/service 下的各 Test")
class AgriWeatherServiceTests {

    @Autowired
    private AgriWeatherService agriWeatherService;

    @Test
    void shouldReturnDashboardOverview() {
        WeatherResponseDto.DashboardOverview overview = agriWeatherService.getDashboardOverview("baini");

        assertNotNull(overview);
        assertEquals("baini", overview.village().code());
        assertNotNull(overview.currentWeather());
        assertFalse(overview.forecast().isEmpty());
        assertFalse(overview.farmAdvices().isEmpty());
    }
}
