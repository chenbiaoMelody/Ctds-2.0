package com.ctds.common.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 存证提交入口（WBS-3.8.3 hifi §1，Q7-A：afterCommit 触发 + fail-open）。
 *
 * <p>事务内调用 → 注册 afterCommit（提交后发送、回滚零发送）；无事务 → 立即发送。
 * 任何失败（网络异常 / 非 200 / code 非 "0" / JSON 解析失败）只告警，不抛出、不影响业务；
 * 客户端不重试、不排队、不做去重（去重由存证侧三元组唯一键承担，规格 B2-2）。</p>
 *
 * <p>日志只含环节 / 单号 / 操作类型，不含 DID、数据原文与堆栈（规格 B1-4 白名单）。</p>
 */
public final class EvidenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRecorder.class);
    private static final String PATH = "/api/v1/evidence/internal/records";
    private static final String SUCCESS_CODE = "0";

    private final EvidenceClientProperties properties;
    private final String serviceName;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EvidenceRecorder(final EvidenceClientProperties properties, final String serviceName) {
        this(properties, serviceName, new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build());
    }

    EvidenceRecorder(final EvidenceClientProperties properties, final String serviceName,
            final ObjectMapper objectMapper, final HttpClient httpClient) {
        this.properties = properties;
        this.serviceName = serviceName;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 业务事务内调用：有事务 → 提交后发送；无事务 → 立即发送；任何失败不抛出。
     *
     * @param event 存证事件（null 静默忽略——埋点不得成为业务失败原因）
     */
    public void record(final EvidenceEvent event) {
        if (event == null) {
            return;
        }
        if (!properties.isEnabled()) {
            warnSkipped("埋点已关闭", event);
            return;
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            warnSkipped("存证服务地址未配置", event);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务已提交：回调异常会抛给业务调用方，必须在此吞掉（hifi §1.4）
                    try {
                        send(event);
                    } catch (final Throwable ignored) {
                        warnFailed(event);
                    }
                }
            });
            return;
        }
        send(event);
    }

    private void send(final EvidenceEvent event) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + PATH))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Ctds-Subject", serviceName)
                    .header("X-Ctds-Roles", properties.getRoles())
                    .POST(HttpRequest.BodyPublishers.ofString(payload(event), StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !isSuccess(response.body())) {
                warnFailed(event);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            warnFailed(event);
        } catch (final Exception e) {
            // IOException / 运行时异常 / JSON 解析失败：一律降级为告警（fail-open）
            warnFailed(event);
        }
    }

    private String payload(final EvidenceEvent event) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("stageType", event.stageType());
        node.put("businessNo", event.businessNo());
        node.put("operationType", event.operationType());
        node.put("subjectDid", event.subjectDid());
        node.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        node.put("actorSubjectNo", event.actorSubjectNo());
        return node.toString();
    }

    private boolean isSuccess(final String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            final JsonNode node = objectMapper.readTree(body);
            return node != null && SUCCESS_CODE.equals(node.path("code").asText());
        } catch (final Exception e) {
            return false;
        }
    }

    private static void warnSkipped(final String reason, final EvidenceEvent event) {
        log.warn("存证提交跳过（{}）: stage={}, businessNo={}, operationType={}", reason,
                event.stageType(), event.businessNo(), event.operationType());
    }

    private static void warnFailed(final EvidenceEvent event) {
        log.warn("存证提交失败: stage={}, businessNo={}, operationType={}", event.stageType(),
                event.businessNo(), event.operationType());
    }
}
