-- WBS 2.4.10 示例迁移 V2：灌入演示数据（V1 建表 → V2 灌数据，演示版本递进；Flyway 按 history 跳过已应用脚本，天然幂等）
INSERT INTO demo_note (title, content) VALUES
  ('迁移演示', '本行数据由 V2__seed_demo_note.sql 写入（Flyway 版本化管理演示）'),
  ('版本递进', 'V1 建表 → V2 灌数据：每次结构变更都是新版本脚本（ADR-009）');
