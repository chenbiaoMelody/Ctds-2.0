# WBS-2.5.3 监控告警基座 — 高保真设计（定稿确认稿 = 编码契约）

- 状态：**待 PO 定稿确认**（确认后进入编码；与本稿不一致 = 打回项）
- 低保真：`docs/designs/WBS-2.5.3-lofi.md`（方向已确认，2026-09-12"都按推荐"：部署形态 A 朴素清单三件套 / 指标范围 A 仅平台服务 / 交付通道 A 独立 monitoring.ps1 / 演练接受 / 单卡交付接受 / 依赖 5 项批准）

## 1. 部署拓扑契约（全部落 `deploy/k8s-monitoring/`，kubectl apply 幂等）

命名空间 `ctds-monitoring`；三组件均为 1 副本 Deployment + ClusterIP Service + ConfigMap 配置；数据全部 Pod 本地盘（emptyDir，重启清零）。

| 组件 | 镜像（锁版） | 端口 | 关键参数 | 存储 |
| --- | --- | --- | --- | --- |
| ctds-prometheus | `prom/prometheus:v3.13.3` | 9090 | `--storage.tsdb.retention.time=24h`；抓取间隔 15s | emptyDir |
| ctds-alertmanager | `prom/alertmanager:v0.34.0` | 9093 | 路由配置见 §5 | emptyDir |
| ctds-grafana | `grafana/grafana:13.2.1` | 3000 | 匿名只读开启（Viewer）；数据源/看板预配自动装载 | emptyDir |

- 访问口径（沿 ADR-013 §8.5）：base 模板一律 ClusterIP；`monitoring.ps1` 部署后对**副本内** Grafana Service patch `NodePort 30082`，并拉起宿主 port-forward `30082→3000`（脚本退出后存活，`-Teardown` 回收）；Prometheus/Alertmanager 仅在脚本冒烟期间临时转发（9090/9093→本地随机口），冒烟完即回收，日常排查用 runbook 给出的手动转发命令。
- Grafana 账号：匿名 Viewer 免登录看板（业务人员零门槛）；admin 留 Grafana 出厂默认（首次登录强制改密，**任何口令不落仓库**——红线；生产化任务改接配置中心）。

## 2. 后端指标暴露契约（B1）

| 项 | 契约 |
| --- | --- |
| pom 新增 | `org.springframework.boot:spring-boot-starter-actuator`、`io.micrometer:micrometer-registry-prometheus`（均 Boot 3.5.16 BOM 托管，不显式锁版；实施时 `mvn dependency:tree` 实测解析留痕） |
| application.yml | `management.endpoints.web.exposure.include: health,prometheus`（**最小暴露面**，其余 actuator 端点一律不开） |
| 鉴权口径 | 零代码改动：common-auth 为注解式按端点强制（PermissionInterceptor，无注解默认放行），actuator 端点无注解天然放行；**不得**为指标端点新增加密/鉴权逻辑 |
| 部署注解（backend-deployment.yaml pod template metadata） | `prometheus.io/scrape: "true"`、`prometheus.io/port: "8080"`、`prometheus.io/path: "/actuator/prometheus"`（静态注解，无占位符，不碰注入工具链） |
| 测试（先写失败测试） | MockMvc 三断言：`/actuator/prometheus` 200 且含 `jvm_memory_used_bytes` 与 `http_server_requests` 指标文本；`/actuator/health` 200；`/actuator/env` 404（未暴露证明） |

## 3. Prometheus 抓取与告警规则契约（B2/B4）

**抓取发现**（prometheus.yml）：`kubernetes_sd_configs` role=pod 全命名空间 → 按 §2 三注解过滤并拼抓取目标 → relabel：`job` 取 Pod 标签 `app.kubernetes.io/name`（ctds-backend 抓取目标的 job 即 `ctds-backend`）；Prometheus 自身抓取一条静态目标。

| 规则 | 表达式（要点） | for | 级别 |
| --- | --- | --- | --- |
| CtdsBackendDown | `up{job="ctds-backend"} == 0 or absent(up{job="ctds-backend"})`（absent 覆盖"副本缩 0/目标消失"场景，保证演练可确定性触发） | 1m | critical |
| CtdsBackendHighErrorRate | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.05`（总量为 0 时结果 NaN 不触发=安全侧） | 5m | warning |

## 4. Alertmanager 路由契约（B5）

`alertmanager.yml`：默认路由 receiver=`ctds-default`（空动作 receiver——告警在 UI/API 可见可分组可静默，v1 不对外发送）；`group_by: [alertname]`、`group_wait: 10s`、`group_interval: 5m`、`repeat_interval: 1h`（开发环境短周期便于演示）。分组=同 alertname 归并；静默=经 API 创建/删除验证（见 §6 步骤 5）；真实通知渠道（邮件/webhook 出网）留生产任务，ADR-014 登记扩展点。

## 5. Grafana 看板契约（B3）

数据源：Prometheus，url `http://ctds-prometheus.ctds-monitoring.svc:9090`， provisioning 自动装载（不手工点配置）。看板《C-TDS 平台基础看板》四区块：

| 区块 | 图型 | PromQL 要点 |
| --- | --- | --- |
| ① 服务存活 | Stat（1=绿） | `up{job="ctds-backend"}` |
| ② JVM 内存/GC | 时序 | `jvm_memory_used_bytes{area="heap"}` 按 id 分组 + `rate(jvm_gc_pause_seconds_sum[5m])` |
| ③ HTTP 请求速率 | 时序（QPS） | `sum(rate(http_server_requests_seconds_count[1m]))` |
| ④ 错误率与时延 | 时序 | 5xx 占比 + `quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket[5m])))` |

## 6. `scripts/deploy/monitoring.ps1` 步骤验收判据（B6~B8）

| 步 | 动作 | PASS 判据 |
| --- | --- | --- |
| 1 前提自检 | kubectl 可达；`docker images` 三镜像在位；**default 命名空间存在 ctds-backend Deployment**（看板数据与告警演练依赖应用，缺失则打印"先跑 deploy.ps1"并停止，不代部署） | 全过，否则报告列明确原因 |
| 2 部署 | `kubectl apply -f deploy/k8s-monitoring/`（文件清单固定）+ 三 Deployment `rollout status`（超时 120s/个） | 三者 Available |
| 3 指标链路冒烟 | 临时转发 9090 → Prometheus API 查 `up{job="ctds-backend"}` | 返回值=1（转发完回收） |
| 4 看板冒烟 | 副本 patch NodePort 30082 + 转发 30082→3000（**常驻**）→ 匿名请求 Grafana `/api/health` 与看板页面 | 两个 HTTP 200 |
| 5 告警演练 | `kubectl scale` 后端缩 0 → 轮询 Prometheus `/api/v1/alerts` 等 CtdsBackendDown=firing（上限 5 分钟）→ Alertmanager `/api/v2/alerts` 可见同告警 → 创建静默再删除（API 往返成功）→ 后端恢复 1 副本并等 rollout → 轮询等 resolved | 每步断言成立，全程输出留痕 |
| 6 报告落盘 | `build-output/monitoring/<时间戳>/monitoring-report.md`（逐步 PASS/FAIL + 末行 `[MONITOR] PASS` 字面量，沿 2.5.2 报告口径） | 文件存在且全 PASS |
| -Teardown | 删 ctds-monitoring 命名空间 + 回收本脚本草图 port-forward（回收前校验进程名为 kubectl，防 PID 重用误杀——沿 2.5.2 教训）+ `teardown-report.md` | `kubectl get all -n ctds-monitoring` 无资源，退 0 |

## 7. 行为边界值表（细化 lofi §5）

| 边界 | 契约值/行为 |
| --- | --- |
| 抓取间隔 / 数据保留 | 15s / 24h（emptyDir，Pod 重建清零=开发环境已知边界） |
| 告警触发判定窗 | Down: 异常持续 1m；错误率: >5% 持续 5m；演练 firing 轮询上限 5 分钟（含 absent 生效时延） |
| rollout 超时 | 120s/Deployment，超时即 FAIL 不重试（报告留 Pod 事件摘录） |
| 幂等复跑 | apply 覆盖式 + NodePort patch 幂等；重复跑不报错 |
| 演练期间影响 | 后端缩 0 约 1~2 分钟，仅本机演示环境（lofi 问题 4 已批准） |
| 端口占用 | 30082 被占 → 自检步提示占用者，与 2.5.2 口径一致 |

## 8. 规格行为 → 设计 → 测试/验证 → 剧本 映射表

| lofi 行为 | hifi 契约 | 验证载体 | runbook |
| --- | --- | --- | --- |
| B1 指标端点 | §2 | MetricsEndpointTest 3 断言 + §6 步骤 3 | §6 监控章节 |
| B2 采集 | §3 | §6 步骤 3 | 同上 |
| B3 看板 | §5 | §6 步骤 4 | 同上 |
| B4 规则 | §3 | §6 步骤 5（firing 实证） | 同上 |
| B5 路由 | §4 | §6 步骤 5（Alertmanager 可见 + 静默往返） | 同上 |
| B6 演练 | §6 步骤 5 | monitoring-report.md | 同上 |
| B7 一键脚本 | §6 | 脚本自身 PASS/Teardown | 部署章节补 monitoring.ps1 入口 |
| B8 访问口径 | §1 | §6 步骤 4 | 同上 |
| B9 依赖登记 | lofi §2 | dependencies.md 新行（Maven 2 行 + 容器镜像节 3 行） | — |
| B10 ADR-014 | 全文 | ADR-014 随分支提交 | — |

## 9. 交付物清单与体量

`deploy/k8s-monitoring/`（6 清单 ≈350 行）、`scripts/deploy/monitoring.ps1`（≈300 行）、`runbook.md` 新章节、`docs/adr/ADR-014-监控告警基座规范.md`、`docs/dependencies.md` 登记、后端 pom/yml/部署模板小改、`MetricsEndpointTest`。合计净增约 1000~1200 行（lofi 问题 5 已批准单卡交付）。测试先行：先提交失败测试（§2 断言）经确认再实现。

## 10. 定稿确认清单

1. §1~§7 契约值（锁版、阈值、超时、端口、演练窗口）是否照此执行？
2. Grafana 匿名只读 + admin 出厂默认（生产化再接配置中心）口径是否接受？
3. monitoring.ps1 检测到应用未部署时"提示先跑 deploy.ps1 并停止"（不代部署）是否认可？
4. 测试先行两步走（失败测试先提交确认）是否照旧？
