package com.ctds.space.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.errorcode.ErrorCode;
import com.ctds.space.domain.SpaceErrorCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 码表↔处理器映射一致性锚（评审循环 1 补；hifi §2 表 + SpaceErrorCodes 常量 + 处理器映射集
 * 三处登记不得漂移）：SpaceErrorCodes 的全部 ErrorCode 常量必须与处理器精确映射集完全一致——
 * 新增码值漏登记映射集时此处即红（处理器的越集兜底分支因此保持不可达防御位）。
 */
class SpaceExceptionHandlerCodeConsistencyTest {

    @Test
    void everySpaceErrorCodeIsPreciselyMapped() throws Exception {
        final Set<String> defined = new LinkedHashSet<>();
        for (final Field field : SpaceErrorCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ErrorCode.class) {
                defined.add(((ErrorCode) field.get(null)).value());
            }
        }
        assertThat(defined).as("1006 段码值应恰为 15 个（3.2.3 定稿 8 码 + WBS-3.2.4 顺延 4 码 + WBS-3.2.5 顺延 3 码）")
                .hasSize(15);
        assertThat(SpaceExceptionHandler.MAPPED_CODES).containsExactlyInAnyOrderElementsOf(defined);
    }
}
