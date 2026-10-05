package com.ctds.contract.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 合约协商与签署仓储端口（WBS-3.4.3 hifi §5 事务口径；实现 = infrastructure.JdbcContractRepository，
 * 多写事务边界在本端口实现方法）。版本行不可变：无任何改写条款内容（条款值/变更明细）的方法
 * ——编译期保证快照不可变（锁定写入的规范化原文/哈希与逐方确认列 = 确认动作承载，非内容修订）。
 * 并发口径：确认/签署/终止/解除一律先 FOR UPDATE 读合约行再条件更新（影响行数判定），
 * 并发撞唯一索引按约束名转译 1008C0019（换驱动回归清单 hifi §10-2）。
 */
public interface ContractRepository {

    // ==== 编号取号与发起三写 ====

    /** 全局 1 行原子取号（LAST_INSERT_ID 连接级技巧；事务绑定连接——沿模板 nextTemplateNoSeq 先例）。 */
    int nextContractNoSeq();

    /** W5 发起三写（contract + 条款版本 V1 + 留痕 CREATE）同事务；撞 uk_contract_no 上抛转译。 */
    void create(Contract contract, ContractClauseVersion versionV1, ContractActionLog log);

    // ==== 读取 ====

    Optional<Contract> findByNo(String contractNo);

    Optional<Contract> findById(long contractId);

    Optional<ContractClauseVersion> findVersion(long contractId, int versionNo);

    /** 条款版本链（版本号升序——R8 协商过程/R11 治理详情）。 */
    List<ContractClauseVersion> listVersions(long contractId);

    Optional<ContractSignature> findSignature(long contractId, PartyRole role);

    /** 签署记录双方全集（R7 逐方签署状态/R9 验签/R11 治理详情）。 */
    List<ContractSignature> listSignatures(long contractId);

    Optional<ContractAttestation> findAttestation(long contractId);

    /** R6 我的合约分页（恒仅本主体参与；status/role 可选过滤——role 命中对应主体列）。 */
    PageResult<Contract> pageMine(String subjectNo, ContractStatus status, PartyRole role,
            PageQuery page);

    /** R10 治理全量分页（含协商中/已终止；status 可选过滤）。 */
    PageResult<Contract> pageAll(ContractStatus status, PageQuery page);

    // ==== 协商与生命周期写面（事务边界在实现） ====

    /**
     * W6 提案三写（版本 Vn+1 + 主表版本指针前移 + 留痕 PROPOSE）同事务；
     * 撞 uk_contract_version 上抛转译 1008C0019（并发提案唯一索引兜底）。
     */
    void propose(long contractId, ContractClauseVersion newVersion, ContractActionLog log);

    /**
     * W7 确认（同事务：FOR UPDATE 读合约行复判状态 → 条件更新本方确认列 → 双方齐则条件锁版本行
     * （规范化原文+哈希，WHERE content_hash IS NULL 恰一次）→ 条件转 PENDING_SIGNATURE → 留痕）。
     */
    ConfirmOutcome confirm(ConfirmCommand command);

    /** W9 签署（同事务：FOR UPDATE 复判 → 插签署行 → 条件转状态 → 后签写生效+存证+留痕）。 */
    SignOutcome sign(SignCommand command);

    /** W8/W10/W12 终止（同事务：FOR UPDATE 复判 → 条件转 TERMINATED + 终止要素 → 留痕）。 */
    TerminateOutcome terminate(TerminateCommand command);

    /** W11 合意解除（同事务：FOR UPDATE 复判 → 条件确认列 → 双方齐条件转 COMPLETED + ended_at → 留痕）。 */
    ReleaseOutcome releaseConsent(ReleaseCommand command);

    /** 独立留痕写入（拒绝/异常/治理查看——主链回滚不影响留痕，沿 3.4.2 先例）。 */
    void insertLog(ContractActionLog log);

    // ==== 事务命令与结局载体 ====

    /** W7 命令（canonicalCipher/contentHash 由应用服务按锁定值预计算——确定性，双方计算一致）。 */
    record ConfirmCommand(long contractId, int versionNo, PartyRole role, String actorSubjectNo,
            LocalDateTime at, byte[] canonicalCipher, String contentHash, ContractActionLog log) {
    }

    enum ConfirmOutcome { CONFIRMED, LOCKED, DUPLICATE_CONFIRM, STATE_CONFLICT }

    /** W9 命令（签名密文由应用服务在 did 代签成功后封装；effectiveAt 仅后签写）。 */
    record SignCommand(long contractId, PartyRole role, String subjectNo, String did,
            String contentHash, byte[] signatureCipher, LocalDateTime signedAt,
            ContractActionLog signLog, ContractActionLog attestLog) {
    }

    enum SignOutcome { FIRST_SIGNED, EFFECTED, DUPLICATE_SIGN, STATE_CONFLICT }

    /** W8/W10/W12 命令（终止类型与留痕动作由应用服务按分支落定；reason 仅强制终止必填）。 */
    record TerminateCommand(long contractId, String contractNo, java.util.Set<ContractStatus> fromStates,
            TerminationType terminationType, String reason, String terminatedBy, LocalDateTime at,
            ContractActionLog log) {
    }

    enum TerminateOutcome { TERMINATED, STATE_CONFLICT }

    /** W11 命令。 */
    record ReleaseCommand(long contractId, String contractNo, PartyRole role, String actorSubjectNo,
            LocalDateTime at, ContractActionLog log) {
    }

    enum ReleaseOutcome { CONSENT_RECORDED, COMPLETED, DUPLICATE_CONSENT, STATE_CONFLICT }
}
