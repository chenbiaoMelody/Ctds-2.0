-- WBS-3.7.4 产品评价系统迁移（hifi §2）：
-- product_review = 产品评价（一人一产品一评；隐藏留痕不物理删除）；
-- product_review_action_log = 评价域动作留痕（提交/更新/隐藏全程）。
-- 评价资格判定走既有 delivery 表（零结构改动）：requester_no + product_id + result='SUCCESS' EXISTS。

CREATE TABLE product_review (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    product_id   BIGINT       NOT NULL COMMENT '产品 id（沿 delivery.product_id 快照口径）',
    requester_no VARCHAR(32)  NOT NULL COMMENT '评价人（需方主体编号快照）',
    score        TINYINT      NOT NULL COMMENT '评分 1~5（服务端校验，B5-1）',
    comment      VARCHAR(500) NULL     COMMENT '可选文字评价',
    hidden       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=有效 1=已隐藏（留痕不物理删除，B5-4）',
    hidden_by    VARCHAR(32)  NULL     COMMENT '隐藏人（运营主体编号）',
    hidden_at    DATETIME     NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次提交时点（留痕可溯 B5-1）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_once (product_id, requester_no),
    KEY idx_product_visible (product_id, hidden, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品评价（一人一产品一评；隐藏留痕不删除）';

CREATE TABLE product_review_action_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    review_id    BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    requester_no VARCHAR(32)  NOT NULL,
    action       VARCHAR(16)  NOT NULL COMMENT 'CREATE / UPDATE / HIDE',
    actor_no     VARCHAR(32)  NOT NULL COMMENT '动作人（评价人 / 运营）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_review (review_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价域动作留痕（提交/更新/隐藏全程）';
