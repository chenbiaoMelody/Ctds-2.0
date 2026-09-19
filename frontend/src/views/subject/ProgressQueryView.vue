<script setup lang="ts">
/**
 * CHG-C-1.1-V1.2 入驻进度自助查询（规格行为 8，hifi §1.2 定稿界面）：
 * 申请编号 + 统一社会信用代码双凭证查询，仅返回最小必要信息；
 * 查询失败（编号不存在或凭证不符）页面顶部统一文案"未查询到匹配的申请"防枚举；
 * 输入为空前端必填红字拦截、不发请求。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import { fetchRegistrationProgress, type RegistrationProgressView } from '../../api/subject'
import { STATUS_LABELS, STATUS_TYPES, subjectTypeLabel } from '../../constants/subject'

const router = useRouter()
const formRef = ref()
const querying = ref(false)
const form = reactive({ subjectNo: '', uscc: '' })
const result = ref<RegistrationProgressView | null>(null)
const notFound = ref(false)

const rules = {
  subjectNo: [{ required: true, message: '申请编号不能为空', trigger: 'blur' }],
  uscc: [
    { required: true, message: '统一社会信用代码不能为空', trigger: 'blur' },
    {
      // 18 位字符集两侧空白容忍 + 大小写不敏感，与后端 trim+归一口径一致（hifi §2 查询凭证比对）
      pattern: /^\s*[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}\s*$/i,
      message: '统一社会信用代码格式不正确',
      trigger: 'blur',
    },
  ],
}

async function query(): Promise<void> {
  try {
    await formRef.value.validate()
  } catch {
    // 校验失败：el-form 已逐项显示原因，此处仅阻断查询（不产生未处理的 Promise 拒绝）
    return
  }
  querying.value = true
  result.value = null
  notFound.value = false
  try {
    result.value = await fetchRegistrationProgress(form.subjectNo.trim(), form.uscc.trim().toUpperCase())
  } catch (error) {
    if (error instanceof ApiError && error.code === '1000C0003') {
      notFound.value = true
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '查询失败，请稍后重试')
    }
  } finally {
    querying.value = false
  }
}

function goCertification(): void {
  if (result.value) {
    void router.push(`/subject/certification/${result.value.subjectNo}`)
  }
}
</script>

<template>
  <div class="page">
    <h2 class="page-title">入驻进度查询</h2>
    <p class="page-desc">凭注册时返回的申请编号与本主体统一社会信用代码查询入驻进度</p>
    <el-alert
      v-if="notFound"
      class="not-found-tip"
      type="error"
      :closable="false"
      show-icon
      title="未查询到匹配的申请"
    />
    <el-card shadow="never" class="query-card">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="150px" class="query-form">
        <el-form-item label="申请编号" prop="subjectNo">
          <el-input v-model="form.subjectNo" placeholder="注册成功时返回，如 S20260919000001" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="uscc">
          <el-input v-model="form.uscc" placeholder="注册时填写的 18 位代码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="querying" @click="query">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <span>查询结果</span>
      </template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="主体名称">{{ result.subjectName }}</el-descriptions-item>
        <el-descriptions-item label="主体类型">{{ subjectTypeLabel(result.subjectType) }}</el-descriptions-item>
        <el-descriptions-item label="当前状态">
          <el-tag :type="STATUS_TYPES[result.status] ?? 'info'">
            {{ STATUS_LABELS[result.status] ?? result.status }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="result.status === 'REJECTED' && result.rejectReason"
        class="reject-tip"
        type="error"
        :closable="false"
        show-icon
        :title="`驳回理由：${result.rejectReason}`"
      />
      <div class="result-actions">
        <el-button type="primary" @click="goCertification">前往认证页</el-button>
      </div>
    </el-card>
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

.not-found-tip {
  margin-bottom: 16px;
}

.query-form {
  max-width: 560px;
}

.result-card {
  max-width: 640px;
}

.reject-tip {
  margin-top: 16px;
}

.result-actions {
  margin-top: 16px;
}
</style>
