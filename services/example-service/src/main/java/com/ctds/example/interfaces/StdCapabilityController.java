package com.ctds.example.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.example.application.StdCapabilityService;
import com.ctds.example.interfaces.dto.StdCapabilityView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口层：标准能力域探活演示（WBS 2.4.8 H6）——浏览器/curl 直接可见
 * 三个标准域（互联互通 / DID 互认 / 测评证据）的"骨架已建、能力未开放"状态。
 */
@RestController
@RequestMapping("/api/v1/std-capabilities")
public class StdCapabilityController {

    private final StdCapabilityService stdCapabilityService;

    public StdCapabilityController(final StdCapabilityService stdCapabilityService) {
        this.stdCapabilityService = stdCapabilityService;
    }

    /** 三域状态清单（只读；占位期全部 implemented=false）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<StdCapabilityView>> list() {
        final List<StdCapabilityView> views = stdCapabilityService.statuses().stream()
                .map(status -> new StdCapabilityView(
                        status.domain().code(), status.domain().displayName(),
                        status.implemented(), status.message()))
                .toList();
        return ApiResult.ok(views);
    }
}
