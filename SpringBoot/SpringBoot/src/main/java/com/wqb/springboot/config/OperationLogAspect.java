package com.wqb.springboot.config;

import com.wqb.springboot.entity.OperationLog;
import com.wqb.springboot.repository.OperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面配置类
 * <p>
 * 通过AOP拦截管理端控制器和AI对话控制器的方法调用，
 * 自动记录操作人、操作类型、所属模块、请求参数、IP地址等日志信息，
 * 用于系统审计和操作追踪。
 * </p>
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    private final OperationLogRepository operationLogRepository;

    public OperationLogAspect(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    /**
     * 环绕通知：拦截Admin控制器和AI对话控制器的方法调用，记录操作日志
     * <p>
     * 切点匹配 Admin* 和 AiChatController 下的所有方法，
     * 记录方法执行状态（成功/失败）及耗时，并在finally中异步保存日志。
     * </p>
     *
     * @param joinPoint 切面连接点，包含目标方法的信息
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的异常
     */
    @Around("execution(* com.wqb.springboot.controller.Admin*.*(..)) || " +
            "execution(* com.wqb.springboot.controller.AiChatController.*(..)) || " +
            "execution(* com.wqb.springboot.controller.HumanChatController.*(..))")
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis(); // 记录方法开始执行时间
        String methodName = ((MethodSignature) joinPoint.getSignature()).getMethod().getName(); // 获取目标方法名
        Object result = null;
        int status = 1; // 操作状态：1-成功，0-失败
        try {
            result = joinPoint.proceed(); // 执行目标方法
            return result;
        } catch (Throwable e) {
            status = 0; // 方法执行异常，标记为失败
            throw e;
        } finally {
            try {
                saveLog(joinPoint, methodName, status, System.currentTimeMillis() - startTime); // 保存操作日志（含耗时）
            } catch (Exception e) {
                // 存日志失败不能影响业务，但也不能静默 —— 审计是合规要求，丢日志这件事本身要能被发现。
                log.warn("保存操作日志失败，method={}: {}", methodName, e.getMessage());
            }
        }
    }

    /**
     * 保存操作日志到数据库
     * <p>
     * 根据安全上下文获取操作人，根据请求URL判断操作类型和所属模块，
     * 并记录请求参数和客户端IP地址。
     * </p>
     *
     * @param joinPoint  切面连接点
     * @param methodName 目标方法名
     * @param status     操作状态（1-成功，0-失败）
     * @param duration   方法执行耗时（毫秒）
     */
    private void saveLog(ProceedingJoinPoint joinPoint, String methodName, int status, long duration) {
        OperationLog log = new OperationLog();

        // 操作人：优先从安全上下文获取已登录用户，登录方法则从参数提取
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String name = auth.getName();
            if (name != null && name.length() > 100) name = name.substring(0, 100);
            log.setOperatorName(name);
        } else if ("login".equals(methodName)) {
            Object[] args = joinPoint.getArgs();
            if (args.length > 0 && args[0] != null) {
                String name = args[0].toString();
                if (name.length() > 100) name = name.substring(0, 100);
                log.setOperatorName(name);
            }
        }

        // 获取请求URL，用于精确判断模块
        String requestUrl = "";
        String httpMethod = "";
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            requestUrl = request.getRequestURI(); // 请求URI
            httpMethod = request.getMethod(); // HTTP方法
        }

        // 农民端人工客服公开接口(online-experts/my-active-session)高频轮询，不记录，避免日志噪音
        if (requestUrl.startsWith("/api/human-chat")) {
            return;
        }

        // 操作类型：基于HTTP方法 + 方法名判断
        if ("POST".equals(httpMethod) && !requestUrl.contains("/chat")) {
            log.setOperationType("CREATE");
        } else if ("PUT".equals(httpMethod) || "PATCH".equals(httpMethod)) {
            log.setOperationType("UPDATE");
        } else if ("DELETE".equals(httpMethod)) {
            log.setOperationType("DELETE");
        } else if ("login".equals(methodName)) {
            log.setOperationType("LOGIN");
        } else if (requestUrl.contains("/chat")) {
            log.setOperationType("CHAT");
        } else {
            log.setOperationType("QUERY");
        }

        // 模块：基于请求URL路径判断（优先匹配具体路径）
        if (requestUrl.contains("/ai/chat")) {
            log.setModule("ai");
        } else if (requestUrl.contains("/villages")) {
            log.setModule("village");
        } else if (requestUrl.contains("/warnings")) {
            log.setModule("warning");
        } else if (requestUrl.contains("/crops")) {
            log.setModule("crop");
        } else if (requestUrl.contains("/disasters")) {
            log.setModule("disaster");
        } else if (requestUrl.contains("/farm-advices")) {
            log.setModule("farm");
        } else if (requestUrl.contains("/farm-reports")) {
            log.setModule("farm-report");
        } else if (requestUrl.contains("/admins") && !requestUrl.contains("/admin/")) {
            log.setModule("admin");
        } else if (requestUrl.contains("/users")) {
            log.setModule("user");
        } else if (requestUrl.contains("/system-params")) {
            log.setModule("system");
        } else if (requestUrl.contains("/feedbacks")) {
            log.setModule("feedback");
        } else if (requestUrl.contains("/life-index")) {
            log.setModule("weather");
        } else if (requestUrl.contains("/dashboard")) {
            log.setModule("dashboard");
        } else if (requestUrl.contains("/login") || requestUrl.contains("/admin/info")) {
            log.setModule("auth");
        } else if (requestUrl.contains("/operation-logs")) {
            log.setModule("log");
        } else {
            log.setModule("system");
        }

        // 描述：友好的中文描述
        String desc = buildDescription(httpMethod, requestUrl, methodName);
        log.setDescription(desc);

        // 请求参数（截取前500字符，防止过长）
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0) {
            String params = java.util.Arrays.toString(args);
            if (params.length() > 500) params = params.substring(0, 500);
            log.setRequestParams(params);
        }

        // IP：依次从代理头和远程地址获取客户端真实IP
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For"); // 优先取反向代理转发的IP
            if (ip == null || ip.isEmpty()) ip = request.getHeader("X-Real-IP"); // Nginx等代理设置的真实IP头
            if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr(); // 兜底取远程地址
            log.setIp(ip);
        }

        log.setStatus(status); // 设置操作状态
        operationLogRepository.save(log); // 持久化日志
    }

    /**
     * 根据HTTP方法、请求URL和方法名构建友好的中文操作描述
     *
     * @param httpMethod  HTTP请求方法（GET/POST/PUT/DELETE等）
     * @param requestUrl  请求URL路径
     * @param methodName  目标方法名
     * @return 中文操作描述，如"新增区域"、"删除预警"等
     */
    private String buildDescription(String httpMethod, String requestUrl, String methodName) {
        // 登录
        if ("login".equals(methodName)) return "管理员登录";

        // 确定资源名称
        String resource = "数据";
        if (requestUrl.contains("/villages")) resource = "村镇";
        else if (requestUrl.contains("/warnings")) resource = "预警";
        else if (requestUrl.contains("/crops")) resource = "作物";
        else if (requestUrl.contains("/disasters")) resource = "灾害";
        else if (requestUrl.contains("/farm-advices")) resource = "农事建议";
        else if (requestUrl.contains("/farm-reports")) resource = "农情上报";
        else if (requestUrl.contains("/farm-responses")) resource = "帮扶响应";
        else if (requestUrl.contains("/assistance-records")) resource = "帮扶记录";
        else if (requestUrl.contains("/admins")) resource = "管理员";
        else if (requestUrl.contains("/users")) resource = "用户";
        else if (requestUrl.contains("/system-params")) resource = "系统参数";
        else if (requestUrl.contains("/feedbacks")) resource = "反馈";
        else if (requestUrl.contains("/life-index")) resource = "生活指数";
        else if (requestUrl.contains("/ai") || requestUrl.contains("/chat")) resource = "AI对话";
        else if (requestUrl.contains("/dashboard")) resource = "数据概览";
        else if (requestUrl.contains("/operation-logs")) resource = "操作日志";

        // 确定操作
        if ("POST".equals(httpMethod) && !requestUrl.contains("/chat")) return "新增" + resource;
        if ("PUT".equals(httpMethod) || "PATCH".equals(httpMethod)) return "编辑" + resource;
        if ("DELETE".equals(httpMethod)) return "删除" + resource;
        if (requestUrl.contains("/chat")) return "AI对话测试";
        return "查看" + resource;
    }
}
