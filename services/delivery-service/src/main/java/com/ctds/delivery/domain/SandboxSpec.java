package com.ctds.delivery.domain;

import java.nio.file.Path;

/**
 * 沙箱容器启动规格（hifi §4.1）。路径只在服务端使用，且必须落在配置根目录内；
 * 不包含用户输入的命令、镜像或宿主机路径。
 */
public record SandboxSpec(String sandboxNo, Path inputDir, Path outputDir, Path jobsDir,
        String image, double cpus, int memoryMb) {
}
