package cn.ncii.lottie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.ncii.lottie.service.LottieCatalogService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

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
}
