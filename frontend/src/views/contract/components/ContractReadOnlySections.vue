<template>
  <!-- DB-44：合约只读五分区单点（详情 / 治理两视角共用）。
       组件纯展示——只经 props 驱动、分页经 emit 回传，不发起任何请求。 -->
  <!-- A 总览：两视角字段集 / 列数 / 状态形态有意不同（hifi §2.3，非脏拷贝） -->
  <el-descriptions v-if="isDetail" title="合约总览" :column="3" border>
    <el-descriptions-item label="合约编号">{{ detail.contractNo }}</el-descriptions-item>
    <el-descriptions-item label="状态">
      <el-tag :type="CONTRACT_STATUS_TAG_TYPES[detail.status] ?? 'info'">
        {{ CONTRACT_STATUS_LABELS[detail.status] ?? detail.status }}
      </el-tag>
    </el-descriptions-item>
    <el-descriptions-item label="当前条款版本">V{{ detail.currentClauseVersion }}</el-descriptions-item>
    <el-descriptions-item label="产品名称">{{ detail.productName }}</el-descriptions-item>
    <el-descriptions-item label="定价快照">{{ detail.pricingModel }} / {{ detail.priceAmount ?? '—' }}</el-descriptions-item>
    <el-descriptions-item label="模板引用">{{ detail.templateNo }}（版本快照 V{{ detail.templateVersionNo }}）</el-descriptions-item>
    <el-descriptions-item :label="MY_ROLE_LABELS.PROVIDER">{{ detail.providerSubjectNo }}</el-descriptions-item>
    <el-descriptions-item :label="MY_ROLE_LABELS.REQUESTER">{{ detail.requesterSubjectNo }}</el-descriptions-item>
    <el-descriptions-item label="内容哈希">
      <el-tooltip :content="CONTENT_HASH_HINT" placement="top">
        <span class="hash-cell">{{ detail.contentHash }}</span>
      </el-tooltip>
    </el-descriptions-item>
    <el-descriptions-item label="生效时间">{{ detail.effectiveAt ?? '—' }}</el-descriptions-item>
    <el-descriptions-item label="完结时间">{{ detail.endedAt ?? '—' }}</el-descriptions-item>
    <el-descriptions-item label="创建 / 更新">{{ detail.createdAt }} / {{ detail.updatedAt }}</el-descriptions-item>
  </el-descriptions>
  <el-descriptions v-else title="合约总览" :column="2" border size="small">
    <el-descriptions-item label="合约编号">{{ detail.contractNo }}</el-descriptions-item>
    <el-descriptions-item label="状态">
      {{ CONTRACT_STATUS_LABELS[detail.status] ?? detail.status }}
    </el-descriptions-item>
    <el-descriptions-item label="产品名称">{{ detail.productName }}</el-descriptions-item>
    <el-descriptions-item label="定价快照">
      {{ detail.pricingModel }} / {{ detail.priceAmount ?? '—' }}
    </el-descriptions-item>
    <el-descriptions-item :label="MY_ROLE_LABELS.PROVIDER">{{ detail.providerSubjectNo }}</el-descriptions-item>
    <el-descriptions-item :label="MY_ROLE_LABELS.REQUESTER">{{ detail.requesterSubjectNo }}</el-descriptions-item>
    <el-descriptions-item label="模板引用">
      {{ detail.templateNo }}（V{{ detail.templateVersionNo }}）
    </el-descriptions-item>
    <el-descriptions-item label="当前版本">V{{ detail.currentClauseVersion }}</el-descriptions-item>
    <el-descriptions-item label="内容哈希">
      <el-tooltip :content="CONTENT_HASH_HINT" placement="top">
        <span class="hash-cell">{{ detail.contentHash }}</span>
      </el-tooltip>
    </el-descriptions-item>
    <el-descriptions-item label="生效 / 完结">
      {{ detail.effectiveAt ?? '—' }} / {{ detail.endedAt ?? '—' }}
    </el-descriptions-item>
  </el-descriptions>

  <!-- 终止提示：详情视角在总览之后；治理视角由治理页置于抽屉顶部（有意差异，hifi §2.3） -->
  <el-alert
    v-if="isDetail && detail.terminationType"
    type="error"
    :closable="false"
    :title="`${TERMINATION_TYPE_LABELS[detail.terminationType] ?? detail.terminationType} — 终止理由：${detail.terminationReason ?? '—'}（操作者：${detail.terminatedBy ?? '—'}）`"
  />

  <!-- 视图专有区块插槽：置于总览与条款全文之间（详情视角置入"协商动作条"，保持既有区块顺序零变更；
       治理视角不传——hifi §2.2 实施澄清 E2） -->
  <slot name="before-clause" />

  <!-- B 当前条款全文 + F 策略视图（容器形态与字号两视角有意不同） -->
  <component :is="shell" :shadow="isDetail ? 'never' : undefined">
    <template v-if="isDetail" #header>当前条款全文（V{{ detail.currentClauseVersion }}）</template>
    <h4 v-if="!isDetail">当前条款全文</h4>
    <el-descriptions :column="1" border :size="isDetail ? undefined : 'small'">
      <el-descriptions-item v-for="(value, key) in detail.clauseValues.slots" :key="key" :label="slotName(String(key))">
        {{ value }}
      </el-descriptions-item>
    </el-descriptions>
    <h4>使用控制策略（五要素全文）</h4>
    <el-descriptions :column="2" border :size="isDetail ? undefined : 'small'">
      <el-descriptions-item :label="POLICY_ELEMENT_LABELS.quota">{{ strategyLine('quota') }}</el-descriptions-item>
      <el-descriptions-item :label="POLICY_ELEMENT_LABELS.term">{{ strategyLine('term') }}</el-descriptions-item>
      <el-descriptions-item :label="POLICY_ELEMENT_LABELS.purpose">{{ strategyLine('purpose') }}</el-descriptions-item>
      <el-descriptions-item :label="POLICY_ELEMENT_LABELS.territory">{{ strategyLine('territory') }}</el-descriptions-item>
      <el-descriptions-item :label="POLICY_ELEMENT_LABELS.noRedistribution">
        {{ detail.clauseValues.strategy.noRedistribution?.enabled ? '是' : '未启用' }}
      </el-descriptions-item>
      <el-descriptions-item label="无使用限制声明">
        {{ detail.clauseValues.strategy.noRestrictionDeclared ? '是（显式声明）' : '未声明' }}
      </el-descriptions-item>
    </el-descriptions>
  </component>

  <!-- C 条款版本链（R8）：逐版 + 可展开条款全文（键名直显，沿 hifi E2） -->
  <component :is="shell" :shadow="isDetail ? 'never' : undefined">
    <template v-if="isDetail" #header>条款版本链</template>
    <h4 v-if="!isDetail">条款版本链</h4>
    <el-table :data="clauseVersions" :size="isDetail ? undefined : 'small'" :empty-text="'（仅初始版本）'">
      <el-table-column type="expand">
        <template #default="scope">
          <div class="expand-slots">
            <div v-for="(value, key) in scope.row.clauseValues.slots" :key="key" class="expand-slot-line">
              {{ key }}：{{ value }}
            </div>
            <span v-if="!Object.keys(scope.row.clauseValues.slots ?? {}).length">—</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="版本号" width="80">
        <template #default="scope">V{{ scope.row.versionNo }}</template>
      </el-table-column>
      <el-table-column prop="proposedBy" label="提案方" width="170">
        <template #default="scope">{{ scope.row.proposedBy ?? '发起' }}</template>
      </el-table-column>
      <el-table-column prop="proposedAt" label="提案时间" width="180">
        <template #default="scope">{{ scope.row.proposedAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="双方确认时间" min-width="240">
        <template #default="scope">
          {{ MY_ROLE_LABELS.PROVIDER }}：{{ scope.row.confirmedProviderAt ?? '—' }} / {{ MY_ROLE_LABELS.REQUESTER }}：{{ scope.row.confirmedRequesterAt ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column label="变更明细" min-width="200">
        <template #default="scope">
          <div v-for="change in scope.row.changes ?? []" :key="change.slot">
            {{ change.slot }}：{{ change.from ?? '—' }} → {{ change.to ?? '—' }}
          </div>
          <span v-if="!scope.row.changes?.length">—</span>
        </template>
      </el-table-column>
    </el-table>
  </component>

  <!-- 视图专有区块插槽（详情视角置入"签署与存证 / 策略与使用"，保持既有页面区块顺序零变更；
       治理视角不传插槽——hifi §2.2 实施澄清 E1） -->
  <slot />

  <!-- H 合约留痕区（R13；分页经 emit 回传父视图，无乐观更新） -->
  <component :is="shell" :shadow="isDetail ? 'never' : undefined">
    <template v-if="isDetail" #header>合约留痕</template>
    <h4 v-if="!isDetail">合约留痕</h4>
    <el-table v-loading="logsLoading" :data="actionLogs" size="small" :empty-text="'暂无记录'">
      <el-table-column label="动作" width="120">
        <template #default="scope">{{ ACTION_LOG_ACTION_LABELS[scope.row.action] ?? scope.row.action }}</template>
      </el-table-column>
      <el-table-column label="版本号" width="80">
        <template #default="scope">{{ scope.row.versionNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="actorSubjectNo" label="操作者主体编号" min-width="170" />
      <el-table-column prop="createdAt" label="时间" width="180" />
      <el-table-column label="从何值 → 到何值" min-width="180">
        <template #default="scope">{{ scope.row.fromValue ?? '—' }} → {{ scope.row.toValue ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="拒绝码" width="90">
        <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
      </el-table-column>
    </el-table>
    <el-pagination
      :current-page="logsPage"
      layout="prev, pager, next"
      :total="logsTotal"
      :page-size="logsPageSize"
      @current-change="onLogsPageChange"
    />
  </component>
</template>

<script setup lang="ts">
/**
 * 合约只读五分区共享组件（DB-44 Q1-A；`docs/designs/DB-44-hifi.md` §2）：
 * 承载合约详情页与治理直查抽屉共用的只读分区——总览 / 当前条款全文 / 策略五要素 /
 * 条款版本链 / 合约留痕；`slotName()` 与 `strategyLine()` 为**全仓唯一副本**。
 * 两视角差异经 `variant` 显式表达（总览字段集、容器形态、字号），有意差异见 hifi §2.3；
 * 组件纯展示：不发请求、不做错误处理（读面错误由各视图统一出口处理）。
 */
import { computed } from 'vue'
import { ElCard } from 'element-plus'
import type { ClauseFramework, ClauseVersionItem, ContractActionLogItem, ContractDetail } from '../../../api/contract'
import {
  ACTION_LOG_ACTION_LABELS,
  CONTRACT_STATUS_LABELS,
  CONTRACT_STATUS_TAG_TYPES,
  CONTENT_HASH_HINT,
  MY_ROLE_LABELS,
  POLICY_ELEMENT_LABELS,
  TERMINATION_TYPE_LABELS,
} from '../../../constants/contract'

const props = withDefaults(defineProps<{
  /** 视角：`detail` = 合约详情页；`governance` = 治理直查抽屉。 */
  variant: 'detail' | 'governance'
  /** 已解包的合约详情（治理侧传 `governanceDetail.detail`）。 */
  detail: ContractDetail
  /** 条款版本链（R8 出参原样；缺省 = 空表）。 */
  clauseVersions?: ClauseVersionItem[]
  /** 模板条款框架（R5）；null 或缺省 → 槽位标签回退键名。 */
  framework?: ClauseFramework | null
  /** 留痕行（R13；缺省 = 空表）。 */
  actionLogs?: ContractActionLogItem[]
  logsLoading: boolean
  logsTotal: number
  logsPage: number
  logsPageSize: number
}>(), {
  clauseVersions: () => [],
  framework: null,
  actionLogs: () => [],
})

const emit = defineEmits<{
  (e: 'logs-page-change', page: number): void
}>()

const isDetail = computed(() => props.variant === 'detail')

/** 分区外壳：详情视角 = 卡片（`el-card`），治理视角 = 平铺容器（`div` + `h4` 小标题）。 */
const shell = computed(() => (isDetail.value ? ElCard : 'div'))

/**
 * 槽位显示名：框架命中 → "名称（键）"；未命中或框架缺失 → 键名
 * （与 WBS-3.4.7 hifi §6.4-B "键 → 名称映射优先经模板详情框架；键缺失时显示键名"同口径）。
 */
function slotName(key: string): string {
  const slot = props.framework?.slots.find((item) => item.key === key)
  return slot ? `${slot.name}（${key}）` : key
}

/** 策略要素行：未启用 → "未启用"；quota → "次数上限 {n|—}"；term → "起 ~ 止"；其余 → text | "已启用"。 */
function strategyLine(element: 'quota' | 'term' | 'purpose' | 'territory'): string {
  const el = props.detail.clauseValues.strategy[element]
  if (!el?.enabled) {
    return '未启用'
  }
  if (element === 'quota') {
    return `${POLICY_ELEMENT_LABELS.quota} ${el.maxCount ?? '—'}`
  }
  if (element === 'term') {
    return `${el.startDate ?? '—'} ~ ${el.endDate ?? '—'}`
  }
  return el.text ?? '已启用'
}

/** 留痕翻页：回传目标页号，由父视图更新页号并重新拉取（无乐观更新）。 */
function onLogsPageChange(page: number): void {
  emit('logs-page-change', page)
}
</script>

<style scoped>
.hash-cell {
  cursor: help;
}

.expand-slot-line {
  padding: 2px 0;
  font-size: 13px;
}
</style>
