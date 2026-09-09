package com.ctds.common.idempotency;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * SpEL 幂等/锁键求值（契约 = WBS-2.4.7-hifi 边界表）：以方法参数为变量上下文（#参数名）求值；
 * 表达式语法错误或求值异常 → 1000C0001 PARAM_INVALID（快速失败，不执行业务）。
 */
public final class SpelKeyResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private SpelKeyResolver() {
    }

    /**
     * 求值 key 表达式；返回 null 表示表达式求值结果为 null（空键由调用方判定）。
     *
     * @param expression SpEL 表达式（注解 key 属性）
     * @param pjp        切点（提供方法与参数）
     * @return 求值结果的字符串形式；表达式本身为 null/空 或求值异常 → 1000C0001
     */
    public static String resolve(final String expression, final ProceedingJoinPoint pjp) {
        if (expression == null || expression.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "键表达式不能为空");
        }
        final MethodSignature signature = (MethodSignature) pjp.getSignature();
        final String[] paramNames = PARAMETER_NAMES.getParameterNames(signature.getMethod());
        final Object[] args = pjp.getArgs();
        final EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            final Expression expr = PARSER.parseExpression(expression);
            final Object value = expr.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "键表达式求值失败", e);
        }
    }
}
