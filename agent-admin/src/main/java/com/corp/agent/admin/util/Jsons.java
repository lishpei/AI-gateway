package com.corp.agent.admin.util;

import com.corp.agent.admin.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * Jackson 静态工具（独立于 Spring 上下文，便于单测）。
 */
public final class Jsons {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Jsons() {
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json deserialize failed: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw BizException.badRequest("非法 JSON 字符串: " + e.getMessage());
        }
    }

    public static List<Object> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw BizException.badRequest("非法 JSON 数组字符串: " + e.getMessage());
        }
    }

    /** 校验字符串为合法 JSON（对象或数组） */
    public static void requireValidJson(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            MAPPER.readTree(json);
        } catch (Exception e) {
            throw BizException.badRequest(fieldName + " 不是合法 JSON: " + e.getMessage());
        }
    }
}
