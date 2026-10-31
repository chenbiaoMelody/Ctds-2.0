package com.ctds.contract.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.errorcode.ErrorCode;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.OrderErrorCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 码表 ↔ 出站处理器集合一致性锚（WBS-3.4.2 hifi §3；沿 catalog/space 同名测试先例）：
 * ContractErrorCodes（1008 段）与 OrderErrorCodes（1011 段，WBS-3.7.3）登记的每个码值都必须在
 * ContractExceptionHandler.MAPPED_CODES 内（否则异常会落全局处理器按默认映射出站，HTTP 语义
 * 漂移）；反向亦不得多出幽灵码。WBS-3.7.3 勘误：双 advice 会按异常类型劫持 ContractBizException，
 * 故两段共用一个处理器（登记制不另设 advice）。
 */
class ContractExceptionHandlerCodeConsistencyTest {

    @Test
    void handlerMappedCodesMatchErrorCodeTable() throws IllegalAccessException {
        final Set<String> declared = new TreeSet<>();
        declared.addAll(errorCodesOf(ContractErrorCodes.class));
        declared.addAll(errorCodesOf(OrderErrorCodes.class));
        assertThat(declared).as("1008 段码表应登记 20 个 C 码 + 3 个 S 码"
                        + "（WBS-3.4.2 hifi §3 + WBS-3.4.3 hifi §3 续延 + WBS-3.4.5 hifi §8"
                        + " 续延 C0020；CONTRACT_PARAM_INVALID 与 TEMPLATE_PARAM_INVALID 同码"
                        + " 1008C0008 复用别名不重复计数）+ 1011 段订单码表 4 个 C 码"
                        + "（WBS-3.7.3 hifi §1.5；1011S 段不声明避免死码）")
                .hasSize(27);
        assertThat(new TreeSet<>(ContractExceptionHandler.MAPPED_CODES))
                .as("处理器映射集与码表必须逐项一致（不得漂移）")
                .isEqualTo(declared);
        assertThat(declared).allMatch(code -> code.startsWith("1008") || code.startsWith("1011"));
    }

    private Set<String> errorCodesOf(final Class<?> table) throws IllegalAccessException {
        final Set<String> values = new TreeSet<>();
        for (final Field field : table.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ErrorCode.class) {
                values.add(((ErrorCode) field.get(null)).value());
            }
        }
        return values;
    }
}
