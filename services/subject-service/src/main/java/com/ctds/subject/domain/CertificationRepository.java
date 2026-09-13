package com.ctds.subject.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 认证仓储（领域层接口，infrastructure 提供 MySQL 实现；WBS-3.1.3）。
 * 证照材料替换语义 = 同主体同类型删旧插新（确认状态随之重置，hifi 库表设计）。
 */
public interface CertificationRepository {

    /** 替换式保存证照材料：删除该主体同类型既有记录后插入新行（同事务，保证"替换保留最近一次"）。 */
    CertMaterial replaceMaterial(CertMaterial material);

    /** 该主体指定类型最近一次上传的证照材料（无上传返回空）。 */
    Optional<CertMaterial> findLatestMaterial(long subjectId, String materialType);

    /** 更新核对确认要素与确认时间（仅覆盖确认字段，影像与 OCR 密文不动）。 */
    void updateConfirmation(long materialId, String confirmedName, String confirmedUscc,
            String confirmedLegalPerson, String confirmedRegAddress, LocalDateTime confirmedAt);

    /** 追加一条渠道调用留痕。 */
    void appendVerification(CertVerificationLog log);

    /** 该主体全部调用留痕（时间正序，认证档案展示用；身份证号等 L4 字段由调用方决定不回显）。 */
    List<CertVerificationLog> findVerifications(long subjectId);

    /** 当日（since 起）计入失败次数的核验调用数（规格行为 3 第 3 条：当日 5 次上限的计数口径）。 */
    int countFailuresSince(long subjectId, LocalDateTime since);
}
