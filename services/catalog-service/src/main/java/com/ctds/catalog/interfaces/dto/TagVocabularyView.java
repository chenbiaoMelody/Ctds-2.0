package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.TagVocabulary;

/**
 * 词表册出站视图（WBS-3.3.3 hifi §1.1 R3）：仅册码与册名——不含本体、主体与个人信息（行为 7 规则 5）。
 */
public record TagVocabularyView(String vocabularyCode, String vocabularyName) {

    public static TagVocabularyView from(final TagVocabulary vocabulary) {
        return new TagVocabularyView(vocabulary.vocabularyCode(), vocabulary.vocabularyName());
    }
}
