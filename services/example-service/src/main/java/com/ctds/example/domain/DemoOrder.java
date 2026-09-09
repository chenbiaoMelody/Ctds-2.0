package com.ctds.example.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.Instant;

/**
 * 领域实体：订单（幂等演示，WBS 2.4.7 B11）——orderNo 为幂等键，同号重复提交只创建一次。
 */
public record DemoOrder(String orderNo, String status, Instant submittedAt) {

    public DemoOrder {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "orderNo must not be null or blank");
        }
    }

    public static DemoOrder of(final String orderNo) {
        return new DemoOrder(orderNo, "CREATED", Instant.now());
    }
}
