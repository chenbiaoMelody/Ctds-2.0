<script setup lang="ts">
/**
 * WBS-3.2.6 页面 3：我的邀请与申请（hifi §6.4 / 测试锚点 T18）：
 * - 端点 14（跨空间个人面，分页）；「待确认」行动作为「接受 / 谢绝」（端点 11，本人身份）；
 * - 谢绝可填理由；成功后刷新列表并以响应为准，不做乐观更新。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import { confirmAdmission, listMyAdmissions, type AdmissionItem } from '../../api/space'
import {
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_TYPES,
  ADMISSION_TYPE_LABELS,
  MY_ADMISSIONS_EMPTY_TIP,
  labelOf,
} from '../../constants/space'
import { guideToLoginIfAuthFailed } from './authGuide'

const router = useRouter()
const loading = ref(false)
const items = ref<AdmissionItem[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const declineVisible = ref(false)
const declineTarget = ref<AdmissionItem | null>(null)
const declineReason = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listMyAdmissions(pageNum.value, pageSize.value)
    items.value = page.list
    total.value = page.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '邀请与申请加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 接受邀请（T18）：本人身份确认 → 成为成员。 */
async function accept(row: AdmissionItem): Promise<void> {
  try {
    await confirmAdmission(row.spaceId, row.id, { decision: 'CONFIRM' })
    ElMessage.success('已加入空间')
    await load()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '接受失败，请稍后重试')
  }
}

function openDecline(row: AdmissionItem): void {
  declineTarget.value = row
  declineReason.value = ''
  declineVisible.value = true
}

/** 谢绝邀请（T18）：理由可选。 */
async function submitDecline(): Promise<void> {
  const target = declineTarget.value
  if (!target) return
  const payload: { decision: 'DECLINE'; reason?: string } = { decision: 'DECLINE' }
  if (declineReason.value.trim()) payload.reason = declineReason.value.trim()
  try {
    await confirmAdmission(target.spaceId, target.id, payload)
    ElMessage.success('已谢绝邀请')
    declineVisible.value = false
    await load()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '谢绝失败，请稍后重试')
  }
}

function changePage(page: number): void {
  pageNum.value = page
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">我的邀请与申请</h2>
    <p class="page-desc">跨空间查看与你相关的准入单；「待确认」的邀请可在此接受或谢绝。</p>

    <el-card shadow="never">
      <el-table :data="items" v-loading="loading" class="my-admissions-table" :empty-text="MY_ADMISSIONS_EMPTY_TIP">
        <el-table-column prop="spaceId" label="空间 id" width="100" />
        <el-table-column label="形态" width="90">
          <template #default="scope">{{ labelOf(ADMISSION_TYPE_LABELS, scope.row.type) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="scope">
            <el-tag :type="ADMISSION_STATUS_TYPES[scope.row.status] ?? 'info'">
              {{ labelOf(ADMISSION_STATUS_LABELS, scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作者" min-width="150" />
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="理由" min-width="150">
          <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="scope">
            <template v-if="scope.row.status === 'PENDING_CONFIRMATION'">
              <el-button type="primary" link class="accept-btn" @click="accept(scope.row)">接受</el-button>
              <el-button type="danger" link class="decline-btn" @click="openDecline(scope.row)">谢绝</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="changePage"
      />
    </el-card>

    <el-dialog v-model="declineVisible" title="谢绝邀请" width="460px" class="decline-dialog">
      <el-form label-width="80px">
        <el-form-item label="理由">
          <el-input v-model="declineReason" class="decline-reason" maxlength="256" placeholder="理由（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="declineVisible = false">取消</el-button>
        <el-button type="danger" class="decline-submit" @click="submitDecline">确认谢绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.page-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #6b7280;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
