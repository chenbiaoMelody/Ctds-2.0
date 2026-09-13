<script setup lang="ts">
/**
 * WBS-3.1.5 审核工作台 · 认证档案与审核操作（规格 C-1.1 行为 5 第 1~2 条）：
 * 档案分区（注册信息/证照影像放大/OCR 结果/核验记录；政务主体显示证书验证段、不显示法人核验段）+
 * 通过（二次确认）/ 驳回（理由必填）。不提供政务证书原件下载（lofi 问题 2 采 A）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  approveSubject,
  fetchLicenseImage,
  fetchProfile,
  fetchSubjectDetail,
  rejectSubject,
  type CertificationProfile,
  type SubjectDetail,
} from '../../api/subject'
import { STATUS_LABELS, STATUS_TYPES, subjectTypeLabel } from '../../constants/subject'

const route = useRoute()
const router = useRouter()
const subjectNo = computed(() => String(route.params.subjectNo))

const loading = ref(false)
const profile = ref<CertificationProfile | null>(null)
const detail = ref<SubjectDetail | null>(null)
const imageVisible = ref(false)
const imageDataUrl = ref('')
const rejectVisible = ref(false)
const rejectReason = ref('')
const submitting = ref(false)

const statusLabel = computed(() =>
  profile.value ? (STATUS_LABELS[profile.value.status] ?? profile.value.status) : '',
)
const statusType = computed(() =>
  profile.value ? (STATUS_TYPES[profile.value.status] ?? 'info') : 'info',
)

async function load(): Promise<void> {
  loading.value = true
  try {
    profile.value = await fetchProfile(subjectNo.value)
    detail.value = await fetchSubjectDetail(subjectNo.value)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '档案加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function zoomImage(): Promise<void> {
  try {
    const image = await fetchLicenseImage(subjectNo.value)
    imageDataUrl.value = image.dataUrl
    imageVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '影像加载失败')
  }
}

async function approve(): Promise<void> {
  try {
    await ElMessageBox.confirm('通过后主体状态流转为"已入驻"，确认执行审核通过？', '审核确认', {
      confirmButtonText: '确认通过',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  submitting.value = true
  try {
    await approveSubject(subjectNo.value)
    ElMessage.success('审核通过，主体已入驻')
    backToQueue()
  } catch (error) {
    handleReviewError(error)
  } finally {
    submitting.value = false
  }
}

function openRejectDialog(): void {
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject(): Promise<void> {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('驳回理由必填')
    return
  }
  submitting.value = true
  try {
    await rejectSubject(subjectNo.value, rejectReason.value.trim())
    rejectVisible.value = false
    ElMessage.success('已驳回，申请人可修改后重新申请')
    await load()
  } catch (error) {
    handleReviewError(error)
  } finally {
    submitting.value = false
  }
}

function handleReviewError(error: unknown): void {
  if (error instanceof ApiError) {
    ElMessage.error(error.message)
    if (error.code === '1004C0002') {
      void load()
    }
    return
  }
  ElMessage.error('操作失败，请稍后重试')
}

function backToQueue(): void {
  void router.push('/review')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">审核详情</h2>
    <el-page-header class="back" content="认证档案" @back="backToQueue" />

    <template v-if="profile">
      <el-card shadow="never" class="block">
        <template #header>基本信息</template>
        <p><span class="label">申请编号</span>{{ profile.subjectNo }}</p>
        <p><span class="label">当前状态</span><el-tag :type="statusType">{{ statusLabel }}</el-tag></p>
      </el-card>

      <!-- 注册信息（规格行为 5 第 1 条：审核员可见注册信息的完整档案） -->
      <el-card v-if="detail" shadow="never" class="block">
        <template #header>注册信息</template>
        <p><span class="label">主体名称</span>{{ detail.subject.subjectName }}</p>
        <p><span class="label">主体类型</span>{{ subjectTypeLabel(detail.subject.subjectType) }}</p>
        <p><span class="label">统一社会信用代码</span>{{ detail.subject.uscc }}</p>
        <p><span class="label">注册地址</span>{{ detail.subject.regAddress }}</p>
        <p><span class="label">联系人</span>{{ detail.subject.contactName }}（{{ detail.subject.contactPhone }}）</p>
      </el-card>

      <el-card v-if="profile.license && profile.license.uploaded" shadow="never" class="block">
        <template #header>证照影像（点击放大）</template>
        <el-button type="primary" plain @click="zoomImage">放大查看原始影像</el-button>
        <p v-if="profile.license.confirmed" class="hint">
          核对确认于 {{ profile.license.confirmedAt }}
        </p>
      </el-card>

      <el-card v-if="profile.license && profile.license.confirmed" shadow="never" class="block">
        <template #header>OCR 识别与确认结果</template>
        <p><span class="label">主体名称</span>{{ profile.license.confirmedResult?.subjectName }}</p>
        <p><span class="label">统一社会信用代码</span>{{ profile.license.confirmedResult?.uscc }}</p>
        <p><span class="label">法定代表人</span>{{ profile.license.confirmedResult?.legalPerson }}</p>
        <p><span class="label">注册地址</span>{{ profile.license.confirmedResult?.regAddress }}</p>
      </el-card>

      <!-- 政务主体段（WBS-3.1.4 档案口径）：仅政务主体显示，不提供原件下载 -->
      <el-card v-if="profile.govCa" shadow="never" class="block">
        <template #header>政务 CA 证书验证</template>
        <p><span class="label">证书文件名</span>{{ profile.govCa.fileName }}</p>
        <p>
          <span class="label">最近结论</span>
          <el-tag :type="profile.govCa.lastConclusion === 'PASS' ? 'success' : 'danger'">
            {{ profile.govCa.lastConclusion }}
          </el-tag>
        </p>
        <p v-if="profile.govCa.lastFailReason"><span class="label">失败原因</span>{{ profile.govCa.lastFailReason }}</p>
        <p v-if="profile.govCa.lastSubmittedAt"><span class="label">最近提交时间</span>{{ profile.govCa.lastSubmittedAt }}</p>
      </el-card>

      <!-- 法人核验段仅企业/机构主体显示（政务主体全程不出现，规格行为 6） -->
      <el-card v-if="!profile.govCa" shadow="never" class="block">
        <template #header>核验记录</template>
        <p v-if="profile.remainingAttemptsToday !== null" class="hint">
          当日剩余核验次数：{{ profile.remainingAttemptsToday }}
        </p>
        <el-table :data="profile.verifications" empty-text="暂无核验记录">
          <el-table-column prop="conclusion" label="结论" width="120" />
          <el-table-column prop="failReason" label="原因" min-width="200" />
          <el-table-column prop="createdAt" label="时间" width="180" />
        </el-table>
      </el-card>

      <el-card v-if="profile.status === 'PENDING_REVIEW'" shadow="never" class="block">
        <template #header>审核操作（二选一）</template>
        <el-button type="success" :loading="submitting" @click="approve">通过（转已入驻）</el-button>
        <el-button type="danger" plain :loading="submitting" @click="openRejectDialog">驳回（转已驳回）</el-button>
      </el-card>

      <!-- 流转留痕（行为 4 第 2 条四要素；审核结论与操作者、时间闭环——剧本 S1 步骤 9 可见性） -->
      <el-card v-if="detail" shadow="never" class="block">
        <template #header>流转留痕</template>
        <el-table :data="detail.transitions" empty-text="暂无流转记录">
          <el-table-column label="前状态" width="120">
            <template #default="scope">{{ scope.row.fromStatus ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="toStatus" label="后状态" width="150" />
          <el-table-column prop="triggerRole" label="触发方" width="110" />
          <el-table-column prop="operator" label="操作人" width="150" />
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column prop="remark" label="备注" min-width="200" />
        </el-table>
      </el-card>
    </template>

    <el-dialog v-model="imageVisible" title="证照原始影像" width="720">
      <img :src="imageDataUrl" alt="证照原始影像" class="zoom-image" />
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="驳回（理由必填）" width="480">
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        maxlength="200"
        show-word-limit
        placeholder="请填写驳回理由（申请人可见，修改后可重新申请）"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.back {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}

.label {
  display: inline-block;
  width: 140px;
  color: #6b7280;
}

.hint {
  color: #6b7280;
  font-size: 13px;
}

.zoom-image {
  width: 100%;
}
</style>
