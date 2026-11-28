ALTER TABLE dataset_action_log
    MODIFY COLUMN actor_subject_no VARCHAR(128) NOT NULL COMMENT '操作者主体编号；与认证 subject ≤128 契约对齐',
    MODIFY COLUMN space_id BIGINT NULL COMMENT '归属空间 id；对象不存在性拒绝审计为 NULL',
    ADD COLUMN dedup_key VARCHAR(255) COLLATE utf8mb4_bin NULL COMMENT '拒绝审计去重键：主体+对象+动作+原因+UTC分钟',
    ADD UNIQUE KEY uk_dataset_action_denial_dedup (dedup_key);
