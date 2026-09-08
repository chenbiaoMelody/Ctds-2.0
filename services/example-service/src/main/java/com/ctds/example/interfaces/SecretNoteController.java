package com.ctds.example.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.example.application.SecretNoteService;
import com.ctds.example.domain.SecretNote;
import com.ctds.example.interfaces.dto.SecretNoteRequest;
import com.ctds.example.interfaces.dto.SecretNoteView;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口层：保密备注演示（WBS 2.4.6 B11）——存的是密文、取回是明文、篡改被拒。
 * 加解密一律经 common-crypto 统一入口（红线：全平台唯一加解密入口）。
 */
@RestController
@RequestMapping("/api/v1/secret-notes")
public class SecretNoteController {

    private final SecretNoteService secretNoteService;

    public SecretNoteController(final SecretNoteService secretNoteService) {
        this.secretNoteService = secretNoteService;
    }

    /** 明文写入 → SM4 加密后存信封；回 id。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<UUID> create(@RequestBody final SecretNoteRequest request) {
        final SecretNote saved = secretNoteService.create(request.text());
        return ApiResult.ok(saved.id());
    }

    /** 按 id 取回：解密返回明文（密文被篡改时报 1001C0002"数据校验未通过，已拒绝"）。 */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SecretNoteView> read(@PathVariable final UUID id) {
        return ApiResult.ok(new SecretNoteView(id, secretNoteService.read(id)));
    }

    /** 演示面：直接查看库里存的密文信封（证明"落盘的不是明文"）。 */
    @GetMapping(path = "/{id}/envelope", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<String> envelope(@PathVariable final UUID id) {
        return ApiResult.ok(secretNoteService.storedEnvelope(id));
    }

    /** 演示用：翻转已存密文中部一个比特（模拟落库后被篡改），随后读取应被拒绝。 */
    @PostMapping(path = "/{id}/tamper", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> tamper(@PathVariable final UUID id) {
        secretNoteService.demoTamper(id);
        return ApiResult.ok(null);
    }
}
