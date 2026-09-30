package com.ctds.catalog.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.common.errorcode.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 码表 ↔ 出站处理器集合一致性锚（WBS-3.3.2 hifi §2；沿 space 同名测试先例）：
 * CatalogErrorCodes 登记的每个码值都必须在 CatalogExceptionHandler.MAPPED_CODES 内
 * （否则 1007 段异常会落全局处理器按默认映射出站，HTTP 语义漂移）；反向亦不得多出幽灵码。
 */
class CatalogExceptionHandlerCodeConsistencyTest {

    @Test
    void handlerMappedCodesMatchErrorCodeTable() throws IllegalAccessException {
        final Set<String> declared = new TreeSet<>();
        for (final Field field : CatalogErrorCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ErrorCode.class) {
                declared.add(((ErrorCode) field.get(null)).value());
            }
        }
        assertThat(declared).as("1007 段码表应登记 8 个 C 码 + 2 个 S 码").hasSize(10);
        assertThat(new TreeSet<>(CatalogExceptionHandler.MAPPED_CODES))
                .as("处理器映射集与码表必须逐项一致（不得漂移）")
                .isEqualTo(declared);
        assertThat(declared).allMatch(code -> code.startsWith("1007"));
    }
}
