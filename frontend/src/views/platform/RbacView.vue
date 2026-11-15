<template>
  <div class="platform-rbac-page">
    <div class="page-head">
      <h2 class="page-title">角色与权限</h2>
      <div class="page-desc">
        当前三员视角：{{ ADMIN_PERSONA_LABELS[persona] }}——各标签页按三员职责面呈现，越权调用如实展示后端拒绝结果。
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="权限清单" name="roles">
        <div class="tab-toolbar">
          <el-button @click="loadRoles">刷新</el-button>
        </div>
        <el-alert
          v-if="rolesError"
          class="tab-error"
          type="error"
          :title="rolesError.message"
          :closable="false"
          show-icon
        >
          <el-button link type="primary" @click="loadRoles">重试</el-button>
        </el-alert>
        <el-table
          v-else
          v-loading="rolesLoading"
          class="roles-table"
          size="small"
          :data="orderedRoles"
          :empty-text="ROLES_EMPTY_TEXT"
        >
          <el-table-column label="角色" width="200">
            <template #default="scope">
              <code class="mono">{{ scope.row.role }}</code>
            </template>
          </el-table-column>
          <el-table-column label="权限点" min-width="520">
            <template #default="scope">
              <el-tag
                v-for="point in scope.row.permissions"
                :key="point"
                class="perm-tag"
                size="small"
                type="info"
              >
                <code class="mono">{{ point }}</code>
              </el-tag>
            </template>
          </el-table-column>
          <template #empty>{{ ROLES_EMPTY_TEXT }}</template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="授予与收回" name="grant" lazy>
        <div class="grant-form">
          <div class="grant-field">
            <span class="grant-label">角色</span>
            <el-select
              v-model="grantForm.role"
              class="rbac-role-select"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入角色值"
            >
              <el-option v-for="opt in RBAC_ROLE_OPTIONS" :key="opt" :label="opt" :value="opt" />
            </el-select>
          </div>
          <div class="grant-field">
            <span class="grant-label">权限点</span>
            <el-select
              v-model="grantForm.permission"
              class="rbac-permission-select"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入权限点"
            >
              <el-option
                v-for="opt in RBAC_PERMISSION_OPTIONS"
                :key="opt"
                :label="opt"
                :value="opt"
              />
            </el-select>
          </div>
          <div class="grant-field">
            <span class="grant-label">动作</span>
            <el-radio-group v-model="grantForm.action">
              <el-radio value="grant">授予</el-radio>
              <el-radio value="revoke">收回</el-radio>
            </el-radio-group>
          </div>
          <div class="grant-field">
            <span class="grant-label">变更单号</span>
            <el-input
              v-model="grantForm.changeOrderNo"
              class="rbac-order-input"
              placeholder="如 CHG-2026-001（必填）"
            />
          </div>
          <div class="grant-actions">
            <el-button type="primary" :loading="grantLoading" @click="submitGrant">提交</el-button>
          </div>
          <div v-if="grantResult" class="grant-result">
            已提交，变更号 <code class="mono">{{ grantResult }}</code>——授权映射变更留痕可查
            （"变更留痕"页签按变更单号检索）。
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="变更留痕" name="changes" lazy>
        <div class="tab-toolbar">
          <el-button @click="loadChanges">刷新</el-button>
        </div>
        <el-alert
          v-if="changesError"
          class="tab-error"
          type="error"
          :title="changesError.message"
          :closable="false"
          show-icon
        >
          <el-button link type="primary" @click="loadChanges">重试</el-button>
        </el-alert>
        <template v-else>
          <el-table
            v-loading="changesLoading"
            class="changes-table"
            size="small"
            :data="changes?.list ?? []"
            :empty-text="CHANGES_EMPTY_TEXT"
          >
            <el-table-column label="变更号" width="190">
              <template #default="scope">
                <code class="mono">{{ scope.row.changeNo }}</code>
              </template>
            </el-table-column>
            <el-table-column label="变更单号" min-width="190">
              <template #default="scope">{{ scope.row.changeOrderNo }}</template>
            </el-table-column>
            <el-table-column prop="changedBy" label="变更人" width="150" />
            <el-table-column prop="changedAt" label="时点" width="185" />
            <el-table-column label="角色" width="170">
              <template #default="scope">
                <code class="mono">{{ scope.row.roleCode }}</code>
              </template>
            </el-table-column>
            <el-table-column label="权限点" min-width="180">
              <template #default="scope">
                <code class="mono">{{ scope.row.permissionPoint }}</code>
              </template>
            </el-table-column>
            <el-table-column label="变更前" width="90">
              <template #default="scope">{{ scope.row.beforeValue ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="变更后" width="90">
              <template #default="scope">{{ scope.row.afterValue ?? '—' }}</template>
            </el-table-column>
            <template #empty>{{ CHANGES_EMPTY_TEXT }}</template>
          </el-table>
          <el-pagination
            v-if="(changes?.total ?? 0) > pageSize"
            layout="total, prev, pager, next"
            :total="changes?.total ?? 0"
            :page-size="pageSize"
            :current-page="changesPageNum"
            @current-change="onChangesPageChange"
          />
        </template>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  grantPermission,
  listRbacChanges,
  listRbacRoles,
  revokePermission,
  type RbacChangeView,
  type RbacRoleView,
} from '../../api/platform'
import type { PageData } from '../../api/types'
import { PLATFORM_ADMIN_ROLES, RBAC_PERMISSION_OPTIONS, RBAC_ROLE_OPTIONS } from '../../constants/platform'
import { ADMIN_PERSONA_LABELS, getAdminPersona, type AdminPersona } from '../../stores/demoIdentity'
import { getDemoSubject } from '../../api/client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.9.5「角色与权限」页（hifi §4 / lofi 屏 C，menuOrder 30，connector.health.read）：
 * 三标签——清单（只读，B1-AC1，三员在前仅前端展示序）/ 授予与收回（表单三元组 + 变更单号
 * 必填，W1/W2；视角不符时表单仍渲染、提交后 403 原文如实呈现，前端不预判拦截以外加规则）/
 * 变更留痕（分页八列，B1-AC3）。表单候选 = 静态快照仅输入辅助（constants/platform.ts），
 * 真实值域封闭判定在后端（B1-R1/B1-R2）。
 */
const ROLES_EMPTY_TEXT = '暂无角色清单'
const CHANGES_EMPTY_TEXT = '暂无变更留痕'
const CHANGE_ORDER_REQUIRED_TIP = '变更单号必填（如 CHG-2026-001），授权映射变更以变更单留痕'

const pageSize = 10
const persona = getAdminPersona() as AdminPersona

const activeTab = ref('roles')

// ==== 清单 tab ====
const rolesLoading = ref(false)
const roles = ref<RbacRoleView[] | null>(null)
const rolesError = ref<Error | null>(null)

/** 三员在前仅前端展示序（lofi C-1：三员〔PLATFORM_ADMIN_ROLES〕在前、业务角色与演示档随后；不加工 permissions 数据）。 */
const orderedRoles = computed<RbacRoleView[]>(() => {
  const list = roles.value ?? []
  const weight = (role: string): number => (PLATFORM_ADMIN_ROLES.includes(role) ? 0 : 1)
  return [...list].sort((a, b) => weight(a.role) - weight(b.role))
})

async function loadRoles(): Promise<void> {
  rolesLoading.value = true
  rolesError.value = null
  try {
    roles.value = await listRbacRoles()
  } catch (err) {
    rolesError.value = err instanceof Error ? err : new Error(String(err))
  } finally {
    rolesLoading.value = false
  }
}

// ==== 授予与收回 tab ====
const grantLoading = ref(false)
const grantResult = ref<string | null>(null)
const grantForm = reactive({
  role: '',
  permission: '',
  action: 'grant' as 'grant' | 'revoke',
  changeOrderNo: '',
})

async function submitGrant(): Promise<void> {
  const orderNo = grantForm.changeOrderNo.trim()
  if (!orderNo) {
    ElMessage.error(CHANGE_ORDER_REQUIRED_TIP)
    return
  }
  if (!grantForm.role.trim() || !grantForm.permission.trim()) {
    ElMessage.error('请先选择或输入角色与权限点')
    return
  }
  grantLoading.value = true
  try {
    const req = { role: grantForm.role.trim(), permission: grantForm.permission.trim(), changeOrderNo: orderNo }
    grantResult.value = grantForm.action === 'grant' ? await grantPermission(req) : await revokePermission(req)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    grantLoading.value = false
  }
}

// ==== 变更留痕 tab（懒加载：首次激活拉取） ====
const changesLoading = ref(false)
const changes = ref<PageData<RbacChangeView> | null>(null)
const changesError = ref<Error | null>(null)
const changesPageNum = ref(1)
const changesLoaded = ref(false)

async function loadChanges(): Promise<void> {
  changesLoading.value = true
  changesError.value = null
  try {
    changes.value = await listRbacChanges(changesPageNum.value, pageSize)
    changesLoaded.value = true
  } catch (err) {
    changesError.value = err instanceof Error ? err : new Error(String(err))
  } finally {
    changesLoading.value = false
  }
}

function onChangesPageChange(page: number): void {
  changesPageNum.value = page
  void loadChanges()
}

watch(activeTab, (tab) => {
  if (tab === 'changes' && !changesLoaded.value) {
    void loadChanges()
  }
})

onMounted(() => {
  if (!getDemoSubject().trim()) {
    rolesError.value = new Error(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void loadRoles()
})
</script>

<style scoped>
.platform-rbac-page {
  padding: 16px;
}
.page-head {
  margin-bottom: 12px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-desc {
  color: #909399;
  font-size: 12px;
}
.tab-toolbar {
  margin-bottom: 10px;
}
.tab-error {
  margin-bottom: 10px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
.perm-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}
.grant-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 640px;
}
.grant-field {
  display: flex;
  align-items: center;
  gap: 10px;
}
.grant-label {
  width: 90px;
  font-size: 13px;
  color: #606266;
}
.rbac-role-select,
.rbac-permission-select {
  width: 280px;
}
.rbac-order-input {
  width: 320px;
}
.grant-actions {
  padding-left: 100px;
}
.grant-result {
  padding: 8px 10px;
  background: #f0f9eb;
  color: #67c23a;
  font-size: 13px;
}
</style>
