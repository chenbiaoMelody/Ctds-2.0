package com.ctds.delivery.domain;

import java.nio.file.Path;

/** 已校验的文件源（路径只在服务端使用，不对外暴露、不入库）。 */
public record FileSource(String fileId, long productId, Path path, String displayName, long size) {
}
