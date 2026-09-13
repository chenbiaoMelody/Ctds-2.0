<script setup lang="ts">
/**
 * WBS-3.1.5 申请人侧 · 主体注册（3.1.2 契约界面化，字段口径不变）：
 * 主体类型分支——企业/机构（营业执照 OCR 流程提示）与政府部门（政务 CA 证书流程提示，
 * 认证环节全程不出现执照上传与法人核验，规格行为 6）。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import { registerSubject } from '../../api/subject'

const router = useRouter()
const submitting = ref(false)
const formRef = ref()
const form = reactive({
  subjectType: 'ENTERPRISE',
  subjectName: '',
  uscc: '',
  regAddress: '',
  contactName: '',
  contactPhone: '',
  adminAccount: '',
})

const rules = {
  subjectName: [{ required: true, message: '主体名称不能为空', trigger: 'blur' }],
  uscc: [{ required: true, message: '统一社会信用代码不能为空', trigger: 'blur' }],
  regAddress: [{ required: true, message: '注册地址不能为空', trigger: 'blur' }],
  contactName: [{ required: true, message: '联系人姓名不能为空', trigger: 'blur' }],
  contactPhone: [{ required: true, message: '联系电话不能为空', trigger: 'blur' }],
  adminAccount: [{ required: true, message: '管理员账号不能为空', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  await formRef.value.validate()
  submitting.value = true
  try {
    const result = await registerSubject({ ...form })
    ElMessage.success(`注册成功，申请编号：${result.subjectNo}`)
    await router.push(`/subject/certification/${result.subjectNo}`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <h2 class="page-title">主体入驻</h2>
    <p class="page-desc">
      企业/机构主体：注册后上传营业执照完成 OCR 识别与法人核验；政府部门主体：注册后提交政务 CA
      数字证书（无需营业执照与法人核验）。
    </p>
    <el-card shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="150px" class="form">
        <el-form-item label="主体类型" prop="subjectType">
          <el-radio-group v-model="form.subjectType">
            <el-radio value="ENTERPRISE">企业</el-radio>
            <el-radio value="GOV">政府部门</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="form.subjectType === 'GOV'"
          class="branch-tip"
          type="info"
          :closable="false"
          title="政府部门通道：注册后在认证页提交政务 CA 数字证书，验证通过直接进入人工审核"
        />
        <el-form-item label="主体名称" prop="subjectName">
          <el-input v-model="form.subjectName" placeholder="与证照/单位信息一致" />
        </el-form-item>
        <el-form-item
          :label="form.subjectType === 'GOV' ? '机关统一社会信用代码' : '统一社会信用代码'"
          prop="uscc"
        >
          <el-input v-model="form.uscc" placeholder="18 位" />
        </el-form-item>
        <el-form-item label="注册地址" prop="regAddress">
          <el-input v-model="form.regAddress" />
        </el-form-item>
        <el-form-item label="联系人姓名" prop="contactName">
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" />
        </el-form-item>
        <el-form-item label="管理员账号" prop="adminAccount">
          <el-input v-model="form.adminAccount" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">提交注册</el-button>
        </el-form-item>
      </el-form>
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

.form {
  max-width: 560px;
}

.branch-tip {
  margin-bottom: 16px;
}
</style>
