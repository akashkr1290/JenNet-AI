package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.ai.AiQualityResult;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.exception.ImageQualityRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-032: unusable photos are rejected before a complaint exists (SRS 21.3, 17.2). */
@ExtendWith(MockitoExtension.class)
class ImageQualityGateTest {

    @Mock private AiServiceClient aiServiceClient;
    @Mock private AiServiceProperties aiServiceProperties;
    private ImageQualityGate gate;

    @BeforeEach
    void setUp() {
        gate = new ImageQualityGate(aiServiceClient, aiServiceProperties);
        lenient().when(aiServiceProperties.isEnabled()).thenReturn(true);
    }

    private static MockMultipartFile jpeg(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return new MockMultipartFile("photo", "p.jpg", "image/jpeg", out.toByteArray());
    }

    @Test
    void belowFourEightyPIsRejectedLocallyWithoutCallingAi() throws Exception {
        assertThatThrownBy(() -> gate.check(jpeg(640, 400)))
                .isInstanceOf(ImageQualityRejectedException.class)
                .hasMessageContaining("retake");
        verify(aiServiceClient, never()).checkQuality(anyString());
    }

    @Test
    void blurryPhotoReportedByAiIsRejectedWithItsRetakePrompt() throws Exception {
        when(aiServiceClient.checkQuality(anyString())).thenReturn(
                new AiQualityResult(false, "BLURRY", 800, 600, "The photo is too blurry. Please retake it."));

        assertThatThrownBy(() -> gate.check(jpeg(800, 600)))
                .isInstanceOf(ImageQualityRejectedException.class)
                .hasMessage("The photo is too blurry. Please retake it.");
    }

    @Test
    void acceptablePhotoPasses() throws Exception {
        when(aiServiceClient.checkQuality(anyString())).thenReturn(new AiQualityResult(true, "ACCEPTABLE", 800, 600, null));
        assertThatCode(() -> gate.check(jpeg(800, 600))).doesNotThrowAnyException();
    }

    @Test
    void anAiOutageNeverBlocksASubmission() throws Exception {
        when(aiServiceClient.checkQuality(anyString()))
                .thenThrow(new AiServiceCallException("AI_SERVICE_UNREACHABLE", "down"));
        assertThatCode(() -> gate.check(jpeg(800, 600))).doesNotThrowAnyException();
    }

    @Test
    void aiDisabledSkipsTheBlurCheckButKeepsTheResolutionCheck() throws Exception {
        when(aiServiceProperties.isEnabled()).thenReturn(false);
        assertThatCode(() -> gate.check(jpeg(800, 600))).doesNotThrowAnyException();
        verify(aiServiceClient, never()).checkQuality(anyString());
        assertThatThrownBy(() -> gate.check(jpeg(300, 300))).isInstanceOf(ImageQualityRejectedException.class);
    }
}
