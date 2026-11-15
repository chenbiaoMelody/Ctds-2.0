package com.ctds.evidence.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * WBS-3.9.4 Q8-A：1013 段顺延码值集（监控读面 / 运维处置最小集；规格边界 8 本卡登记义务，
 * 任务卡 §二 Q8-A）。401 / 403 仍走 common-auth 公共码；参数越界复用
 * {@link RbacErrorCodes#PARAM_INVALID}（1013C0001，不新开码）。
 */
public final class MonitorErrorCodes {

    /** 处置对象未定义（非封闭两规则名，400）。 */
    public static final ErrorCode ALERT_NOT_DEFINED = ErrorCode.of("1013C0005");
    public static final String ALERT_NOT_DEFINED_MESSAGE = "告警对象未定义";

    /** 监控数据源不可用（Prometheus / Alertmanager 未配置或不可达，fail-closed → 503）。 */
    public static final ErrorCode MONITORING_SOURCE_UNAVAILABLE = ErrorCode.of("1013S0003");
    public static final String MONITORING_SOURCE_UNAVAILABLE_MESSAGE = "监控数据源暂不可用，请稍后重试";

    private MonitorErrorCodes() {
    }
}
