package com.ctds.common.modelgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.modelgateway.api.ChatCompletionRequest;
import com.ctds.common.modelgateway.api.ChatCompletionResponse;
import com.ctds.common.modelgateway.api.ChatMessage;
import com.ctds.common.modelgateway.api.Choice;
import com.ctds.common.modelgateway.api.ModelGateway;
import com.ctds.common.modelgateway.api.Usage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void requestShouldSerializeSnakeCaseAndOmitNullOptionals() throws Exception {
        final ChatCompletionRequest request = new ChatCompletionRequest(
                "glm-4", List.of(new ChatMessage("user", "你好")), null, 512, null);

        final JsonNode json = mapper.readTree(mapper.writeValueAsString(request));

        assertTrue(json.has("model"));
        assertTrue(json.has("messages"));
        assertTrue(json.has("max_tokens"));
        assertEquals(512, json.get("max_tokens").asInt());
        assertFalse(json.has("maxTokens"));
        assertFalse(json.has("temperature"));
        assertFalse(json.has("stream"));
    }

    @Test
    void responseShouldSerializeSnakeCaseUsageAndFixedObject() throws Exception {
        final ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-1", ChatCompletionResponse.OBJECT_CHAT_COMPLETION, 1700000000L, "glm-4",
                List.of(new Choice(0, new ChatMessage("assistant", "你好！"), "stop")),
                new Usage(10, 20, 30));

        final JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals("chat.completion", json.get("object").asText());
        assertEquals("stop", json.get("choices").get(0).get("finish_reason").asText());
        assertEquals(10, json.get("usage").get("prompt_tokens").asInt());
        assertEquals(20, json.get("usage").get("completion_tokens").asInt());
        assertEquals(30, json.get("usage").get("total_tokens").asInt());
        assertFalse(json.has("finishReason"));
    }

    @Test
    void multiTurnMessagesShouldRoundTripVerbatim() throws Exception {
        assertEquals("system", ChatMessage.ROLE_SYSTEM);
        assertEquals("user", ChatMessage.ROLE_USER);
        assertEquals("assistant", ChatMessage.ROLE_ASSISTANT);
        final ChatCompletionRequest request = new ChatCompletionRequest("glm-4",
                List.of(new ChatMessage("system", "你是助手"),
                        new ChatMessage("user", "问题"),
                        new ChatMessage("assistant", "回答")),
                0.7, 256, null);

        final String json = mapper.writeValueAsString(request);
        final ChatCompletionRequest back = mapper.readValue(json, ChatCompletionRequest.class);

        assertEquals(request, back);
        assertEquals(json, mapper.writeValueAsString(back));
    }

    @Test
    void responseWithoutUsageShouldRoundTripWithNullUsage() throws Exception {
        final ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-2", ChatCompletionResponse.OBJECT_CHAT_COMPLETION, 1700000001L, "glm-4",
                List.of(new Choice(0, new ChatMessage("assistant", "ok"), null)), null);

        final String json = mapper.writeValueAsString(response);
        assertFalse(mapper.readTree(json).has("usage"));
        assertFalse(mapper.readTree(json).get("choices").get(0).has("finish_reason"));

        final ChatCompletionResponse back = mapper.readValue(json, ChatCompletionResponse.class);
        assertEquals(response, back);
        assertNull(back.usage());
    }

    @Test
    void unknownFieldsShouldBeIgnoredOnDeserialization() throws Exception {
        final String json = "{\"model\":\"glm-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\","
                + "\"extra_msg_field\":1}],\"max_tokens\":16,\"future_request_field\":true}";

        final ChatCompletionRequest request = mapper.readValue(json, ChatCompletionRequest.class);

        assertEquals("glm-4", request.model());
        assertEquals(16, request.maxTokens());
        assertEquals("user", request.messages().get(0).role());
        assertEquals("hi", request.messages().get(0).content());
    }

    @Test
    void modelGatewayInterfaceShouldAcceptContractTypes() {
        final ChatCompletionResponse canned = new ChatCompletionResponse(
                "chatcmpl-3", ChatCompletionResponse.OBJECT_CHAT_COMPLETION, 1700000002L, "glm-4",
                List.of(new Choice(0, new ChatMessage("assistant", "ok"), "stop")), null);
        final ModelGateway gateway = request -> canned;

        assertSame(canned, gateway.chat(new ChatCompletionRequest("glm-4",
                List.of(new ChatMessage("user", "hi")), null, null, null)));
    }
}
