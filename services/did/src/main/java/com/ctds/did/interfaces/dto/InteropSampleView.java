package com.ctds.did.interfaces.dto;

import com.ctds.std.did.DidInteropSamples;
import com.ctds.std.did.InteropSample;
import java.util.List;

/**
 * 预置样例视图（hifi §2）：三态样例随对端空间标识一并输出（执行人按清单取用，免手输 DID 与签名）。
 * 样例公钥与对端状态断言属对端内部口径，不出现在响应中。
 */
public record InteropSampleView(
        String sampleId,
        String peerSpace,
        String peerSpaceName,
        String did,
        String scenario,
        String data,
        String signature,
        String expectedResult,
        String expectedReason) {

    public static List<InteropSampleView> from(final DidInteropSamples samples) {
        return samples.samples().stream()
                .map(sample -> of(samples, sample))
                .toList();
    }

    private static InteropSampleView of(final DidInteropSamples samples, final InteropSample sample) {
        return new InteropSampleView(sample.sampleId(), samples.peerSpace(), samples.peerSpaceName(), sample.did(),
                sample.scenario().name(), sample.data(), sample.signature(), sample.expectedResult().name(),
                sample.expectedReason() == null ? null : sample.expectedReason().name());
    }
}
