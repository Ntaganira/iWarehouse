package com.ntaganira.heritier.iWarehouse.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : MinioConfig.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The MinIO client (as iVura's), from minio.url, minio.access-key and minio.secret-key (MINIO_URL,
 *               MINIO_ACCESS_KEY, MINIO_SECRET_KEY). Building it does not connect: the app starts while MinIO is down.
 * </pre>
 */
@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${minio.url}") String url, @Value("${minio.access-key}") String accessKey,
                                   @Value("${minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build();
    }
}
