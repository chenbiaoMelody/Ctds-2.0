package com.ctds.catalog.infrastructure;

import com.ctds.common.vectorstore.VectorRecord;
import com.ctds.common.vectorstore.VectorStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品语义向量缓存仓储（WBS-3.10.2 hifi §4；MySQL 8 `product_embedding`，BLOB 小端 float32）。
 * 存储损坏/维度不符的行按缺失处理（应用层重算回写）；分块查询避免超长 IN。
 */
@Repository
public class JdbcProductVectorStore implements VectorStore {

    private static final int KEY_CHUNK = 500;

    private final JdbcClient jdbc;
    /** 进程内 L1 缓存（持久层仍为 MySQL；源指纹由应用层校验，变更会触发重算回写）。 */
    private final Map<String, VectorRecord> cache = new ConcurrentHashMap<>();

    public JdbcProductVectorStore(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, VectorRecord> findAll(final Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        final List<String> missing = new ArrayList<>();
        final Map<String, VectorRecord> records = new HashMap<>();
        for (final String key : keys) {
            final VectorRecord cached = cache.get(key);
            if (cached == null) {
                missing.add(key);
            } else {
                records.put(key, cached);
            }
        }
        for (int from = 0; from < missing.size(); from += KEY_CHUNK) {
            final List<String> chunk = missing.subList(from,
                    Math.min(from + KEY_CHUNK, missing.size()));
            final String placeholders = String.join(", ", chunk.stream().map(key -> "?").toList());
            final List<Object> params = new ArrayList<>(chunk);
            jdbc.sql("SELECT product_id, source_hash, dims, vector FROM product_embedding "
                            + "WHERE product_id IN (" + placeholders + ")")
                    .params(params)
                    .query((rs, rowNum) -> {
                        final byte[] bytes = rs.getBytes("vector");
                        final int dims = rs.getInt("dims");
                        if (bytes == null || dims <= 0 || bytes.length != dims * Float.BYTES) {
                            return Optional.<VectorRecord>empty();
                        }
                        final ByteBuffer buffer = ByteBuffer.wrap(bytes)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        final float[] vector = new float[dims];
                        for (int i = 0; i < dims; i++) {
                            vector[i] = buffer.getFloat();
                        }
                        return Optional.of(new VectorRecord(
                                String.valueOf(rs.getLong("product_id")),
                                rs.getString("source_hash"), vector));
                    })
                    .list()
                    .forEach(record -> record.ifPresent(value -> {
                        records.put(value.key(), value);
                        cache.put(value.key(), value);
                    }));
        }
        return Map.copyOf(records);
    }

    @Override
    @Transactional
    public void upsertAll(final Collection<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (final VectorRecord record : records) {
            final long productId = Long.parseLong(record.key());
            final int dims = record.vector().length;
            final ByteBuffer buffer = ByteBuffer.allocate(dims * Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (final float value : record.vector()) {
                buffer.putFloat(value);
            }
            jdbc.sql("INSERT INTO product_embedding (product_id, source_hash, dims, vector) "
                            + "VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE source_hash = ?, dims = ?, vector = ?")
                    .param(productId)
                    .param(record.sourceHash())
                    .param(dims)
                    .param(buffer.array())
                    .param(record.sourceHash())
                    .param(dims)
                    .param(buffer.array())
                    .update();
            cache.put(record.key(), record);
        }
    }
}
