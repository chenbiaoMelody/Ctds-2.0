# C-TDS 监控告警基座 K8s 清单（WBS 2.5.3，规范载体 ADR-014）

本目录声明监控三件套（Prometheus 指标采集 / Alertmanager 告警路由 / Grafana 基础看板）在 Kubernetes 上怎么跑。全部组件版本写死在镜像字段（禁 latest），部署与清理由 `scripts/deploy/monitoring.ps1` 一键完成（含镜像装进集群节点、冒烟、告警演练与报告落盘）；base 清单一律 ClusterIP，Grafana 的 NodePort 30082 由脚本对**存活对象** patch（本目录文件不改）。

## 清单清单

| 文件 | 内容 |
| --- | --- |
| `namespace.yaml` | ctds-monitoring 命名空间 |
| `prometheus-config.yaml` | 抓取配置（Pod 注解自动发现）+ 告警规则两条（服务下线 / 5xx 错误率） |
| `prometheus-deployment.yaml` | Prometheus Deployment（v3.13.3，数据保留 24h）+ ClusterIP Service（9090） |
| `alertmanager-config.yaml` | 路由配置（默认路由 v1，分组/静默可用） |
| `alertmanager-deployment.yaml` | Alertmanager Deployment（v0.34.0）+ ClusterIP Service（9093） |
| `grafana-config.yaml` | 数据源/看板 provisioning + 《C-TDS 平台基础看板》JSON（四区块） |
| `grafana-deployment.yaml` | Grafana Deployment（13.2.1，匿名访客可看）+ ClusterIP Service（3000） |

## 接入新服务（业务可读三步）

1. 服务 pom 引入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（版本走 Boot BOM）；
2. application.yml 加 `management.endpoints.web.exposure.include: health,prometheus`；
3. K8s 部署模板 Pod 加三条注解：`prometheus.io/scrape: "true"`、`prometheus.io/port: <服务端口>`、`prometheus.io/path: "/actuator/prometheus"`——Prometheus 侧零改动，自动开始抓取。

边界：开发/测试基线（数据存 Pod 本地盘、单副本、告警不对外发送），生产化扩展点见 ADR-014 §4。
