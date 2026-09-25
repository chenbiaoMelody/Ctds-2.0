package com.ctds.std.did;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟对端预置样例清单（WBS-3.1.10 hifi §3）：classpath 资源（真实 SM2 签名，制作期一次性生成、私钥不入库）
 * → 对端空间标识与三态样例。
 * <p>资源缺失/损坏属内部错误（由调用方收敛为自身 S 型错误码），不得降级为"验证不通过"。</p>
 */
public final class DidInteropSamples {

    private static final String RESOURCE = "/std/did-interop-samples.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String peerSpace;
    private final String peerSpaceName;
    private final List<InteropSample> samples;

    public DidInteropSamples(final String peerSpace, final String peerSpaceName, final List<InteropSample> samples) {
        this.peerSpace = peerSpace;
        this.peerSpaceName = peerSpaceName;
        this.samples = List.copyOf(samples);
    }

    /** 读取预置样例资源（只读、无副作用；演示期样例不随运行期变化）。 */
    public static DidInteropSamples fromClasspath() {
        try (InputStream in = DidInteropSamples.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("互认样例资源缺失：" + RESOURCE);
            }
            return parse(MAPPER.readTree(in));
        } catch (final IOException e) {
            throw new UncheckedIOException("互认样例资源不可读", e);
        }
    }

    private static DidInteropSamples parse(final JsonNode root) {
        final List<InteropSample> parsed = new ArrayList<>();
        for (final JsonNode node : root.path("samples")) {
            parsed.add(new InteropSample(
                    node.path("sampleId").asText(),
                    node.path("did").asText(),
                    InteropScenario.valueOf(node.path("scenario").asText()),
                    node.path("data").asText(),
                    node.path("signature").asText(),
                    node.path("publicKeyHex").asText(),
                    node.path("peerRevoked").asBoolean(),
                    node.path("peerBindingLost").asBoolean(),
                    InteropResult.valueOf(node.path("expectedResult").asText()),
                    node.path("expectedReason").isNull()
                            ? null
                            : InteropReason.valueOf(node.path("expectedReason").asText())));
        }
        return new DidInteropSamples(root.path("peerSpace").asText(), root.path("peerSpaceName").asText(), parsed);
    }

    public String peerSpace() {
        return peerSpace;
    }

    public String peerSpaceName() {
        return peerSpaceName;
    }

    public List<InteropSample> samples() {
        return samples;
    }
}
