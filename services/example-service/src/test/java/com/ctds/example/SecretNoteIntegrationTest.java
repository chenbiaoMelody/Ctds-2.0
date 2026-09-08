package com.ctds.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B11 国密演示集成（真实 Spring 上下文 + 真实 common-crypto + 演示 KeyProvider）：
 * ①存取往返 ②库里是密文（CTSE 信封） ③同文两次入库密文互异（随机 IV） ④篡改被拒（1001C0002）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecretNoteIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEXT = "城市可信数据空间·内部报价备注001";

    @Autowired
    private MockMvc mockMvc;

    private UUID createNote(final String text) throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/secret-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return UUID.fromString(MAPPER.readTree(result.getResponse().getContentAsString()).get("data").asText());
    }

    @Test
    void storeCipherReadPlainRoundTrip() throws Exception {
        final UUID id = createNote(TEXT);
        mockMvc.perform(get("/api/v1/secret-notes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.text").value(TEXT));
    }

    @Test
    void storedEnvelopeIsCtdsCipherNotPlaintext() throws Exception {
        final UUID id = createNote(TEXT);
        final MvcResult result = mockMvc.perform(get("/api/v1/secret-notes/" + id + "/envelope"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
        final String envelope = node.get("data").asText();
        assertThat(envelope).doesNotContain(TEXT);
        final byte[] raw = Base64.getDecoder().decode(envelope);
        assertThat(new String(raw, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("CTSE");
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain(TEXT);
    }

    @Test
    void sameTextStoredTwiceHasDifferentEnvelopes() throws Exception {
        final UUID a = createNote(TEXT);
        final UUID b = createNote(TEXT);
        final String envA = envelopeOf(a);
        final String envB = envelopeOf(b);
        assertThat(envA).isNotEqualTo(envB);
        mockMvc.perform(get("/api/v1/secret-notes/" + b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value(TEXT));
    }

    @Test
    void tamperedEnvelopeIsRejectedWithDataRejectCode() throws Exception {
        final UUID id = createNote(TEXT);
        mockMvc.perform(post("/api/v1/secret-notes/" + id + "/tamper"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/secret-notes/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1001C0002"))
                .andExpect(jsonPath("$.message").value("数据校验未通过，已拒绝"));
    }

    @Test
    void emptyTextRejectedByComponentBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/secret-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1001C0001"));
    }

    @Test
    void unknownIdYieldsResourceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/secret-notes/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));
    }

    private String envelopeOf(final UUID id) throws Exception {
        final MvcResult result = mockMvc.perform(get("/api/v1/secret-notes/" + id + "/envelope"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data").asText();
    }
}
