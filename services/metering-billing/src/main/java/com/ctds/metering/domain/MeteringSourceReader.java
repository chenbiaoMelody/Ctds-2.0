package com.ctds.metering.domain;

import java.util.List;

/** 事件源读取端口（跨 schema 只读 SELECT，零写零改——B7-3 上游零改动；hifi §2）。 */
public interface MeteringSourceReader {

    /** 交付腿：delivery(FILE/SANDBOX_EXPORT, result=SUCCESS)——排除 API 行防双计（B7-3）。 */
    List<SourceRow> readDeliveryRows();

    /** 沙箱腿：sandbox_job(SUCCEEDED) 经 sandbox_task JOIN 归属（ADR-024；只取 SUCCEEDED 侧）。 */
    List<SourceRow> readSandboxJobRows();
}
