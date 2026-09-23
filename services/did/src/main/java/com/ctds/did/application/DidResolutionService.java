package com.ctds.did.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * DID 解析应用服务（WBS-3.1.9 行为 2）：有效/已吊销均照常返回文档与状态（吊销可见，行为 3 依赖）；
 * 未登记 → 1005B0003 明确业务答复（不以系统异常样式呈现）。解析不留痕（hifi Q5=A）。
 */
@Service
public class DidResolutionService {

    private static final String DID_PREFIX = "did:ctds:";

    private final DidRepository repository;
    private final ObjectMapper objectMapper;

    public DidResolutionService(final DidRepository repository, final ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 解析：返回 DID 文档公开要素（JSON 结构）+ 当前状态（仅 ACTIVE/REVOKED 两值可对外）。 */
    public ResolutionResult resolve(final String did) {
        if (did == null || did.isBlank() || !did.startsWith(DID_PREFIX)) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "DID 标识不合法");
        }
        return repository.findByDid(did)
                .map(identity -> new ResolutionResult(identity.did(), identity.status(),
                        parseDocument(identity.documentJson())))
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_NOT_REGISTERED, "该 DID 未登记"));
    }

    /** 文档为签发时写入的公开要素 JSON（库内一致）；损坏属内部数据异常（hifi §6 边界表 → 1005S0002），不对外暴露细节。 */
    private JsonNode parseDocument(final String documentJson) {
        try {
            return objectMapper.readTree(documentJson);
        } catch (final JsonProcessingException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "解析内部错误");
        }
    }

    /** 解析结果视图（record：DID / 状态 / 文档公开要素）。 */
    public record ResolutionResult(String did, DidStatus status, JsonNode document) {
    }
}