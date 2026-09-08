package com.ctds.common.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地密钥文件实现（Q3 模式 A）：启动一次性加载为只读缓存，文件永不回写。
 * 坏配置 = 启动失败并指明原因（fail-fast，沿 2.4.5 评审教训）；报错只含行号/编号/原因类别，不回显密钥值。
 * 文件命名约定：以 {@code *.keys} 结尾（.gitignore 已拦截，红线：密钥不入库；评审①P2-2/②P2-4）。
 */
public final class LocalFileKeyProvider implements KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFileKeyProvider.class);
    private static final int SM4_KEY_BYTES = 16;

    private final Map<String, byte[]> keys;

    private LocalFileKeyProvider(final Map<String, byte[]> loaded) {
        this.keys = loaded;
    }

    /** 未配置密钥文件时的空供给（所有取密钥请求报 KEY_UNAVAILABLE；装配处已 WARN 一次）。 */
    public static LocalFileKeyProvider empty() {
        return new LocalFileKeyProvider(Map.of());
    }

    /** 加载并校验密钥文件（properties 形 {@code 密钥编号=Base64(16字节)}，# 注释行）。 */
    public static LocalFileKeyProvider fromFile(final Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("SM4 密钥文件不存在或不可读: " + file, e);
        }
        final Map<String, byte[]> loaded = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            final int lineNo = i + 1;
            final String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int sep = line.indexOf('=');
            if (sep <= 0 || sep == line.length() - 1) {
                throw new IllegalStateException("SM4 密钥文件第" + lineNo + "行格式错误（应为 密钥编号=Base64(16字节)）");
            }
            final String keyRef = line.substring(0, sep).trim();
            if (keyRef.isEmpty() || keyRef.length() > Inputs.MAX_KEY_REF_CHARS
                    || !keyRef.matches(Inputs.KEY_REF_PATTERN)) {
                // 字符集限制与服务入口一致（评审②P3-1：防非法编号进日志/映射）
                throw new IllegalStateException("SM4 密钥文件第" + lineNo + "行密钥编号为空、超"
                        + Inputs.MAX_KEY_REF_CHARS + "字符或含非法字符（仅允许字母数字 . _ -）");
            }
            if (loaded.containsKey(keyRef)) {
                throw new IllegalStateException("SM4 密钥文件第" + lineNo + "行密钥编号重复: " + keyRef);
            }
            loaded.put(keyRef, decodeKey(line.substring(sep + 1).trim(), lineNo, keyRef));
        }
        return new LocalFileKeyProvider(Map.copyOf(loaded));
    }

    private static byte[] decodeKey(final String value, final int lineNo, final String keyRef) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("SM4 密钥文件第" + lineNo + "行（" + keyRef + "）值不是合法 Base64");
        }
        if (decoded.length != SM4_KEY_BYTES) {
            throw new IllegalStateException("SM4 密钥文件第" + lineNo + "行（" + keyRef + "）密钥长度必须为 16 字节");
        }
        return decoded;
    }

    @Override
    public byte[] sm4Key(final String keyRef) {
        final byte[] key = keys.get(keyRef);
        if (key == null) {
            log.warn("SM4 key not found: keyRef={}", keyRef);
            throw Inputs.keyUnavailable();
        }
        return key.clone();
    }
}
