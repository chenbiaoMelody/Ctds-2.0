package com.ctds.did.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.VerificationLog;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * DID 管理面读数应用服务（WBS-3.1.11 B1/B7/B14）：签发记录列表、单 DID 操作留痕、验证留痕列表。
 * 只读：不新增业务规则、不写库、不改既有端点语义；状态口径复用既有 {@link DidStatus} 三值
 * （解析端口径仍锁两值，ADR-017 §3）；过滤值一律服务端校验（分页由 common-pagination 承担）。
 */
@Service
public class DidManagementQueryService {

    private static final int MAX_SUBJECT_NO_CHARS = 32;
    private static final int MAX_DID_CHARS = 128;

    private final DidRepository repository;

    public DidManagementQueryService(final DidRepository repository) {
        this.repository = repository;
    }

    /** 签发记录列表（分页 + 可选过滤；最新签发在前）。 */
    public PageResult<DidIdentity> records(final String subjectNo, final String status, final PageQuery query) {
        final String subjectNoFilter = subjectNoFilter(subjectNo);
        final String statusFilter = statusFilter(status);
        final List<DidIdentity> list = repository.findRecords(subjectNoFilter, statusFilter,
                (int) query.offset(), query.pageSize());
        return PageResult.of(list, repository.countRecords(subjectNoFilter, statusFilter), query);
    }

    /** 单 DID 操作留痕（签发/重签/吊销，时间正序）：未登记 → 1005B0003（明确业务答复）。 */
    public List<DidOperationLog> operationLogs(final String did) {
        if (did == null || did.isBlank()) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "DID 标识不合法");
        }
        repository.findByDid(did)
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_NOT_REGISTERED, "该 DID 未登记"));
        return repository.findOperationLogs(did);
    }

    /** 验证留痕列表（分页 + 可选 did 过滤；最新在前）：只含时间/DID/结果/原因，无数据原文。 */
    public PageResult<VerificationLog> verificationLogs(final String did, final PageQuery query) {
        final String didFilter = didFilter(did);
        final List<VerificationLog> list = repository.findVerificationLogs(didFilter,
                (int) query.offset(), query.pageSize());
        return PageResult.of(list, repository.countVerificationLogs(didFilter), query);
    }

    /** 主体编号过滤值：空白视为不筛选；超长 → 1005C0001（对外不暴露内部实现）。 */
    private static String subjectNoFilter(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank()) {
            return null;
        }
        final String trimmed = subjectNo.trim();
        if (trimmed.length() > MAX_SUBJECT_NO_CHARS) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "主体申请编号不合法");
        }
        return trimmed;
    }

    /** 记录状态过滤值：空白视为不筛选；非既有三值之一 → 1005C0001（不新增状态枚举）。 */
    private static String statusFilter(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DidStatus.valueOf(status.trim()).name();
        } catch (final IllegalArgumentException e) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "记录状态不合法");
        }
    }

    /** DID 过滤值：空白视为不筛选；超长 → 1005C0001。 */
    private static String didFilter(final String did) {
        if (did == null || did.isBlank()) {
            return null;
        }
        final String trimmed = did.trim();
        if (trimmed.length() > MAX_DID_CHARS) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "DID 标识不合法");
        }
        return trimmed;
    }
}
