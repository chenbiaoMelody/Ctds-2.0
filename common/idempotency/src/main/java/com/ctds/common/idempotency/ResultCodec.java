package com.ctds.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;

/**
 * 幂等结果 JSON 编解码（契约 = WBS-2.4.7-hifi 接口契约）：方法返回值序列化进结果缓存并按
 * 方法泛型返回类型还原；null 返回值视为合法结果（序列化为 JSON null，还原为 null）。
 * 组件自建 ObjectMapper（标准配置 + JavaTimeModule 支持 java.time 返回值），不依赖 web 栈自动配置。
 */
public final class ResultCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ResultCodec() {
    }

    public static String serialize(final Object result) throws JsonProcessingException {
        return MAPPER.writeValueAsString(result);
    }

    public static Object deserialize(final String json, final Type returnType) throws JsonProcessingException {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        final JavaType javaType = MAPPER.getTypeFactory().constructType(returnType);
        return MAPPER.readValue(json, javaType);
    }
}
