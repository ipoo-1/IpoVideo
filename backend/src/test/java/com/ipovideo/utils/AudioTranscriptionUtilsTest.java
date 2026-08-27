package com.ipovideo.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest
@ActiveProfiles("test")
class AudioTranscriptionUtilsTest {

    @Autowired
    private AudioTranscriptionUtils audioTranscriptionUtils;

    @Test
    void transcribeSampleAudio() throws Exception {
        Path audio = Path.of("target/stage8-test/test.mp3");
        if (!Files.exists(audio)) {
            return; // 没有样例音频就跳过
        }
        String text = audioTranscriptionUtils.transcribe(audio);
        org.junit.jupiter.api.Assertions.assertNotNull(text, "ASR 应返回文本");
    }
}
