package com.ipovideo.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {


    @Bean
    public MinioClient minioClient(@Value("${minio.endpoint}")String endpoint,
                                   @Value("${minio.accessKey}")String accessKey,
                                   @Value("${minio.secretKey}")String secretKey){
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey,secretKey)
                .build();
    }


    }

