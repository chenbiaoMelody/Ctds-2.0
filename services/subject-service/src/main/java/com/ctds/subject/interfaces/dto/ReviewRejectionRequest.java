package com.ctds.subject.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核驳回请求体（WBS-3.1.5 hifi 接口契约；理由必填——规格行为 5 第 2 条；长度上限由应用服务
 * 按配置参数校验，DTO 侧 512 为传输面兜底）。
 *
 * @param reason 驳回理由（随流转留痕落库，申请人可见并可修改后重新申请）
 */
public record ReviewRejectionRequest(
        @NotBlank(message = "驳回理由必填")
        @Size(max = 512, message = "驳回理由超长")
        String reason) {
}
