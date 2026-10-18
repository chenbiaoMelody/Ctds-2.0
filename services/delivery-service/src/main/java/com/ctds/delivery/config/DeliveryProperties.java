package com.ctds.delivery.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * ctds.delivery 配置项（hifi §1/§4/§5）。文件源清单为部署/演示配置（Q3-A），
 * 不接受用户路径；口令/密钥只经环境变量或 KeyProvider，不入配置、不入库。
 */
@ConfigurationProperties(prefix = "ctds.delivery")
public class DeliveryProperties {

    /** 文件源根目录（清单内相对路径的基准）；未配置 = 文件交付不可用（fail-closed）。 */
    private String sourceRoot;

    /** 加密包存储根目录；未配置 = 文件交付不可用（fail-closed）。 */
    private String storageRoot;

    private final Contract contract = new Contract();

    private final Catalog catalog = new Catalog();

    private final File file = new File();

    /** 文件源清单（fileId 精确键 → productId + 相对路径 + 展示名）。 */
    private List<FileEntry> files = new ArrayList<>();

    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(final String value) {
        this.sourceRoot = value;
    }

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(final String value) {
        this.storageRoot = value;
    }

    public Contract getContract() {
        return contract;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public File getFile() {
        return file;
    }

    public List<FileEntry> getFiles() {
        return files;
    }

    public void setFiles(final List<FileEntry> value) {
        this.files = (value == null) ? new ArrayList<>() : value;
    }

    /** 合约内部只读摘要 / 判定服务衔接。 */
    public static class Contract {

        private String baseUrl;

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration timeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String value) {
            this.baseUrl = value;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration value) {
            this.timeout = value;
        }
    }

    /** 目录内部产品只读衔接。 */
    public static class Catalog {

        private String baseUrl;

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration timeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String value) {
            this.baseUrl = value;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration value) {
            this.timeout = value;
        }
    }

    /** 文件交付参数（Q6-A）。 */
    public static class File {

        /** 加密密钥编号（非密钥材料）；实际密钥由 KeyProvider/KMS 提供。 */
        private String keyRef = "delivery-file";

        /** 明文字节上限；不得超过 common-crypto 的 64MiB 上限。 */
        private long maxBytes = 64L * 1024 * 1024;

        public String getKeyRef() {
            return keyRef;
        }

        public void setKeyRef(final String value) {
            this.keyRef = value;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(final long value) {
            this.maxBytes = value;
        }
    }

    /** 单条文件源清单。 */
    public static class FileEntry {

        private String fileId;
        private long productId;
        private String path;
        private String displayName;

        public String getFileId() {
            return fileId;
        }

        public void setFileId(final String value) {
            this.fileId = value;
        }

        public long getProductId() {
            return productId;
        }

        public void setProductId(final long value) {
            this.productId = value;
        }

        public String getPath() {
            return path;
        }

        public void setPath(final String value) {
            this.path = value;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(final String value) {
            this.displayName = value;
        }
    }
}
