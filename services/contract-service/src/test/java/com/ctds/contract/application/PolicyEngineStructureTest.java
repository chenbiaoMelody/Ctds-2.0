package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.PolicyJudge;
import com.ctds.contract.domain.policy.UsageRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 判定入口结构锚（WBS-3.4.5 任务卡 §三 规则 1/规则 3 行）：判定入口 = 应用层方法（无新 HTTP
 * 端点）且判定纯函数无状态无 IO——服务端唯一权威（规格行为 5 规则 3"直接调用接口同样被拦截"，
 * 界面提示不构成执行；客户端不存在判定路径）。
 */
class PolicyEngineStructureTest {

    @Test
    void checkEntryIsApplicationLayerMethodWithoutHttpEndpoint() throws Exception {
        // 应用层方法：非控制器、无 HTTP 映射注解（无新端点——Q2-A）
        assertThat(PolicyExecutionService.class.getAnnotation(RestController.class))
                .as("判定入口不得是 HTTP 控制器").isNull();
        assertThat(PolicyExecutionService.class.getAnnotation(RequestMapping.class)).isNull();
        final boolean anyHttpMapping = Arrays.stream(PolicyExecutionService.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(RequestMapping.class));
        assertThat(anyHttpMapping).as("判定入口不得暴露 HTTP 端点").isFalse();
        // 唯一权威入口签名（同宿主直调）
        final Method check = PolicyExecutionService.class.getMethod("check", String.class,
                UsageRequest.class);
        assertThat(Modifier.isPublic(check.getModifiers())).isTrue();
    }

    @Test
    void judgeIsStatelessPureFunctionWithoutIo() throws Exception {
        // 判定纯函数：final 类 + 零实例字段（无 IO 依赖、无状态）+ 静态方法（可单测矩阵全覆盖）
        assertThat(Modifier.isFinal(PolicyJudge.class.getModifiers())).isTrue();
        assertThat(PolicyJudge.class.getDeclaredFields()).isEmpty();
        final Method judge = PolicyJudge.class.getMethod("judge", UsageControlPolicy.class,
                UsageRequest.class, LocalDate.class);
        assertThat(Modifier.isStatic(judge.getModifiers())).isTrue();
        assertThat(judge.getReturnType()).isEqualTo(List.class);
    }
}
