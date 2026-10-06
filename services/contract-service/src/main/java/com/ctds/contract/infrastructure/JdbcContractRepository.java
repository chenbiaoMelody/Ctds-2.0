package com.ctds.contract.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractAttestation;
import com.ctds.contract.domain.ContractClauseVersion;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractSignature;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.PartyRole;
import com.ctds.contract.domain.TerminationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合约协商与签署仓储（JdbcClient，ADR-009 迁移规范建表 V2；沿 JdbcContractTemplateRepository
 * 先例）。多写事务口径（hifi §5）：create = 取号外三写、propose = 三写、confirm/sign/terminate/
 * releaseConsent = FOR UPDATE 复判（状态 + 当前版本指针一并读回）+ 条件更新（影响行数判定，
 * 行级互斥恰一次转移）+ 留痕，事务边界在本仓储方法（@Transactional）；insertLog 在主链内随主链
 * 事务提交，被应用层在无事务上下文调用时为独立写入（拒绝留痕，主链回滚不影响——调用契约见端口）。
 * <b>无任何更新条款内容（条款值/变更明细）的方法</b>——版本行不可变（编译期保证，hifi §4 注）。
 */
@Repository
public class JdbcContractRepository implements ContractRepository {

    private static final String CONTRACT_COLUMNS = "id, contract_no, product_id, product_name, "
            + "provider_subject_no, requester_subject_no, template_no, template_version_no, "
            + "pricing_model, price_amount, current_clause_version, status, termination_type, "
            + "termination_reason, terminated_by, effective_at, ended_at, "
            + "release_consent_provider_at, release_consent_requester_at, created_by, created_at, "
            + "updated_at";
    private static final String VERSION_COLUMNS = "id, contract_id, version_no, "
            + "clause_values_cipher, changes_cipher, canonical_cipher, content_hash, proposed_by, "
            + "proposed_at, confirmed_provider_at, confirmed_requester_at";
    private static final String SIGNATURE_COLUMNS = "id, contract_id, contract_no, party_role, "
            + "subject_no, did, content_hash, signature_cipher, signed_at, created_at";

    private final JdbcClient jdbc;

    public JdbcContractRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ==== 编号取号与发起三写 ====

    @Override
    @Transactional
    public int nextContractNoSeq() {
        // 全局 1 行原子自增（LAST_INSERT_ID 连接级技巧，沿模板 nextTemplateNoSeq 先例）；
        // 事务绑定保证两条语句共用同一连接（LAST_INSERT_ID 为连接级，脱离事务则跨连接失真）
        jdbc.sql("UPDATE contract_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    @Override
    @Transactional
    public void create(final Contract contract, final ContractClauseVersion versionV1,
            final ContractActionLog log) {
        jdbc.sql("INSERT INTO contract (contract_no, product_id, product_name, "
                        + "provider_subject_no, requester_subject_no, template_no, "
                        + "template_version_no, pricing_model, price_amount, current_clause_version, "
                        + "status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(contract.contractNo()).param(contract.productId())
                .param(contract.productName()).param(contract.providerSubjectNo())
                .param(contract.requesterSubjectNo()).param(contract.templateNo())
                .param(contract.templateVersionNo()).param(contract.pricingModel())
                .param(contract.priceAmount()).param(contract.currentClauseVersion())
                .param(contract.status().name()).param(contract.createdBy()).update();
        // contractNo 全局唯一（uk_contract_no），回查 id 供版本表逻辑引用（编号业务键语义）
        final long contractId = jdbc.sql("SELECT id FROM contract WHERE contract_no = ?")
                .param(contract.contractNo()).query(Long.class).single();
        insertVersionRow(new ContractClauseVersion(versionV1.id(), contractId,
                versionV1.versionNo(), versionV1.clauseValuesCipher(), versionV1.changesCipher(),
                versionV1.canonicalCipher(), versionV1.contentHash(), versionV1.proposedBy(),
                versionV1.proposedAt(), versionV1.confirmedProviderAt(),
                versionV1.confirmedRequesterAt()));
        insertLog(log);
    }

    // ==== 读取 ====

    @Override
    public Optional<Contract> findByNo(final String contractNo) {
        return jdbc.sql("SELECT " + CONTRACT_COLUMNS + " FROM contract WHERE contract_no = ?")
                .param(contractNo)
                .query((rs, rowNum) -> mapContract(rs))
                .optional();
    }

    @Override
    public Optional<ContractClauseVersion> findVersion(final long contractId, final int versionNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM contract_clause_version "
                        + "WHERE contract_id = ? AND version_no = ?")
                .param(contractId).param(versionNo)
                .query((rs, rowNum) -> mapVersion(rs))
                .optional();
    }

    @Override
    public List<ContractClauseVersion> listVersions(final long contractId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM contract_clause_version "
                        + "WHERE contract_id = ? ORDER BY version_no ASC")
                .param(contractId)
                .query((rs, rowNum) -> mapVersion(rs))
                .list();
    }

    @Override
    public Optional<ContractSignature> findSignature(final long contractId, final PartyRole role) {
        return jdbc.sql("SELECT " + SIGNATURE_COLUMNS + " FROM contract_signature "
                        + "WHERE contract_id = ? AND party_role = ?")
                .param(contractId).param(role.name())
                .query((rs, rowNum) -> mapSignature(rs))
                .optional();
    }

    @Override
    public List<ContractSignature> listSignatures(final long contractId) {
        return jdbc.sql("SELECT " + SIGNATURE_COLUMNS + " FROM contract_signature "
                        + "WHERE contract_id = ? ORDER BY signed_at ASC, id ASC")
                .param(contractId)
                .query((rs, rowNum) -> mapSignature(rs))
                .list();
    }

    @Override
    public Optional<ContractAttestation> findAttestation(final long contractId) {
        return jdbc.sql("SELECT id, contract_id, contract_no, content_hash, provider_subject_no, "
                        + "provider_did, provider_signed_at, requester_subject_no, requester_did, "
                        + "requester_signed_at, attested_at FROM contract_attestation "
                        + "WHERE contract_id = ?")
                .param(contractId)
                .query((rs, rowNum) -> mapAttestation(rs))
                .optional();
    }

    @Override
    public PageResult<Contract> pageMine(final String subjectNo, final ContractStatus status,
            final PartyRole role, final PageQuery page) {
        final StringBuilder where = new StringBuilder(
                "(provider_subject_no = ? OR requester_subject_no = ?)");
        final List<Object> params = new ArrayList<>(List.of(subjectNo, subjectNo));
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        if (role != null) {
            where.append(role == PartyRole.PROVIDER
                    ? " AND provider_subject_no = ?" : " AND requester_subject_no = ?");
            params.add(subjectNo);
        }
        return pageContracts(where.toString(), params, page);
    }

    @Override
    public PageResult<Contract> pageAll(final ContractStatus status, final PageQuery page) {
        final StringBuilder where = new StringBuilder("1 = 1");
        final List<Object> params = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        return pageContracts(where.toString(), params, page);
    }

    // ==== 协商与生命周期写面 ====

    @Override
    @Transactional
    public void propose(final long contractId, final ContractClauseVersion newVersion,
            final ContractActionLog log) {
        insertVersionRow(new ContractClauseVersion(newVersion.id(), contractId,
                newVersion.versionNo(), newVersion.clauseValuesCipher(), newVersion.changesCipher(),
                newVersion.canonicalCipher(), newVersion.contentHash(), newVersion.proposedBy(),
                newVersion.proposedAt(), newVersion.confirmedProviderAt(),
                newVersion.confirmedRequesterAt()));
        final int moved = jdbc.sql("UPDATE contract SET current_clause_version = ? WHERE id = ? "
                        + "AND status = ? AND current_clause_version = ?")
                .param(newVersion.versionNo()).param(contractId)
                .param(ContractStatus.NEGOTIATING.name())
                .param(newVersion.versionNo() - 1)
                .update();
        if (moved != 1) {
            // 指针前移未命中：状态或当前版本已被并发变更——回滚本条版本行（抛出让事务回滚）
            throw new IllegalStateException("提案版本指针更新未命中: contractId=" + contractId
                    + ", 目标版本=" + newVersion.versionNo());
        }
        insertLog(log);
    }

    @Override
    @Transactional
    public ConfirmOutcome confirm(final ConfirmCommand command) {
        // ① FOR UPDATE 读合约行：同合约全部写事务在此串行化（后到者按行锁读回最新状态与当前版本）；
        // 版本指针复判（评审循环 2 P0）：确认命令携带的版本 ≠ 锁下当前指针 = 版本已被并发提案
        // 前移——按陈旧版本确认将产生"待签署 + 当前版本未锁定"死状态，与状态冲突同口径拒绝
        final LockedContractState locked = readContractForUpdate(command.contractId());
        if (locked.status() != ContractStatus.NEGOTIATING
                || locked.currentClauseVersion() != command.versionNo()) {
            return ConfirmOutcome.STATE_CONFLICT;
        }
        // ② 条件更新本方确认列（影响行数 = 1 才算本次确认——重复确认天然互斥）
        final String confirmColumn = "confirmed_" + command.role().name().toLowerCase() + "_at";
        final int marked = jdbc.sql("UPDATE contract_clause_version SET " + confirmColumn
                        + " = ? WHERE contract_id = ? AND version_no = ? AND " + confirmColumn
                        + " IS NULL")
                .param(command.at()).param(command.contractId()).param(command.versionNo())
                .update();
        if (marked != 1) {
            return ConfirmOutcome.DUPLICATE_CONFIRM;
        }
        final ContractClauseVersion version = readVersionForUpdate(command.contractId(),
                command.versionNo());
        // ③ 双方齐 → 恰一次锁定（content_hash IS NULL 条件封口）+ 状态转移（带版本指针前置——
        // 纵深防御）+ 留痕；两句均在行锁串行化下执行，影响行数 ≠ 1 为不可达断言腿（回滚 fail-stop）
        if (version.bothConfirmed()) {
            final int moved = jdbc.sql("UPDATE contract SET status = ? WHERE id = ? AND status = ? "
                            + "AND current_clause_version = ?")
                    .param(ContractStatus.PENDING_SIGNATURE.name()).param(command.contractId())
                    .param(ContractStatus.NEGOTIATING.name()).param(command.versionNo()).update();
            if (moved != 1) {
                throw new IllegalStateException("确认锁定状态转移未命中: contractId="
                        + command.contractId() + ", 版本=" + command.versionNo());
            }
            final int lockedRows = jdbc.sql("UPDATE contract_clause_version SET canonical_cipher = ?,"
                            + " content_hash = ? WHERE id = ? AND content_hash IS NULL")
                    .param(command.canonicalCipher()).param(command.contentHash())
                    .param(version.id()).update();
            if (lockedRows != 1) {
                throw new IllegalStateException("确认锁定版本行未命中: contractId="
                        + command.contractId() + ", 版本=" + command.versionNo());
            }
            insertLog(command.log());
            return ConfirmOutcome.LOCKED;
        }
        // 单方确认成功同落 CONFIRM 留痕（规格行为 2 规则 6"确认全留痕"——勘误⑨：验收走查
        // 实证单方腿缺失后补齐；锁定腿与单方腿互斥，同一命令日志行两分支各写一次不重复）
        insertLog(command.log());
        return ConfirmOutcome.CONFIRMED;
    }

    @Override
    @Transactional
    public SignOutcome sign(final SignCommand command) {
        final ContractStatus status = readContractForUpdate(command.contractId()).status();
        if (status != ContractStatus.PENDING_SIGNATURE && status != ContractStatus.PARTIALLY_SIGNED) {
            return SignOutcome.STATE_CONFLICT;
        }
        if (findSignature(command.contractId(), command.role()).isPresent()) {
            return SignOutcome.DUPLICATE_SIGN;
        }
        jdbc.sql("INSERT INTO contract_signature (contract_id, contract_no, party_role, "
                        + "subject_no, did, content_hash, signature_cipher, signed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .param(command.contractId()).param(command.signLog().contractNo())
                .param(command.role().name()).param(command.subjectNo()).param(command.did())
                .param(command.contentHash()).param(command.signatureCipher())
                .param(command.signedAt()).update();
        // 未双签生效前互斥推进：待签署 → 部分签署；部分签署 → 已生效（生效时间 = 最后一签）
        final boolean effective = status == ContractStatus.PARTIALLY_SIGNED;
        final ContractStatus target = effective
                ? ContractStatus.EFFECTIVE : ContractStatus.PARTIALLY_SIGNED;
        final int moved;
        if (effective) {
            moved = jdbc.sql("UPDATE contract SET status = ?, effective_at = ? "
                            + "WHERE id = ? AND status = ?")
                    .param(target.name()).param(command.signedAt()).param(command.contractId())
                    .param(status.name()).update();
        } else {
            moved = jdbc.sql("UPDATE contract SET status = ? WHERE id = ? AND status = ?")
                    .param(target.name()).param(command.contractId()).param(status.name()).update();
        }
        if (moved != 1) {
            // 条件更新影响行数 = 1 才算本次状态转移（hifi §5 W9）；未命中 → 整事务回滚（签名行不残留）
            throw new IllegalStateException("签署状态转移未命中: contractId=" + command.contractId()
                    + ", 期望状态=" + status + ", 角色=" + command.role());
        }
        insertLog(withToValue(command.signLog(), target.name()));
        if (effective) {
            insertAttestation(command);
            insertLog(command.attestLog());
        }
        return effective ? SignOutcome.EFFECTED : SignOutcome.FIRST_SIGNED;
    }

    @Override
    @Transactional
    public TerminateOutcome terminate(final TerminateCommand command) {
        final ContractStatus status = readContractForUpdate(command.contractId()).status();
        if (!command.fromStates().contains(status)) {
            return TerminateOutcome.STATE_CONFLICT;
        }
        final int moved = jdbc.sql("UPDATE contract SET status = ?, termination_type = ?, "
                        + "termination_reason = ?, terminated_by = ?, ended_at = ? "
                        + "WHERE id = ? AND status = ?")
                .param(ContractStatus.TERMINATED.name()).param(command.terminationType().name())
                .param(command.reason()).param(command.terminatedBy()).param(command.at())
                .param(command.contractId()).param(status.name()).update();
        if (moved != 1) {
            // 条件更新影响行数 = 1（hifi §5 W8/W10/W12）；未命中 → 整事务回滚（不得留痕成功转移）
            throw new IllegalStateException("终止状态转移未命中: contractId=" + command.contractId()
                    + ", 期望状态=" + status);
        }
        insertLog(withToValue(command.log(), ContractStatus.TERMINATED.name()));
        return TerminateOutcome.TERMINATED;
    }

    @Override
    @Transactional
    public ReleaseOutcome releaseConsent(final ReleaseCommand command) {
        final ContractStatus status = readContractForUpdate(command.contractId()).status();
        if (status != ContractStatus.EFFECTIVE) {
            return ReleaseOutcome.STATE_CONFLICT;
        }
        final String consentColumn = "release_consent_"
                + command.role().name().toLowerCase() + "_at";
        final int marked = jdbc.sql("UPDATE contract SET " + consentColumn + " = ? "
                        + "WHERE id = ? AND " + consentColumn + " IS NULL")
                .param(command.at()).param(command.contractId()).update();
        if (marked != 1) {
            return ReleaseOutcome.DUPLICATE_CONSENT;
        }
        // 双方齐 → 终态已完结（ended_at = 第二方确认时间；留痕第二方 to = COMPLETED）
        final Contract contract = jdbc.sql("SELECT " + CONTRACT_COLUMNS + " FROM contract "
                        + "WHERE id = ?")
                .param(command.contractId())
                .query((rs, rowNum) -> mapContract(rs)).single();
        final boolean both = contract.releaseConsentProviderAt() != null
                && contract.releaseConsentRequesterAt() != null;
        if (both) {
            // 条件更新影响行数 = 1（行锁串行化下不可达断言腿——hifi 勘误⑧）：未命中 → 整事务回滚
            final int completed = jdbc.sql("UPDATE contract SET status = ?, ended_at = ? "
                            + "WHERE id = ? AND status = ?")
                    .param(ContractStatus.COMPLETED.name()).param(command.at())
                    .param(command.contractId()).param(ContractStatus.EFFECTIVE.name()).update();
            if (completed != 1) {
                throw new IllegalStateException("合意解除完结转移未命中: contractId="
                        + command.contractId());
            }
        }
        insertLog(withToValue(command.log(), both
                ? ContractStatus.COMPLETED.name() : ContractStatus.EFFECTIVE.name()));
        return both ? ReleaseOutcome.COMPLETED : ReleaseOutcome.CONSENT_RECORDED;
    }

    @Override
    public void insertLog(final ContractActionLog log) {
        jdbc.sql("INSERT INTO contract_action_log (contract_no, version_no, action, "
                        + "actor_subject_no, reason_code, from_value, to_value) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(log.contractNo()).param(log.versionNo()).param(log.action().name())
                .param(log.actorSubjectNo()).param(log.reasonCode())
                .param(log.fromValue()).param(log.toValue()).update();
    }

    // ==== 内部：行锁读 / 版本行写入 / 分页 / 映射 ====

    /** FOR UPDATE 读回的合约行状态（行级锁串行化点——hifi §5 W7 后到者读回口径）。 */
    private record LockedContractState(ContractStatus status, int currentClauseVersion) {
    }

    /**
     * FOR UPDATE 读合约行：同合约全部写事务在此串行化；状态与当前版本指针一并读回
     * （确认侧复判依据——评审循环 2 P0：锁下只读状态不足以防"确认陈旧版本"交错）。
     */
    private LockedContractState readContractForUpdate(final long contractId) {
        return jdbc.sql("SELECT status, current_clause_version FROM contract "
                        + "WHERE id = ? FOR UPDATE")
                .param(contractId)
                .query((rs, rowNum) -> new LockedContractState(
                        ContractStatus.valueOf(rs.getString(1)), rs.getInt(2)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("合约不存在: id=" + contractId));
    }

    private ContractClauseVersion readVersionForUpdate(final long contractId, final int versionNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM contract_clause_version "
                        + "WHERE contract_id = ? AND version_no = ? FOR UPDATE")
                .param(contractId).param(versionNo)
                .query((rs, rowNum) -> mapVersion(rs))
                .single();
    }

    private void insertVersionRow(final ContractClauseVersion version) {
        jdbc.sql("INSERT INTO contract_clause_version (contract_id, version_no, "
                        + "clause_values_cipher, changes_cipher, canonical_cipher, content_hash, "
                        + "proposed_by, proposed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .param(version.contractId()).param(version.versionNo())
                .param(version.clauseValuesCipher()).param(version.changesCipher())
                .param(version.canonicalCipher()).param(version.contentHash())
                .param(version.proposedBy()).param(version.proposedAt()).update();
    }

    private void insertAttestation(final SignCommand command) {
        final List<ContractSignature> signatures = listSignatures(command.contractId());
        final ContractSignature provider = signatureOf(signatures, PartyRole.PROVIDER);
        final ContractSignature requester = signatureOf(signatures, PartyRole.REQUESTER);
        jdbc.sql("INSERT INTO contract_attestation (contract_id, contract_no, content_hash, "
                        + "provider_subject_no, provider_did, provider_signed_at, "
                        + "requester_subject_no, requester_did, requester_signed_at, attested_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(command.contractId()).param(command.signLog().contractNo())
                .param(command.contentHash())
                .param(provider.subjectNo()).param(provider.did()).param(provider.signedAt())
                .param(requester.subjectNo()).param(requester.did()).param(requester.signedAt())
                .param(command.signedAt()).update();
    }

    private ContractSignature signatureOf(final List<ContractSignature> signatures,
            final PartyRole role) {
        return signatures.stream().filter(s -> s.partyRole() == role).findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 " + role + " 签署行"));
    }

    private ContractActionLog withToValue(final ContractActionLog log, final String toValue) {
        return new ContractActionLog(log.id(), log.contractNo(), log.versionNo(), log.action(),
                log.actorSubjectNo(), log.reasonCode(), log.fromValue(), toValue, log.createdAt());
    }

    private PageResult<Contract> pageContracts(final String where, final List<Object> params,
            final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM contract WHERE " + where)
                .params(params).query(Long.class).single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<Contract> list = jdbc.sql("SELECT " + CONTRACT_COLUMNS
                        + " FROM contract WHERE " + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapContract(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private Contract mapContract(final ResultSet rs) throws SQLException {
        final String terminationType = rs.getString("termination_type");
        return new Contract(rs.getLong("id"), rs.getString("contract_no"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getString("provider_subject_no"),
                rs.getString("requester_subject_no"), rs.getString("template_no"),
                rs.getInt("template_version_no"), rs.getString("pricing_model"),
                rs.getBigDecimal("price_amount"), rs.getInt("current_clause_version"),
                ContractStatus.valueOf(rs.getString("status")),
                terminationType == null ? null : TerminationType.valueOf(terminationType),
                rs.getString("termination_reason"), rs.getString("terminated_by"),
                toLocalDateTime(rs, "effective_at"), toLocalDateTime(rs, "ended_at"),
                toLocalDateTime(rs, "release_consent_provider_at"),
                toLocalDateTime(rs, "release_consent_requester_at"),
                rs.getString("created_by"), toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at"));
    }

    private ContractClauseVersion mapVersion(final ResultSet rs) throws SQLException {
        return new ContractClauseVersion(rs.getLong("id"), rs.getLong("contract_id"),
                rs.getInt("version_no"), rs.getBytes("clause_values_cipher"),
                rs.getBytes("changes_cipher"), rs.getBytes("canonical_cipher"),
                rs.getString("content_hash"), rs.getString("proposed_by"),
                toLocalDateTime(rs, "proposed_at"),
                toLocalDateTime(rs, "confirmed_provider_at"),
                toLocalDateTime(rs, "confirmed_requester_at"));
    }

    private ContractSignature mapSignature(final ResultSet rs) throws SQLException {
        return new ContractSignature(rs.getLong("id"), rs.getLong("contract_id"),
                rs.getString("contract_no"), PartyRole.valueOf(rs.getString("party_role")),
                rs.getString("subject_no"), rs.getString("did"), rs.getString("content_hash"),
                rs.getBytes("signature_cipher"), toLocalDateTime(rs, "signed_at"),
                toLocalDateTime(rs, "created_at"));
    }

    private ContractAttestation mapAttestation(final ResultSet rs) throws SQLException {
        return new ContractAttestation(rs.getLong("id"), rs.getLong("contract_id"),
                rs.getString("contract_no"), rs.getString("content_hash"),
                rs.getString("provider_subject_no"), rs.getString("provider_did"),
                toLocalDateTime(rs, "provider_signed_at"), rs.getString("requester_subject_no"),
                rs.getString("requester_did"), toLocalDateTime(rs, "requester_signed_at"),
                toLocalDateTime(rs, "attested_at"));
    }

    private static LocalDateTime toLocalDateTime(final ResultSet rs, final String column)
            throws SQLException {
        final Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
