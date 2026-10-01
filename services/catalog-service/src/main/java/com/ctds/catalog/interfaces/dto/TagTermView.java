package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.TagTerm;

/**
 * 词条出站视图（WBS-3.3.3 hifi §1.1 R4）：仅词条编号与名称——归一化名属内部匹配口径不出站，
 * 无本体、无主体与个人信息（行为 7 规则 5）。
 */
public record TagTermView(String termCode, String termName) {

    public static TagTermView from(final TagTerm term) {
        return new TagTermView(term.termCode(), term.termName());
    }
}
