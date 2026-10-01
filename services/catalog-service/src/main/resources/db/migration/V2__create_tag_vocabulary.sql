-- 语义标签受控词表（WBS-3.3.3，hifi §3 定稿；规格 docs/specs/C-3.1-2.3-数据目录与资源.md V1.0
-- 行为 1 规则 3「语义标签（受控词表选取）」+ §末未定义项「语义标签词表集合归 3.3.3」）。
-- 两表承载：tag_vocabulary 词表册（本版恒 SEMANTIC_TAG，vocabulary_code 预留多册扩展）、
-- tag_term 词条（含归一化名 normalized_term = 成员校验匹配口径唯一来源，由应用侧经
-- DatasetNameNormalizer 产出后写入；DB 不归一化）。
-- V1 四表零改动：不新增列、不改语义、不回填既有 dataset.semantic_tags（Q2-A/Q6-A）。
-- 匹配口径前提：各落 MySQL 8 默认 utf8mb4_0900_ai_ci（大小写不敏感）——过阻断方向非绕过。
-- V1.0 无词表维护写面（Q7-A）：数据只插不改，故两表均无 updated_at、无状态列、无排序列。
-- 数据分级落级（WBS-3.3.3 hifi §6，分级规范 §6.1 同步行）：词表册与词条 = CAT-03 L1
-- （公开目录词汇，不含主体信息、个人信息与数据本体）。

CREATE TABLE tag_vocabulary (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    vocabulary_code VARCHAR(32)  NOT NULL COMMENT '词表码（本版恒 SEMANTIC_TAG 语义标签册；预留多册扩展）',
    vocabulary_name VARCHAR(64)  NOT NULL COMMENT '词表名（展示用）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（无维护写面故无 updated_at）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_code (vocabulary_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '语义标签受控词表册（行为1规则3受控词表选取；V1.0 随迁移内置、只读面，无维护写面）';

CREATE TABLE tag_term (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    vocabulary_id   BIGINT      NOT NULL COMMENT '所属词表册 id（同库引用 tag_vocabulary.id，不建外键）',
    term_code       VARCHAR(32) NOT NULL COMMENT '词条业务编号（TT+4 位序号；沿 data_no/subject_no 业务编号与技术主键分离先例）',
    term_name       VARCHAR(64) NOT NULL COMMENT '词条名称（展示与语义标签落库原文）',
    normalized_term VARCHAR(64) NOT NULL COMMENT '归一化词条名（应用侧 DatasetNameNormalizer 产出，成员校验比对口径）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_term_code (term_code),
    UNIQUE KEY uk_vocab_norm_term (vocabulary_id, normalized_term)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '受控词条（归一化名经应用侧 DatasetNameNormalizer 产出；同册归一化名唯一 = 成员校验口径唯一来源）';

-- 种子词条（Q4 定稿 12 条）：TT0001~TT0003 = 3.3.2 走查与集成测试实际使用的标签（金融/普惠/风控），
-- 必须包含——否则既有演示资源变更时会撞上本卡新校验；TT0004~TT0012 为建议集。
-- 词表内容为业务事项：改词表 = 改本段种子行（DTO/接口零改动）。
INSERT INTO tag_vocabulary (vocabulary_code, vocabulary_name) VALUES ('SEMANTIC_TAG', '语义标签受控词表');

INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0001', '金融', '金融' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0002', '普惠', '普惠' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0003', '风控', '风控' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0004', '医疗健康', '医疗健康' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0005', '交通出行', '交通出行' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0006', '政务', '政务' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0007', '企业服务', '企业服务' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0008', '统计分析', '统计分析' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0009', '公开数据', '公开数据' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0010', '脱敏数据', '脱敏数据' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0011', '信用', '信用' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term)
SELECT id, 'TT0012', '地理空间', '地理空间' FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG';
