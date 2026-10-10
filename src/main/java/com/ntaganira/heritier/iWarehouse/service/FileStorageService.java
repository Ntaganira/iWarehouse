package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import io.minio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FileStorageService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : File storage in MinIO (SRS 3.1), ported from iVura: puts and reads objects in the minio.bucket bucket,
 *               created the first time it is needed (not at start, so the app runs while MinIO is down). Files are read
 *               back through the app, which checks who may see them, never through links to MinIO: the warehouse LAN
 *               need not reach it. When MinIO cannot be reached the user is told (file.storageDown), nothing is half saved.
 *               Objects are never deleted: a removed document keeps its file.
 * </pre>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public FileStorageService(MinioClient client, @Value("${minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /** Stores the bytes under the key. */
    public void put(String key, byte[] bytes, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(contentType).build());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not store {} in MinIO: {}", key, e.getMessage());
            throw BusinessException.of("file.storageDown");
        }
    }

    /** The object's content; the caller closes it. */
    public InputStream open(String key) {
        try {
            ensureBucket();
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not read {} from MinIO: {}", key, e.getMessage());
            throw BusinessException.of("file.storageDown");
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }
}
