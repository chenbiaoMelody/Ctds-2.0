# WBS-2.5.3 监控告警基座 — 低保真设计（方向确认稿）

- 任务行：`docs/C-TDS项目总体实施计划与WBS.md` L215——**2.5.3 监控告警基座：指标采集、基础看板、告警路由**（依赖 2.5.1 已满足；1 会话）
- 状态：**方向确认通过（2026-09-12 23 时，PO"都按推荐"六问全部采纳：1A/2A/3A/4 接受/5 接受/6 批准）→ 高保真见 `WBS-2.5.3-hifi.md`**；**验收通过，合并（2026-09-13 09:29，PO：演示 30082 看板四区块正常）**
- 关联：ADR-013（§53"业务可用性监控在 2.5.3 做"；§8.5 访问口径 port-forward）、2.5.2 交付物（deploy.ps1 / runbook / deploy/k8s 模板）、复用集群 desktop-control-plane（Ready，应用零残留）

## 1. 范围（做什么 / 不做什么）

**做**（对齐任务行三要素）：
1. **指标采集**：平台服务（example-service）暴露标准指标端点；集群内 Prometheus 自动发现并持续抓取；
2. **基础看板**：Grafana 预配数据源与一块"平台基础看板"（服务存活、JVM 内存/GC、HTTP 请求速率/错误率/耗时）；
3. **告警路由**：Prometheus 告警规则（≥2 条）→ Alertmanager 接收 → 路由（分组/抑制/静默）→ 告警在界面可见；含一次端到端告警演练（人为制造故障 → firing → 恢复 → resolved，报告留痕）。

**不做**（边界，登记后续）：
- 生产级持久化/高可用：数据存 Pod 本地盘（重启清零，开发/测试基线够用）；单副本；
- **真实对外通知**（邮件/短信/出网 webhook）：本机开发环境无外部通道，v1 告警"可见可路由"，真实发送渠道留生产部署任务；
- 集群层监控（节点/集群组件指标）是否纳入 → 见 §3 问题 2；
- 不改 2.5.2 已验证部署链路的行为（见 §3 问题 3）。

## 2. 新依赖申报（流程审批材料，章程 3.5）

> 核验方式说明：本机网络直连 registry-1.docker.io 不通（实测超时），Docker Desktop 守护进程经已配置镜像加速器（daocloud 等）拉取实测成功 = 最硬核验（拉不下来的 tag 谈不上可用）；版本线以官方发布源（GitHub Releases / 官方下载页）交叉确认。

| 依赖 | 拟锁定版本 | 用途 | 许可证 | 核验结果 | 引入方式 |
| --- | --- | --- | --- | --- | --- |
| `prom/prometheus`（镜像） | v3.13.3（3.x 当前 **LTS**；3.14 为最新普通版，按"基座求稳"取 LTS） | 指标采集与时序库 | Apache-2.0 | **docker pull 实测成功**，digest `sha256:6976aa8…`（2026-09-12）；版本线：prometheus.io/download + endoflife.date/prometheus | K8s 部署清单，版本写死在模板 |
| `prom/alertmanager`（镜像） | v0.34.0（GitHub Releases 最新稳定） | 告警路由/分组/静默 | Apache-2.0 | **docker pull 实测成功**，digest `sha256:690c7b5…`（2026-09-12）；prometheus/alertmanager Releases | 同上 |
| `grafana/grafana`（镜像） | 13.2.1（当前稳定，2026-09-01；13.2 标准支持至 2027-05） | 基础看板 | AGPL-3.0（自部署使用无传染问题，不修改其源码分发） | **docker pull 实测成功**，digest `sha256:f772d434…`（2026-09-12；daocloud 通道卡死，经 1ms 加速域名拉取后 retag 回官方坐标 `grafana/grafana:13.2.1`，内容同一 digest）；版本线：grafana.com/download + Releases | 同上 |
| `org.springframework.boot:spring-boot-starter-actuator` | 由 Spring Boot 3.5.16 BOM 管理（pom 不显式锁版，沿登记簿惯例） | 后端暴露指标/健康端点（Spring Boot 官方 starter） | Apache-2.0 | Boot BOM 托管（与项目父版本一致）；实施时 dependency:tree 实测解析留痕 | example-service pom |
| `io.micrometer:micrometer-registry-prometheus` | 由 Spring Boot 3.5.16 BOM 管理 | 把 JVM/HTTP 指标转成 Prometheus 抓取格式（Boot 官方集成，Micrometer 同族） | Apache-2.0 | 同上 | 同上 |

登记动作：审批通过后逐行写入 `docs/dependencies.md`（Maven 表 + 新开"容器镜像依赖"小节）。

## 3. 方向选型：业务影响对比表（请 PO 裁决）

**问题 1：监控栈的部署形态**

| 方案 | 是什么 | 业务收益 | 业务代价/风险 | 判定 |
| --- | --- | --- | --- | --- |
| **A（推荐）朴素清单三件套** | Prometheus + Alertmanager + Grafana 各一个部署，YAML 模板落 `deploy/k8s-monitoring/`，风格与 ADR-013 一致 | 组件最少（3 个 Pod）、资源占用小，单机 Docker Desktop 跑得动；与现有部署模板/规范同风格，维护成本最低；1 会话可交付 | 没有"自动接新服务"的 Operator，新增被监控服务要按注解规范加两行注解（会写进模板规范，操作简单） | ✅ |
| B Helm 全家桶（kube-prometheus-stack） | 业界常用的 Helm 图，含 Operator + 节点采集 + 集群指标等十余组件 | 功能全、社区主流 | 需先引入 **Helm 工具本身**（新工具+新流程审批）；十余个 CRD、10+ Pod，单机内存吃紧；与 ADR-013 朴素清单路线分叉，1 会话装不下 | ❌ 过重 |
| C Grafana 内置告警替代独立 Alertmanager | 少部署 1 个组件，用 Grafana 自己的告警功能 | 组件少一个 | 偏离 Prometheus 教科书组合；告警路由规则被锁进 Grafana，将来生产对接通知渠道要整体迁移，返工成本高 | ❌ 返工风险 |

**问题 2：指标采集范围**

| 方案 | 覆盖 | 业务收益 | 业务代价 |
| --- | --- | --- | --- |
| **A（推荐）仅平台服务指标** | example-service 的存活/JVM/HTTP 指标 | 贴合"平台监控"目标（WBS 3.9.4 也只要求服务健康度）；工作量 1 会话可控 | 看不到集群自身健康（节点压力等） |
| B 平台服务 + 集群层（加 node-exporter + kube-state-metrics） | 再加节点/集群组件指标 | 集群健康也可见 | +2 组件、看板工作量约翻倍，本卡 1 会话装不下；且集群本身由 Docker Desktop 托管，价值有限 | 

若选 B，建议拆到后续任务（或并入 3.9.4），本卡先落 A 打好基座。

**问题 3：交付通道（部署脚本怎么给）**

| 方案 | 做法 | 业务收益 | 业务代价 |
| --- | --- | --- | --- |
| **A（推荐）独立脚本 `scripts/deploy/monitoring.ps1`** | 监控栈自己的部署/验收/Teardown 脚本，报告落 `build-output/monitoring/<时间戳>/` | 不碰 2.5.2 已验证的 deploy.ps1（零回归风险）；监控栈生命周期独立（可以只拆监控不拆应用） | 多一个脚本入口（runbook 写清楚即可） |
| B 扩展 deploy.ps1 加开关 | 一键部署时顺带装监控 | 入口统一 | 改动已验证脚本，回归风险；监控栈与应用生命周期被绑死 |

**问题 4：告警演练方式**（端到端演示需要一次真实告警）

拟采用：把平台后端副本临时缩到 0（本机演示环境，约 1~2 分钟不可用）→"服务下线"告警 firing → 恢复副本 → resolved，全程报告留痕。**请确认接受这个短暂不可用**（仅本机开发环境，不涉任何人使用）。

**问题 5：交付体量预告**：监控栈清单 + 看板 JSON + 脚本 + runbook 更新，预计净增 600~900 行，超 400 行/PR 惯例上限——沿 2.5.2 惯例单卡整体交付、合并裁决时提请您知悉。

## 4. 行为清单（业务可读，编码契约在 hifi 细化）

| 编号 | 行为 | 验证方式 |
| --- | --- | --- |
| B1 | 平台后端暴露指标端点 `/actuator/prometheus`，含存活/JVM/HTTP 指标；除该端点与健康检查外不放开其他管理端点 | curl 有指标文本；其他 actuator 端点按既有鉴权口径拒绝 |
| B2 | 集群部署 Prometheus（ctds-monitoring 命名空间），按注解自动发现平台后端 Pod 并抓取，数据保留 24 小时 | Prometheus UI/接口里能查到 ctds-backend 指标 |
| B3 | Grafana 预配好 Prometheus 数据源与"平台基础看板"（服务存活、JVM 内存/GC、HTTP 速率/错误率/耗时四区块），打开即有图无手工配置 | 浏览器打开看板，四个区块有数据 |
| B4 | 告警规则 ≥2 条：服务下线（抓不到目标）、HTTP 错误率异常（阈值见 hifi） | 规则列表两条且为"正常"状态 |
| B5 | 告警路由到 Alertmanager：分组/静默能力可用，告警在界面可见、带路由标签 | 制造故障后 Alertmanager UI 出现 firing 告警 |
| B6 | 端到端演练：后端缩 0 → firing → 恢复 → resolved，全程自动留痕 | 演练报告 `build-output/monitoring/<时间戳>/` 各步 PASS |
| B7 | 一键脚本 `monitoring.ps1`：前提自检（集群/三个镜像）→ 部署 → 就绪等待 → 冒烟（指标端点/看板/告警链路）→ PASS + 报告落盘；`-Teardown` 一键清场 | 脚本 PASS；Teardown 后 `kubectl get all -n ctds-monitoring` 无资源 |
| B8 | 监控入口对人不设新门槛：Grafana 走 30082（副本 NodePort patch + port-forward，沿 ADR-013 §8.5 口径），runbook 补"监控怎么看"章节 | runbook 步骤可照做 |
| B9 | 依赖登记：审批通过后 `docs/dependencies.md` 登记 5 项（§2） | 登记簿新行齐全 |
| B10 | ADR-014《监控告警基座规范》立项：固化组件版本线、抓取注解规范、告警规则口径、访问口径 | ADR 落盘随分支提交 |

## 5. 异常与边界行为

| 场景 | 行为 |
| --- | --- |
| 集群未就绪 / Docker Desktop 未开 | 脚本前提自检失败即停，提示按 runbook §1 处理（沿 2.5.2 口径） |
| 三个监控镜像任一缺失 | 自检列出确切 tag 与拉取命令，不盲目起部署 |
| 后端指标端点被鉴权拦截（误配置） | 冒烟阶段抓取校验失败即 FAIL，不静默降级 |
| Prometheus 数据盘满（开发环境重启即清，风险低） | 保留期 24h 兜底；生产化任务再谈持久化 |
| 重复执行 monitoring.ps1 | apply 幂等覆盖，不报资源已存在（沿 2.5.2 口径） |
| Grafana 首次打开要求改密码 | 预置只读查看账号口径在 hifi 定（开发/测试环境简化） |

## 6. 问题确认清单（请逐条回复）

1. 问题 1 部署形态：**A / B / C**？（推荐 A）
2. 问题 2 指标范围：**A / B**？（推荐 A，B 登记后续）
3. 问题 3 交付通道：**A / B**？（推荐 A）
4. 问题 4 演练制造短暂故障：**接受 / 不接受**？
5. 问题 5 交付体量（超 400 行单卡交付）：**接受 / 拆分**？
6. §2 依赖申报 5 项：**批准引入 / 有异议**？

> PO 确认本稿（方向）后，出高保真（接口契约表 + 边界值细化 + 看板区块定义 + 脚本验收判据），经定稿确认后进入编码。
