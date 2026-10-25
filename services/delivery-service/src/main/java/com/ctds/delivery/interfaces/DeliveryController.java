package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.DeliveryCommand;
import com.ctds.delivery.application.DeliveryCommandService;
import com.ctds.delivery.application.DeliveryQueryService;
import com.ctds.delivery.application.DeliveryQueryService.PackageDownload;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.interfaces.dto.DeliveryRequest;
import com.ctds.delivery.interfaces.dto.DeliveryViews.ActionLogView;
import com.ctds.delivery.interfaces.dto.DeliveryViews.DeliveryView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交付记录与文件交付端点（hifi §7.1）：发起 / 参与者读面 / 加密包领取。
 * 身份取 AuthContext（网关注入/演示期身份头）；一切可见性判定在服务端。
 */
@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryCommandService commandService;
    private final DeliveryQueryService queryService;

    public DeliveryController(final DeliveryCommandService commandService,
            final DeliveryQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** 发起 API 或 FILE 交付；幂等键走 X-Idempotency-Key（缺失/非法由服务层收敛 1009C0005）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.request")
    public ApiResult<DeliveryView> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) final String idempotencyKey,
            @RequestBody final DeliveryRequest request) {
        final DeliveryCommand command = new DeliveryCommand(AuthContext.subject(), request.contractNo(),
                request.deliveryType(), request.fileId(), request.purpose(), request.territory(),
                idempotencyKey);
        return ApiResult.ok(commandService.create(command));
    }

    /** 参与者详情（需求方/提供方）；非参与方/不存在同码同文 1009C0007。 */
    @GetMapping(path = "/{deliveryNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<DeliveryView> get(@PathVariable final String deliveryNo) {
        return ApiResult.ok(queryService.get(deliveryNo, AuthContext.subject()));
    }

    /** 参与者列表（按调用者过滤；pageSize 上限 100）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<PageResult<DeliveryView>> list(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String deliveryType,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final String orderBy) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, orderBy);
        return ApiResult.ok(queryService.list(AuthContext.subject(), contractNo, deliveryType, page));
    }

    /**
     * G7a 合约动作留痕分页（WBS-3.5.6 Q5-A）：参与方可见本合约全部留痕
     * （含他人越权探测 DENIED 行——C-5.1 S3-7 界面承载）；读取零新增留痕（防递归）。
     */
    @GetMapping(path = "/{contractNo}/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<PageResult<ActionLogView>> actionLogs(
            @PathVariable final String contractNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.participantActionLogs(contractNo, AuthContext.subject(),
                PageQuery.of(pageNum, pageSize, null)));
    }

    /** 加密交付包领取（仅合约需求方）；返回加密件 + 明文 SM3 头，不返回明文。 */
    @GetMapping("/{deliveryNo}/package")
    @RequirePermission("delivery.package.download")
    public ResponseEntity<Resource> download(@PathVariable final String deliveryNo) {
        final PackageDownload download = queryService.download(deliveryNo, AuthContext.subject());
        final long length;
        try {
            length = Files.size(download.path());
        } catch (final IOException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, e);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Ctds-Sm3", download.checksum())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .body(new FileSystemResource(download.path()));
    }
}
