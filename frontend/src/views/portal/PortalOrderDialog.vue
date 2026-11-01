<template>
  <el-dialog
    :model-value="true"
    :title="`订购 · ${productName}`"
    width="640px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <div class="order-dialog-body">
      <div class="pricing-line">
        <span class="pricing-label">定价</span>
        <span class="pricing-value">{{ pricingText }}</span>
      </div>

      <el-form label-width="88px" label-position="left">
        <el-form-item label="合约模板" required>
          <el-select
            v-model="selectedTemplateNo"
            class="template-select"
            placeholder="选择启用中的合约模板"
            :loading="templatesLoading"
            @change="onTemplateChange"
          >
            <el-option
              v-for="item in templates"
              :key="item.templateNo"
              :label="`${item.name}（${item.templateNo} V${item.currentVersion}）`"
              :value="item.templateNo"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template v-if="framework">
        <h4 class="section-title">条款</h4>
        <ClauseEditor mode="framework" :framework="framework" v-model="clauseSlots" />
        <h4 class="section-title">使用控制策略</h4>
        <StrategyEditor v-model="strategy" />
      </template>

      <p class="order-notice">
        提交后将生成订单并同步发起合约草案；协商与签署请在合约工作台完成。
      </p>
      <el-alert v-if="errorTip" class="order-error" :title="errorTip" type="error" :closable="false" show-icon />
    </div>

    <template #footer>
      <el-button class="cancel-order-btn" @click="emit('close')">取消</el-button>
      <el-button
        class="submit-order-btn"
        type="primary"
        :loading="submitting"
        @click="onSubmit"
      >
        提交订购
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import ClauseEditor from '../contract/components/ClauseEditor.vue'
import StrategyEditor from '../contract/components/StrategyEditor.vue'
import {
  createOrder,
  type OrderResult,
} from '../../api/order'
import { listTemplates, getTemplate, type ClauseFramework, type ContractTemplateItem } from '../../api/contract'
import { newIdempotencyKey } from '../../api/client'
import { formatPortalPricing, PRICING_MODEL_LABELS, labelOf } from '../../constants/catalog'

/**
 * 订购弹窗（WBS-3.7.3 hifi §4.2）：选择启用模板 + 填写必填条款 + 策略（复用 ClauseEditor /
 * StrategyEditor，不新建第二套条款语义）；提交即订单 + 合约草案同事务落库（W-OR1）。
 * - 必填前置：模板必选 + 必填槽位非空 → 否则零请求提示（沿 3.4.7 发起弹窗口径）；
 * - 幂等：弹窗打开生成一次 requestId（newIdempotencyKey），失败重提复用同键（Q6-A）；
 * - 成功：emit('ordered', orderNo)——路由跳转由父组件承担；失败：后端文案原样展示，
 *   弹窗状态不变（无乐观更新）；定价只读展示复用 formatPortalPricing 统一口径。
 */
const props = defineProps<{
  productId: number
  productName: string
  pricingModel: string
  priceAmount: string | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'ordered', orderNo: string): void
}>()

// 定价展示过 labelOf 中文映射后入 formatPortalPricing（与列表/详情两视图同一口径，不显示原值码）
const pricingText = computed(() =>
  formatPortalPricing(labelOf(PRICING_MODEL_LABELS, props.pricingModel), props.priceAmount))

/** 弹窗生命周期幂等键（挂载生成一次；失败重提复用——hifi §4.2）。 */
const requestId = newIdempotencyKey()

const templates = ref<ContractTemplateItem[]>([])
const templatesLoading = ref(false)
const selectedTemplateNo = ref('')
const framework = ref<ClauseFramework | null>(null)
const clauseSlots = ref<{ slots: Record<string, string> }>({ slots: {} })
const strategy = ref<Record<string, unknown>>({})
const submitting = ref(false)
const errorTip = ref('')

async function loadTemplates() {
  templatesLoading.value = true
  try {
    const page = await listTemplates({ pageNum: 1, pageSize: 50 })
    templates.value = page.list
  } catch (error) {
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    templatesLoading.value = false
  }
}

async function onTemplateChange(templateNo: string) {
  framework.value = null
  errorTip.value = ''
  try {
    const detail = await getTemplate(templateNo)
    framework.value = detail.clauseFramework
  } catch (error) {
    errorTip.value = error instanceof Error ? error.message : String(error)
  }
}

function missingRequiredSlot(): boolean {
  return (framework.value?.slots ?? []).some(
    (slot) => slot.required && !(clauseSlots.value.slots[slot.key] ?? '').trim(),
  )
}

async function onSubmit() {
  if (submitting.value) {
    return
  }
  if (!selectedTemplateNo.value) {
    errorTip.value = '请先选择合约模板'
    return
  }
  if (missingRequiredSlot()) {
    errorTip.value = '请填写必填条款'
    return
  }
  errorTip.value = ''
  submitting.value = true
  try {
    const template = templates.value.find((item) => item.templateNo === selectedTemplateNo.value)
    const result: OrderResult = await createOrder({
      productId: props.productId,
      templateNo: selectedTemplateNo.value,
      templateVersionNo: template?.currentVersion ?? 1,
      requestId,
      clauseValues: {
        slots: { ...clauseSlots.value.slots },
        strategy: { ...strategy.value },
      },
    })
    emit('ordered', result.orderNo)
  } catch (error) {
    // 后端文案原样展示（1011C0001 / 1008 门槛链透传），弹窗状态不变、requestId 复用
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    submitting.value = false
  }
}

void loadTemplates()
</script>

<style scoped>
.order-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pricing-line {
  font-size: 14px;
}

.pricing-label {
  color: var(--el-text-color-secondary);
  margin-right: 8px;
}

.pricing-value {
  font-weight: 600;
}

.template-select {
  width: 100%;
}

.section-title {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.order-notice {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.order-error {
  width: auto;
}
</style>
