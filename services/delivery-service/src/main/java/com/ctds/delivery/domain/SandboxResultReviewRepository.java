package com.ctds.delivery.domain;

import com.ctds.common.pagination.PageQuery;
import java.util.List;

/** 审核留痕仓储（hifi §2.2）；只追加，永不 UPDATE / DELETE。 */
public interface SandboxResultReviewRepository {

    void insert(SandboxResultReview review);

    List<SandboxResultReview> listBySandbox(String sandboxNo, String fileName, PageQuery page);

    long countBySandbox(String sandboxNo, String fileName);
}
