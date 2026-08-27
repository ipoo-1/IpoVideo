package com.ipovideo.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest
@ActiveProfiles("test")
class OcrUtilsTest {

    @Autowired
    private OcrUtils ocrUtils;

    @Test
    void recognizeChineseScreenshot() throws Exception {
        Path image = Path.of("../docs/images/login-register.png");
        if (!Files.exists(image)) {
            return; // 截图不存在就跳过
        }
        String text = ocrUtils.recognizeText(image.toString());
        org.junit.jupiter.api.Assertions.assertFalse(text.isBlank(), "应识别出文字");
    }
}
