package com.eldercare.common.audit.aspect;

import com.eldercare.common.audit.annotation.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Aspect
@Slf4j
public class AuditLogAspect {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 1. 获取请求对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;

        // 2. 收集请求的基础属性
        String url = request != null ? request.getRequestURI() : "Unknown";
        String method = request != null ? request.getMethod() : "Unknown";
        String clientIp = request != null ? getClientIp(request) : "Unknown";
        String userAgent = request != null ? request.getHeader("User-Agent") : "Unknown";
        
        // 从网关透传的请求头提取当前操作人身份
        String userId = request != null ? request.getHeader("X-User-Id") : null;
        String username = request != null ? request.getHeader("X-User-Name") : null;

        // 3. 解析拦截的方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = signature.getMethod();
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = targetMethod.getName();

        // 4. 解析并脱敏入参参数
        String paramsJson = "";
        if (auditLog.saveParams()) {
            paramsJson = getParamsJson(joinPoint, signature);
        }

        Object result = null;
        Throwable exception = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            exception = e;
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            
            // 5. 格式化记录日志
            Map<String, Object> auditMap = new HashMap<>();
            auditMap.put("operation", auditLog.value());
            auditMap.put("userId", userId != null ? userId : "Anonymous");
            auditMap.put("username", username != null ? username : "Anonymous");
            auditMap.put("clientIp", clientIp);
            auditMap.put("url", url);
            auditMap.put("httpMethod", method);
            auditMap.put("userAgent", userAgent);
            auditMap.put("class", className);
            auditMap.put("method", methodName);
            auditMap.put("duration", duration + "ms");
            
            if (auditLog.saveParams()) {
                auditMap.put("params", paramsJson);
            }
            if (exception != null) {
                auditMap.put("status", "FAIL");
                auditMap.put("error", exception.getMessage());
            } else {
                auditMap.put("status", "SUCCESS");
                if (auditLog.saveResult() && result != null) {
                    try {
                        auditMap.put("result", objectMapper.writeValueAsString(result));
                    } catch (Exception e) {
                        auditMap.put("result", "[Serialization Failed]");
                    }
                }
            }

            try {
                log.info("[AUDIT_LOG] {}", objectMapper.writeValueAsString(auditMap));
            } catch (Exception e) {
                log.error("Failed to print audit log", e);
            }
        }
    }

    private String getParamsJson(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        try {
            Object[] args = joinPoint.getArgs();
            String[] parameterNames = signature.getParameterNames();
            if (args == null || args.length == 0) {
                return "";
            }

            List<Object> safeArgs = new ArrayList<>();
            for (Object arg : args) {
                if (arg instanceof ServletRequest || arg instanceof ServletResponse || arg instanceof MultipartFile) {
                    continue; // 忽略 Servlet 上下文对象及文件上传对象
                }
                safeArgs.add(arg);
            }
            return objectMapper.writeValueAsString(safeArgs);
        } catch (Exception e) {
            return "[Error extracting params]";
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
