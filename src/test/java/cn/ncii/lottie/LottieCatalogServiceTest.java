package cn.ncii.lottie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.service.LottieCatalogRepository;
import cn.ncii.lottie.service.LottieCatalogService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.plugin.ReactiveSettingFetcher;

class LottieCatalogServiceTest {

    @Test
    void normalizesTgsToJson() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write("{\"v\":\"5.7.0\",\"fr\":30}".getBytes(StandardCharsets.UTF_8));
        }
        LottieCatalogService.ImportCandidate candidate = new LottieCatalogService()
            .normalizeUpload("sticker.tgs", output.toByteArray());
        assertEquals("json", candidate.format());
        assertEquals("application/json", candidate.mediaType());
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class,
            () -> new LottieCatalogService().normalizeUpload("sticker.gif", new byte[] {1}));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class,
            () -> new LottieCatalogService().normalizeUpload("sticker.json",
                "{\"v\":}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsDotLottieWithoutManifest() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("animations/sticker.json"));
            zip.write("{\"v\":\"5.7.0\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class,
            () -> new LottieCatalogService().normalizeUpload("sticker.lottie", output.toByteArray()));
    }

    @Test
    void acceptsDotLottieWithManifest() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{\"version\":\"1.0\",\"animations\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        LottieCatalogService.ImportCandidate candidate = new LottieCatalogService()
            .normalizeUpload("sticker.lottie", output.toByteArray());
        assertEquals("lottie", candidate.format());
    }

    @Test
    void usesConfiguredArchiveFileLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (int index = 0; index < 2; index++) {
                zip.putNextEntry(new ZipEntry("animation-" + index + ".json"));
                zip.write("{\"v\":\"5.7.0\"}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);
        LottieCatalogService.PluginSettings settings = new LottieCatalogService.PluginSettings();
        settings.maxFiles = 1;
        when(settingFetcher.fetch("general", LottieCatalogService.PluginSettings.class))
            .thenReturn(Mono.just(settings));

        LottieCatalogService service = new LottieCatalogService(null, null, null, settingFetcher);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> service.previewZipWithSettings(output.toByteArray()).block());
        assertEquals("Archive contains more than 1 files", exception.getMessage());
    }

    @Test
    void reordersAnimationsWithinGroup() {
        LottieAnimation first = animation("first", 0);
        LottieAnimation second = animation("second", 1);
        LottieAnimation third = animation("third", 2);
        LottieCatalogRepository repository = mock(LottieCatalogRepository.class);
        when(repository.listAnimations()).thenReturn(Flux.just(third, first, second));
        when(repository.saveAnimation(any(LottieAnimation.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        new LottieCatalogService(repository, null, null, null)
            .reorderAnimations("stickers", List.of("second", "third", "first"))
            .block();

        assertEquals(2, first.getSpec().getSort());
        assertEquals(0, second.getSpec().getSort());
        assertEquals(1, third.getSpec().getSort());
        verify(repository, times(3)).saveAnimation(any(LottieAnimation.class));
    }

    private static LottieAnimation animation(String name, int sort) {
        LottieAnimation animation = new LottieAnimation();
        animation.setMetadata(new Metadata());
        animation.getMetadata().setName(name);
        animation.getSpec().setGroupName("stickers");
        animation.getSpec().setSort(sort);
        return animation;
    }
}
