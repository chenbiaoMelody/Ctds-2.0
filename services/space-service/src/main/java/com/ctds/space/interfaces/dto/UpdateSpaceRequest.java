package com.ctds.space.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 空间配置变更请求（WBS-3.2.3 hifi §1 端点 6，Q5-A 白名单语义）：仅接受简介与生效期；
 * 名称/场景/参与方范围/可见性不可变更（规格未定义其变更规则）——多余字段不静默忽略，
 * 一律 400（hifi §8/T11"仅接受白名单字段，多余字段 400"）。
 */
public class UpdateSpaceRequest {

    private String intro;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    @JsonIgnore
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public String getIntro() {
        return intro;
    }

    public void setIntro(final String intro) {
        this.intro = intro;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(final LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(final LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    /** 白名单外字段捕获（@JsonAnySetter；只记字段存在性，不回显值）。 */
    @JsonAnySetter
    void captureUnknownField(final String name, final Object value) {
        unknownFields.add(name);
    }

    /** 是否只含白名单字段。 */
    public boolean withinWhitelist() {
        return unknownFields.isEmpty();
    }
}
