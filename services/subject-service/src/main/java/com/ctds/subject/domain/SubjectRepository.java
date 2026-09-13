package com.ctds.subject.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 主体仓储（领域层接口，infrastructure 提供 MySQL 实现）。
 * 写方法一律要求携带 StatusTransition 参数——流转留痕不可绕过（hifi B7：一切流转强制落留痕）。
 */
public interface SubjectRepository {

    /** 新建主体档案并落初始流转留痕（同事务，保证"档案 + 留痕要么都有要么都没有"）。 */
    void create(Subject subject, StatusTransition initialTransition);

    /** 重报：更新可变信息、重置状态并落流转留痕（同事务）。 */
    void resubmit(Subject updated, StatusTransition transition);

    /** 追加一条流转留痕（撤销等无字段变化的流转）。 */
    void appendTransition(long subjectId, StatusTransition transition);

    Optional<Subject> findBySubjectNo(String subjectNo);

    Optional<Subject> findByUscc(String uscc);

    /** 最新一条流转留痕（撤销标记判定 = 最新留痕是否为"申请人撤销"口径，见 SubjectStatusService）。 */
    Optional<StatusTransition> findLatestTransition(long subjectId);

    List<StatusTransition> findTransitions(long subjectId);

    /** 申请编号当日序号原子自增（1 起），返回取到的序号。 */
    int nextDailySeq(LocalDate date);
}
