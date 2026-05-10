package com.xspaceagi.custompage.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.custompage.domain.gateway.AiAgentClient;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.keepalive.IKeepAliveService;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.service.ICustomPageChatDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageConversationDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageChatDomainServiceImpl implements ICustomPageChatDomainService {

    @jakarta.annotation.Resource
    private AiAgentClient aiAgentClient;
    @jakarta.annotation.Resource
    private IAgentRpcService agentRpcService;
    @jakarta.annotation.Resource
    private IKeepAliveService keepAliveService;
    @jakarta.annotation.Resource
    private PageFileBuildClient pageFileBuildClient;
    @jakarta.annotation.Resource
    private SpacePermissionService spacePermissionService;
    @jakarta.annotation.Resource
    private ModelApplicationService modelApplicationService;
    @jakarta.annotation.Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @jakarta.annotation.Resource
    private ICustomPageProxyPathService customPageProxyPathService;
    @jakarta.annotation.Resource
    private ICustomPageConversationDomainService customPageConversationDomainService;

    private final static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public SseEmitter startAgentSessionSse(String sessionId, Long projectId, UserContext userContext) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (projectId == null || projectId <= 0) {
            throw new IllegalArgumentException("projectId is required");
        }

        SseEmitter emitter = new SseEmitter(10L * 60 * 1000); // 超时时间10分钟
        List<Map<String, Object>> assistantEvents = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> assistantRequestIdRef = new AtomicReference<>();
        AtomicBoolean persisted = new AtomicBoolean(false);

        emitter.onCompletion(() -> {
            log.info("[Domain] SSE connection completed, session Id={}", sessionId);
            persistAssistantConversationOnce(projectId, sessionId, assistantEvents, assistantRequestIdRef.get(), userContext, persisted);
        });

        emitter.onTimeout(() -> {
            log.warn("[Domain] SSE connection timeout, session Id={}", sessionId);
            persistAssistantConversationOnce(projectId, sessionId, assistantEvents, assistantRequestIdRef.get(), userContext, persisted);
        });

        emitter.onError((throwable) -> {
            log.error("[Domain] SSE connection error, session Id={}", sessionId, throwable);
            persistAssistantConversationOnce(projectId, sessionId, assistantEvents, assistantRequestIdRef.get(), userContext, persisted);
        });

        aiAgentClient.subscribeSessionSse(sessionId, emitter, projectId, userContext, (eventName, data) -> {
            collectAssistantEvent(eventName, data, assistantEvents, assistantRequestIdRef);
            if (isFinalEvent(eventName, data)) {
                persistAssistantConversationOnce(projectId, sessionId, assistantEvents, assistantRequestIdRef.get(),
                        userContext, persisted);
            }
        });
        return emitter;
    }

    private void collectAssistantEvent(String eventName, String data, List<Map<String, Object>> assistantEvents,
                                       AtomicReference<String> assistantRequestIdRef) {
        if (!shouldPersist(eventName)) {
            return;
        }
        Map<String, Object> eventRecord = new HashMap<>();
        eventRecord.put("event", eventName);
        eventRecord.put("data", parseRawData(data));
        assistantEvents.add(eventRecord);

        String requestId = extractRequestId(data);
        if (StringUtils.isNotBlank(requestId)) {
            assistantRequestIdRef.set(requestId);
        }
    }

    private void persistAssistantConversationOnce(Long projectId, String sessionId, List<Map<String, Object>> assistantEvents,
                                                  String requestId, UserContext userContext, AtomicBoolean persisted) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        if (assistantEvents.isEmpty()) {
            return;
        }
        boolean createdContext = false;
        try {
            RequestContext<Object> requestContext = RequestContext.get();
            if (requestContext == null) {
                requestContext = new RequestContext<>();
                requestContext.setTenantId(userContext.getTenantId());
                requestContext.setUserId(userContext.getUserId());
                RequestContext.set(requestContext);
                createdContext = true;
            } else {
                if (requestContext.getTenantId() == null) {
                    requestContext.setTenantId(userContext.getTenantId());
                }
                if (requestContext.getUserId() == null) {
                    requestContext.setUserId(userContext.getUserId());
                }
            }

            CustomPageConversationModel model = new CustomPageConversationModel();
            model.setProjectId(projectId);
            model.setTopic("Assistant");
            model.setContent(JSON.toJSONString(Map.of("events", assistantEvents)));
            model.setRole("ASSISTANT");
            model.setSessionId(sessionId);
            model.setRequestId(requestId);
            customPageConversationDomainService.saveConversation(model, userContext);
        } catch (Exception e) {
            log.warn("[Domain] auto save assistant conversation failed, session Id={}", sessionId, e);
        } finally {
            if (createdContext) {
                RequestContext.remove();
            }
        }
    }

    private boolean shouldPersist(String eventName) {
        return !"ping".equalsIgnoreCase(eventName);
    }

    private boolean isFinalEvent(String eventName, String data) {
        if ("success".equalsIgnoreCase(eventName) || "error".equalsIgnoreCase(eventName)
                || "canceled".equalsIgnoreCase(eventName)
                || "end_turn".equalsIgnoreCase(eventName)) {
            return true;
        }
        if (!JSON.isValidObject(data)) {
            return false;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            Object type = payload.get("type");
            if (type != null) {
                String typeStr = String.valueOf(type);
                if ("success".equalsIgnoreCase(typeStr) || "error".equalsIgnoreCase(typeStr)
                        || "canceled".equalsIgnoreCase(typeStr)
                        || "end_turn".equalsIgnoreCase(typeStr)) {
                    return true;
                }
            }
            Object subType = payload.get("subType");
            if (subType != null && "end_turn".equalsIgnoreCase(String.valueOf(subType))) {
                return true;
            }
            Object reason = payload.get("reason");
            if (reason != null && "EndTurn".equalsIgnoreCase(String.valueOf(reason))) {
                return true;
            }
            Object dataObj = payload.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                Object nestedReason = dataMap.get("reason");
                if (nestedReason != null && "EndTurn".equalsIgnoreCase(String.valueOf(nestedReason))) {
                    return true;
                }
                Object nestedSubType = dataMap.get("subType");
                if (nestedSubType != null && "end_turn".equalsIgnoreCase(String.valueOf(nestedSubType))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Object parseRawData(String data) {
        if (!JSON.isValidObject(data)) {
            return data;
        }
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return data;
        }
    }

    private String extractRequestId(String data) {
        if (!JSON.isValidObject(data)) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            Object requestId = payload.get("request_id");
            if (requestId != null && StringUtils.isNotBlank(String.valueOf(requestId))) {
                return String.valueOf(requestId);
            }
            Object dataObj = payload.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                Object requestIdInData = dataMap.get("request_id");
                if (requestIdInData != null && StringUtils.isNotBlank(String.valueOf(requestIdInData))) {
                    return String.valueOf(requestIdInData);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ReqResult<Map<String, Object>> agentSessionCancel(String projectId, String sessionId,
            UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Long projectIdLong;
        try {
            projectIdLong = Long.valueOf(projectId);
        } catch (Exception e) {
            return ReqResult.error("0001", "Invalid projectId");
        }
        Map<String, Object> resp = aiAgentClient.sessionCancel(projectIdLong, sessionId, userContext);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to cancel task: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to cancel task" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

    @Override
    public ReqResult<Map<String, Object>> getAgentStatus(String projectId, UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Long projectIdLong;
        try {
            projectIdLong = Long.valueOf(projectId);
        } catch (Exception e) {
            return ReqResult.error("0001", "Invalid projectId");
        }
        Map<String, Object> resp = aiAgentClient.getAgentStatus(projectIdLong, userContext);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to query Agent status: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to query Agent status" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

    @Override
    public ReqResult<Map<String, Object>> stopAgent(String projectId, UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Long projectIdLong;
        try {
            projectIdLong = Long.valueOf(projectId);
        } catch (Exception e) {
            return ReqResult.error("0001", "Invalid projectId");
        }
        Map<String, Object> resp = aiAgentClient.stopAgent(projectIdLong, userContext);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to stop Agent service: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to stop Agent service" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

}
