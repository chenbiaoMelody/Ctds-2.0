<script setup lang="ts">
/**
 * WBS-3.1.5 申请人侧 · 认证与档案（3.1.2/3.1.3/3.1.4 契约界面化，字段口径不变）：
 * 企业流程 = 上传执照 → OCR 回填核对确认 → 法人核验；政务流程 = 提交政务 CA 证书（换证重提）。
 * 状态徽标 + 驳回理由可见（行为 4 验收-2）；已入驻后展示 DID 衔接提示（归 C-1.2）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  confirmLicense,
  fetchProfile,
  fetchSubjectDetail,
  submitGovCertificate,
  uploadLicense,
  verifyLegalPerson,
  type CertificationProfile,
  type SubjectDetailView,
} from '../../api/subject'
import { STATUS_LABELS, STATUS_TYPES, subjectTypeLabel } from '../../constants/subject'

const route = useRoute()
const subjectNo = computed(() => String(route.params.subjectNo))

const loading = ref(false)
const submitting = ref(false)
const profile = ref<CertificationProfile | null>(null)
const detail = ref<SubjectDetailView | null>(null)

const subjectType = computed(() => detail.value?.subjectType ?? 'ENTERPRISE')
const isGov = computed(() => subjectType.value === 'GOV')

const statusLabel = computed(() =>
  profile.value ? (STATUS_LABELS[profile.value.status] ?? profile.value.status) : '',
)
const statusType = computed(() =>
  profile.value ? (STATUS_TYPES[profile.value.status] ?? 'info') : 'info',
)

/** 驳回理由 = 最近一条"转已驳回"流转的备注（行为 4：申请人可见并可修改后重新申请）。 */
const rejectReason = computed(() => {
  const toRejected = detail.value?.statusLogs.filter((t) => t.toStatus === 'REJECTED') ?? []
  const last = toRejected[toRejected.length - 1]
  return last?.remark ?? ''
})

const confirmForm = reactive({
  subjectName: '',
  uscc: '',
  legalPerson: '',
  regAddress: '',
})
const verifyForm = reactive({ legalPersonName: '', legalPersonIdNo: '' })

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

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function doUploadLicense(options: UploadRequestOptions): Promise<unknown> {
  submitting.value = true
  try {
    const result = await uploadLicense(subjectNo.value, options.file)
    if (result.ocrResult) {
      confirmForm.subjectName = result.ocrResult.subjectName
      confirmForm.uscc = result.ocrResult.uscc
      confirmForm.legalPerson = result.ocrResult.legalPerson
      confirmForm.regAddress = result.ocrResult.regAddress
    }
    ElMessage.success('上传成功，OCR 识别要素已回填')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '上传失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
  return true
}

async function doConfirm(): Promise<void> {
  submitting.value = true
  try {
    await confirmLicense(subjectNo.value, { ...confirmForm })
    ElMessage.success('核对确认完成')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '确认失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

async function doVerify(): Promise<void> {
  submitting.value = true
  try {
    const result = await verifyLegalPerson(subjectNo.value, { ...verifyForm })
    ElMessage.success(result.conclusion === 'PASS' ? '法人核验通过' : '法人核验未通过')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '核验失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

async function doSubmitGovCert(options: UploadRequestOptions): Promise<unknown> {
  submitting.value = true
  try {
    const result = await submitGovCertificate(subjectNo.value, options.file)
    if (result.conclusion === 'PASS') {
      ElMessage.success('证书验证通过，已进入人工审核')
    } else {
      ElMessage.warning(`证书验证未通过：${result.failReason ?? '原因未明'}，可重新提交换证`)
    }
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '提交失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
  return true
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <h2 class="page-title">认证与档案</h2>
    <el-card shadow="never" class="block">
      <p>
        <span class="label">申请编号</span>{{ subjectNo }}
        <el-tag :type="statusType" class="tag">{{ statusLabel }}</el-tag>
      </p>
      <p v-if="detail"><span class="label">主体名称</span>{{ detail.subjectName }}（{{ subjectTypeLabel(detail.subjectType) }}）</p>
      <el-alert
        v-if="rejectReason"
        class="reject-tip"
        type="error"
        :closable="false"
        :title="`驳回理由：${rejectReason}`"
        description="请修改后重新提交注册申请"
      />
    </el-card>

    <!-- 政务主体：政务 CA 证书提交（全程不出现执照 OCR 与法人核验，规格行为 6） -->
    <el-card v-if="isGov" shadow="never" class="block">
      <template #header>政务 CA 数字证书</template>
      <p v-if="profile?.govCa?.uploaded" class="hint">
        最近提交：{{ profile.govCa.fileName }}（结论：{{ profile.govCa.lastConclusion }}<template v-if="profile.govCa.lastFailReason">，{{ profile.govCa.lastFailReason }}</template>）
      </p>
      <el-upload
        drag
        action="#"
        :auto-upload="true"
        :http-request="doSubmitGovCert"
        :show-file-list="false"
        accept=".cer,.crt,.pem"
      >
        <p>点击或拖拽提交政务 CA 证书文件（cer/crt/pem，≤2MB；验证失败可重新提交换证）</p>
      </el-upload>
    </el-card>

    <!-- 企业/机构主体：上传执照 → 核对确认 → 法人核验 -->
    <template v-else>
      <el-card shadow="never" class="block">
        <template #header>营业执照上传与 OCR 识别</template>
        <p v-if="profile?.license?.uploaded" class="hint">
          已上传{{ profile.license.recognizable ? '，OCR 识别成功' : '，OCR 未识别请重传' }}
        </p>
        <el-upload
          drag
          action="#"
          :auto-upload="true"
          :http-request="doUploadLicense"
          :show-file-list="false"
          accept=".jpg,.jpeg,.png"
        >
          <p>点击或拖拽上传营业执照影像（jpg/jpeg/png，≤5MB）</p>
        </el-upload>
      </el-card>

      <el-card v-if="profile?.license?.uploaded && profile?.license?.recognizable" shadow="never" class="block">
        <template #header>核对确认（与识别结果一致才生效）</template>
        <el-form label-width="150px" class="form">
          <el-form-item label="主体名称"><el-input v-model="confirmForm.subjectName" /></el-form-item>
          <el-form-item label="统一社会信用代码"><el-input v-model="confirmForm.uscc" /></el-form-item>
          <el-form-item label="法定代表人"><el-input v-model="confirmForm.legalPerson" /></el-form-item>
          <el-form-item label="注册地址"><el-input v-model="confirmForm.regAddress" /></el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="doConfirm">确认提交</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="profile?.license?.confirmed" shadow="never" class="block">
        <template #header>法人实人核验</template>
        <p class="hint">身份证号尾号为 8 的演示号码将触发核验不通过（模拟渠道预置规则）</p>
        <el-form label-width="150px" class="form">
          <el-form-item label="法定代表人姓名"><el-input v-model="verifyForm.legalPersonName" /></el-form-item>
          <el-form-item label="法定代表人身份证号"><el-input v-model="verifyForm.legalPersonIdNo" /></el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="doVerify">发起核验</el-button>
            <span v-if="profile.remainingAttemptsToday !== null" class="hint attempts">
              当日剩余核验次数：{{ profile.remainingAttemptsToday }}
            </span>
          </el-form-item>
        </el-form>
      </el-card>
    </template>

    <el-card v-if="profile?.status === 'ADMITTED'" shadow="never" class="block">
      <el-result icon="success" title="已入驻" sub-title="入驻完成，DID 签发与身份互认将作为下一环节提供" />
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.block {
  margin-bottom: 12px;
}

.label {
  display: inline-block;
  width: 110px;
  color: #6b7280;
}

.tag {
  margin-left: 12px;
}

.hint {
  color: #6b7280;
  font-size: 13px;
}

.attempts {
  margin-left: 12px;
}

.reject-tip {
  margin-top: 8px;
}

.form {
  max-width: 560px;
}
</style>
