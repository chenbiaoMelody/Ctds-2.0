package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import org.junit.jupiter.api.Test;

/** B6：SM3 摘要（标准向量在 ComplianceVectorTest 钉死，此处测输入契约）。 */
class Sm3ServiceTest {

    private final Sm3Service service = new Sm3Service();

    @Test
    void digestIsStableLowercaseHex64AndInputSensitive() {
        final String a = service.digestHex("同一内容");
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(service.digestHex("同一内容")).isEqualTo(a);
        assertThat(service.digestHex("不同内容")).isNotEqualTo(a);
    }

    @Test
    void rejectsNullInputsButAllowsEmptyForStandardEmptyMessageDigest() {
        assertThatThrownBy(() -> service.digestHex((byte[]) null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.digestHex((String) null)).isInstanceOf(BizException.class);
        // 评审③P1：空输入语义统一——SM3 空消息摘要有标准定义，两重载均放行且结果一致
        assertThat(service.digestHex("")).isEqualTo(service.digestHex(new byte[0]));
    }
}
