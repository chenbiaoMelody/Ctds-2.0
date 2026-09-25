/**
 * 跨 API 模块共享类型（WBS-3.1.12 F5 类型收口：全前端唯一声明，禁止各模块重复定义）。
 */

/** 分页数据（与后端 common/pagination PageResult 字段同构）。 */
export interface PageData<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}
