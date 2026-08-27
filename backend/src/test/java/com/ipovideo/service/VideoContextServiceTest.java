package com.ipovideo.service;

import com.ipovideo.dto.VideoContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class VideoContextServiceTest {

    @Autowired
    private VideoContextService videoContextService;

    @Test
    void buildContextFromTextVideo() throws Exception {
        Path video = Path.of("target/stage8-test/video-text.mp4");
        if (!Files.exists(video)) {
            return; // 没有样例视频就跳过
        }
        Path workDir = Path.of("target/stage8-test/ctx");
        Files.createDirectories(workDir);

        VideoContext context = videoContextService.buildContext(video, workDir);
        assertNotNull(context);
        assertNotNull(context.segments());
        assertFalse(context.segments().isEmpty(), "至少应有一个片段");
    }
}
