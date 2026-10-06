package com.ctds.contract.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.errorcode.ErrorCode;
import com.ctds.contract.domain.ContractErrorCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 码表 ↔ 出站处理器集合一致性锚（WBS-3.4.2 hifi §3；沿 catalog/space 同名测试先例）：
 * ContractErrorCodes 登记的每个码值都必须在 ContractExceptionHandler.MAPPED_CODES 内
 * （否则 1008 段异常会落全局处理器按默认映射出站，HTTP 语义漂移）；反向亦不得多出幽灵码。
 */
class ContractExceptionHandlerCodeConsistencyTest {

    @Test
    void handlerMappedCodesMatchErrorCodeTable() throws IllegalAccessException {
        final Set<String> declared = new TreeSet<>();
        for (final Field field : ContractErrorCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ErrorCode.class) {
                declared.add(((ErrorCode) field.get(null)).value());
            }
        }
        assertThat(declared).as("1008 段码表应登记 19 个 C 码 + 3 个 S 码"
                        + "（WBS-3.4.2 hifi §3 + WBS-3.4.3 hifi §3 续延；CONTRACT_PARAM_INVALID"
                        + " 与 TEMPLATE_PARAM_INVALID 同码 1008C0008 复用别名不重复计数）")
                .hasSize(22);
        assertThat(new TreeSet<>(ContractExceptionHandler.MAPPED_CODES))
                .as("处理器映射集与码表必须逐项一致（不得漂移）")
                .isEqualTo(declared);
        assertThat(declared).allMatch(code -> code.startsWith("1008"));
    }
}
