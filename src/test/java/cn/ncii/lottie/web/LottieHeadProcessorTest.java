package cn.ncii.lottie.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LottieHeadProcessorTest {

    @Test
    void disablesAllSkeletonStylesWithMasterSwitch() {
        var settings = new LottieSkeletonSettings();
        settings.setSkeletonEnabled(false);

        var style = LottieHeadProcessor.toSkeletonStyle(settings);

        assertFalse(style.enabled());
    }

    @Test
    void keepsBackgroundBorderAndLoaderControlsIndependent() {
        var settings = new LottieSkeletonSettings();
        settings.setSkeletonLoaderEnabled(false);
        settings.setSkeletonBackgroundEnabled(false);
        settings.setSkeletonBorderEnabled(false);

        var style = LottieHeadProcessor.toSkeletonStyle(settings);

        assertTrue(style.enabled());
        assertFalse(style.loaderEnabled());
        assertEquals("transparent", style.background());
        assertEquals("transparent", style.frameBorder());
        assertEquals("none", style.breatheShadow());
        assertTrue(style.cssVariables().contains("--halo-lottie-skeleton-loader-display:none"));
    }
}
