package com.ctds.common.idempotency;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * SpEL 幂等/锁键求值（契约 = WBS-2.4.7-hifi 边界表）：以方法参数为变量上下文（#参数名）求值；
 * 表达式语法错误或求值异常 → 1000C0001 PARAM_INVALID（快速失败，不执行业务）。
 * 用 SimpleEvaluationContext（只读数据绑定，仅属性访问，禁方法调用/T() 类型引用）——
 * 表达式为注解编译期常量无注入面，此上下文作纵深防御（评审②P3-2）。
 * 键求值结果限长（评审②P2-2 组件侧防护：防超长键对 Redis 内存压力）；键卫生（含业务命名空间、
 * 不可猜测）为使用方责任（ADR-007 §4）。
 */
public final class SpelKeyResolver {

    /** 幂等/锁键最大长度（字符）；超限 → 1000C0001（评审②P2-2）。 */
    public static final int MAX_KEY_LENGTH = 256;

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private SpelKeyResolver() {
    }

    /**
     * 求值 key 表达式；返回 null 表示表达式求值结果为 null（空键由调用方判定）。
     *
     * @param expression SpEL 表达式（注解 key 属性）
     * @param pjp        切点（提供方法与参数）
     * @return 求值结果的字符串形式；表达式本身为 null/空、求值异常或超长 → 1000C0001
     */
    public static String resolve(final String expression, final ProceedingJoinPoint pjp) {
        if (expression == null || expression.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "键表达式不能为空");
        }
        final MethodSignature signature = (MethodSignature) pjp.getSignature();
        final String[] paramNames = PARAMETER_NAMES.getParameterNames(signature.getMethod());
        final Object[] args = pjp.getArgs();
        final SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            final Expression expr = PARSER.parseExpression(expression);
            final Object value = expr.getValue(context);
            final String key = value == null ? null : String.valueOf(value);
            if (key != null && key.length() > MAX_KEY_LENGTH) {
                throw new BizException(ErrorCodes.PARAM_INVALID, "键长度超限");
            }
            return key;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "键表达式求值失败", e);
        }
    }
}
