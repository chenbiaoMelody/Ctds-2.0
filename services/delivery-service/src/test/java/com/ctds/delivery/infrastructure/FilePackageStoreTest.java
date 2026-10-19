package com.ctds.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.DeliveryBizException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 加密包存储安全单测（hifi §5.2）：原子落盘、包标识唯一、缺失 fail-closed。 */
class FilePackageStoreTest {

    @TempDir
    Path temp;

    @Test
    void storeAndRequirePackage() throws Exception {
        final FilePackageStore store = store();
        store.store("DL000001.sm4", new byte[] {1, 2, 3});
        assertThat(store.requirePath("DL000001.sm4")).exists();
        assertThat(Files.readAllBytes(store.requirePath("DL000001.sm4")))
                .containsExactly(1, 2, 3);
    }

    @Test
    void duplicatePackageIdIsRejectedWithoutOverwrite() throws Exception {
        final FilePackageStore store = store();
        store.store("DL000001.sm4", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> store.store("DL000001.sm4", new byte[] {9, 9, 9}))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0003"));
        assertThat(Files.readAllBytes(store.requirePath("DL000001.sm4")))
                .containsExactly(1, 2, 3);
    }

    @Test
    void missingPackageFailsClosed() {
        final FilePackageStore store = store();
        assertThatThrownBy(() -> store.requirePath("DL000002.sm4"))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0003"));
    }

    private FilePackageStore store() {
        final DeliveryProperties properties = new DeliveryProperties();
        properties.setStorageRoot(temp.resolve("store").toString());
        final FilePackageStore store = new FilePackageStore(properties);
        store.init();
        return store;
    }
}
