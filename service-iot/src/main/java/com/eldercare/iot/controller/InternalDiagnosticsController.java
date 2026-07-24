package com.eldercare.iot.controller;

import com.eldercare.common.core.domain.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * 内部诊断端点 — 检查数据库表结构是否就绪。
 * 仅供开发看板使用，不走 gateway。
 */
@RestController
@RequestMapping("/internal/iot")
public class InternalDiagnosticsController {

    private static final List<String> REQUIRED_TABLES = List.of(
            "iot_device_model",
            "iot_device_instance",
            "iot_device_binding",
            "iot_mq_outbox"
    );

    private static final List<String> OUTBOX_DEDUP_COLUMNS = List.of(
            "device_id",
            "source_message_id",
            "event_type"
    );

    private final JdbcTemplate jdbcTemplate;

    @Value("${mqtt.broker-url:tcp://localhost:1883}")
    private String mqttBrokerUrl;

    public InternalDiagnosticsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 检查 EMQX 是否可达（通过 Dashboard HTTP API）。
     * 浏览器无法直接请求 EMQX（CORS），由后端代理检测。
     */
    @GetMapping("/emqx-check")
    public R<Map<String, Object>> emqxCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        // 从 mqtt broker URL 推导 dashboard 地址：tcp://localhost:1883 → http://localhost:18083
        String dashboardUrl = mqttBrokerUrl
                .replace("tcp://", "http://")
                .replace(":1883", ":18083");
        String statusUrl = dashboardUrl + "/api/v5/status";

        boolean reachable = false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(statusUrl).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            reachable = code >= 200 && code < 400;
            conn.disconnect();
        } catch (Exception ignored) {
            // EMQX not reachable
        }

        result.put("reachable", reachable);
        result.put("dashboardUrl", dashboardUrl);
        return R.ok(result);
    }

    @GetMapping("/db-check")
    public R<Map<String, Object>> dbCheck() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 检查 4 张表是否存在
        List<String> existingTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );
        Map<String, Boolean> tableStatus = new LinkedHashMap<>();
        for (String table : REQUIRED_TABLES) {
            tableStatus.put(table, existingTables.contains(table));
        }
        result.put("tables", tableStatus);

        // 检查 outbox 表的 P0 去重三列
        Map<String, Boolean> outboxColumns = new LinkedHashMap<>();
        if (existingTables.contains("iot_mq_outbox")) {
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'iot_mq_outbox'",
                    String.class
            );
            for (String col : OUTBOX_DEDUP_COLUMNS) {
                outboxColumns.put(col, columns.contains(col));
            }
        } else {
            for (String col : OUTBOX_DEDUP_COLUMNS) {
                outboxColumns.put(col, false);
            }
        }
        result.put("outboxDedupColumns", outboxColumns);

        // 检查复合唯一约束 uk_outbox_dedup
        Boolean dedupConstraint = false;
        if (existingTables.contains("iot_mq_outbox")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                    "WHERE table_schema = 'public' AND table_name = 'iot_mq_outbox' " +
                    "AND constraint_type = 'UNIQUE' AND constraint_name = 'uk_outbox_dedup'",
                    Integer.class
            );
            dedupConstraint = count != null && count > 0;
        }
        result.put("outboxDedupConstraint", dedupConstraint);

        // 检查 version 列（乐观锁）
        Boolean modelVersionColumn = false;
        if (existingTables.contains("iot_device_model")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'iot_device_model' AND column_name = 'version'",
                    Integer.class
            );
            modelVersionColumn = count != null && count > 0;
        }
        result.put("modelVersionColumn", modelVersionColumn);

        // 汇总
        boolean allTablesOk = tableStatus.values().stream().allMatch(Boolean::booleanValue);
        boolean allOutboxColsOk = outboxColumns.values().stream().allMatch(Boolean::booleanValue);
        boolean allOk = allTablesOk && allOutboxColsOk && dedupConstraint && modelVersionColumn;
        result.put("allOk", allOk);

        return R.ok(result);
    }
}
