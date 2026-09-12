# C-TDS Kubernetes 编排模板（WBS 2.5.1，规范载体 ADR-013）

本目录是"机房摆放图纸"：声明两个容器化模块在 Kubernetes 上怎么跑（副本数、探针、资源基线、服务发现）。**模板本身不含镜像名**——镜像字段是占位符 `IMAGE_PLACEHOLDER`，由 WBS 2.5.2 一键部署工具链按 ADR-012 命名规范（`scripts/pipeline/image-tag.ps1` 输出）注入后执行 `kubectl apply`。

## 模板清单

| 文件 | 内容 |
| --- | --- |
| `backend-deployment.yaml` | example-service（Java 后端）Deployment：1 副本、tcpSocket 8080 探针、资源基线 256Mi/250m ~ 1Gi/1000m |
| `backend-service.yaml` | 后端 ClusterIP Service（8080） |
| `frontend-deployment.yaml` | 前端（nginx 静态）Deployment：1 副本、tcpSocket 80 探针、资源基线 64Mi/50m ~ 256Mi/500m |
| `frontend-service.yaml` | 前端 ClusterIP Service（80） |
| `kustomization.yaml` | Kustomize 资源清单：`kubectl kustomize .` 离线结构校验；2.5.2 注入镜像后可 `kubectl apply -k .` |

## 使用前提与边界（业务可读）

- **镜像须先存在**：后端箱子需先 `mvn -B -ntp package -DskipTests` 再 `docker build`（见 `services/example-service/Dockerfile` 头注）；前端箱子需先 `npm run build` 再 `docker build`。产物不在位时 docker build 会明确报错。
- **占位符未注入不可直接 apply**：`IMAGE_PLACEHOLDER` 不是合法镜像名，K8s 会拒绝调度——这是防呆边界（hifi B-7），注入机制属 2.5.2。
- **探针用 tcpSocket 而非 HTTP**：后端业务端点有鉴权（未认证 401），HTTP 探针会假红；tcpSocket 只验证"端口在监听"，够用且无假信号。
- **中间件不在模板内**：MySQL/Redis 等编排属后续任务（2.5.2+）；后端默认 profile 无库自足启动（WBS 2.4.10 B3 决策），模板按此口径。
- 模板已通过 kubectl 客户端侧离线校验（本任务实测留痕）；集群真实部署演练属 2.5.2"开发/测试环境演练通过"口径。
