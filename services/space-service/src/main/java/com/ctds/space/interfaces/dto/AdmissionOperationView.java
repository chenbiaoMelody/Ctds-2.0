package com.ctds.space.interfaces.dto;

/**
 * 准入操作结果视图（WBS-3.2.4 lofi Q3-A 幂等三态的响应形态）：
 * alreadyMember=true 时 member 命中（返回既有成员关系，剧本 S1-3"返回既有成员"口径），
 * 否则 admission 命中（新建单或既有待处理单）。
 */
public record AdmissionOperationView(boolean alreadyMember, AdmissionView admission, MemberView member) {
}
