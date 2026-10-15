package com.wqb.springboot.controller;

import com.wqb.springboot.config.AmapConfig;
import com.wqb.springboot.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 高德地图搜索代理控制器
 * <p>
 * 为后台管理系统"地图选址"功能提供地点搜索接口，
 * API密钥保存在服务端，避免暴露到前端。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/amap")
public class AmapController {

    private static final Logger log = LoggerFactory.getLogger(AmapController.class);

    private final AmapConfig amapConfig;
    private final RestTemplate restTemplate;

    public AmapController(AmapConfig amapConfig, RestTemplate restTemplate) {
        this.amapConfig = amapConfig;
        this.restTemplate = restTemplate;
    }

    /**
     * 地点文本搜索
     *
     * @param keywords 搜索关键词
     * @param city     可选，城市名（如"佛山"），限制搜索范围
     * @return 搜索结果列表，每项含名称、地址、经纬度、行政区编码
     */
    @GetMapping("/place-search")
    public ApiResponse<List<Map<String, Object>>> placeSearch(
            @RequestParam String keywords,
            @RequestParam(required = false) String city) {

        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(amapConfig.getBaseUrl() + "/v3/place/text")
                    .queryParam("key", amapConfig.getApiKey())
                    .queryParam("keywords", keywords)
                    .queryParam("offset", 10)
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .queryParam("output", "JSON");
            if (city != null && !city.isBlank()) {
                builder.queryParam("city", city);
                builder.queryParam("citylimit", "true");
            }
            URI uri = builder.build().toUri();

            log.info("Amap place search: keywords={}, city={}", keywords, city);
            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            List<Map<String, Object>> results = new ArrayList<>();
            if (response != null && "1".equals(response.get("status"))) {
                Object poisObj = response.get("pois");
                if (poisObj instanceof List<?> pois) {
                    for (Object poi : pois) {
                        if (!(poi instanceof Map<?, ?> p)) continue;
                        Map<String, Object> item = Map.of(
                                "name", p.get("name") != null ? p.get("name") : "",
                                "address", p.get("address") != null ? p.get("address") : "",
                                "location", p.get("location") != null ? p.get("location") : "",
                                "adcode", p.get("adcode") != null ? p.get("adcode") : "",
                                "province", p.get("pname") != null ? p.get("pname") : "",
                                "city", p.get("cityname") != null ? p.get("cityname") : "",
                                "district", p.get("adname") != null ? p.get("adname") : ""
                        );
                        results.add(item);
                    }
                }
            } else {
                log.warn("Amap place search failed: {}", response);
            }
            return ApiResponse.ok(results);
        } catch (Exception e) {
            log.error("Amap place search error: {}", e.getMessage());
            return ApiResponse.fail("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 静态地图图片代理（降落后的地图显示）：按经纬度在高德静态地图上画标记点，返回 PNG 二进制，密钥留在服务端。
     * <p>标记 label 不支持非 ASCII 字符（中文会导致返回 JSON 错误），所以统一用字母 "A"。</p>
     *
     * @param location 经纬度，格式 "lng,lat"
     * @param zoom     缩放级别，默认 15（街道级）
     */
    @GetMapping("/static-map")
    public ResponseEntity<byte[]> staticMap(
            @RequestParam String location,
            @RequestParam(defaultValue = "15") int zoom) {

        try {
            // 高德 markers 参数格式：size,color,label:lng,lat（label 仅支持 ASCII）
            String markers = "mid,0xFF4444,A:" + location;

            URI uri = UriComponentsBuilder
                    .fromUriString(amapConfig.getBaseUrl() + "/v3/staticmap")
                    .queryParam("key", amapConfig.getApiKey())
                    .queryParam("location", location)
                    .queryParam("zoom", zoom)
                    .queryParam("size", "440*280")
                    .queryParam("scale", "2")
                    .queryParam("markers", markers)
                    .build(true)
                    .toUri();

            log.info("Amap static map: location={}, zoom={}", location, zoom);
            byte[] image = restTemplate.getForObject(uri, byte[].class);

            // 高德出错时返回的是 JSON 而非图片：校验 PNG 魔数（0x89 'P' 'N' 'G'）
            if (image == null || image.length < 8
                    || (image[0] & 0xFF) != 0x89 || image[1] != 'P' || image[2] != 'N' || image[3] != 'G') {
                log.warn("Amap static map returned non-PNG content, length={}",
                        image == null ? 0 : image.length);
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(image);
        } catch (Exception e) {
            log.error("Amap static map error: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
