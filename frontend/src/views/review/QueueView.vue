<script setup lang="ts">
/**
 * WBS-3.1.5 审核工作台 · 待审核清单（规格 C-1.1 行为 5 第 1 条前半）：
 * 固定"待审核"过滤 + 分页；仅 subject.review 权限角色可见（演示期 admin 兼任审核员）。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchReviewQueue, type ReviewQueueItem } from '../../api/subject'
import { ApiError } from '../../api/client'
import { subjectTypeLabel } from '../../constants/subject'

const router = useRouter()
const loading = ref(false)
const items = ref<ReviewQueueItem[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchReviewQueue(pageNum.value, pageSize.value)
    items.value = page.list
    total.value = page.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '清单加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openDetail(row: ReviewQueueItem): void {
  void router.push(`/review/${row.subjectNo}`)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">主体审核</h2>
    <p class="page-desc">待审核主体清单——查看认证档案后执行"通过"或"驳回"（驳回需填写理由）。</p>
    <el-card shadow="never">
      <el-table :data="items" v-loading="loading" empty-text="暂无待审核主体">
        <el-table-column prop="subjectNo" label="申请编号" width="180" />
        <el-table-column prop="subjectName" label="主体名称" min-width="200" />
        <el-table-column label="主体类型" width="120">
          <template #default="scope">{{ subjectTypeLabel(scope.row.subjectType) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="180" />
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button type="primary" link @click="openDetail(scope.row)">查看档案</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="(p: number) => { pageNum = p; void load() }"
      />
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

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
