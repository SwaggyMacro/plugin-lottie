package cn.ncii.lottie.web;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.service.LottieCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

/** Read-only endpoint used by the theme runtime and rendered article nodes. */
@RestController
@ApiVersion("api.lottie.halo.run/v1alpha1")
@RequestMapping
public class LottiePublicController {

    private final LottieCatalogService catalog;

    public LottiePublicController(LottieCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/animations/{name}/content")
    public Mono<ResponseEntity<?>> content(@PathVariable("name") String name) {
        ResponseEntity<?> notFound = ResponseEntity.notFound().build();
        return catalog.getAnimation(name)
            .filter(animation -> animation != null && animation.getSpec() != null
                && Boolean.TRUE.equals(animation.getSpec().getEnabled()))
            .flatMap(this::contentResponse)
            .defaultIfEmpty(notFound);
    }

    private Mono<ResponseEntity<?>> contentResponse(LottieAnimation animation) {
        if (animation == null || animation.getSpec() == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        String attachmentUrl = animation.getSpec().getAttachmentUrl();
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.status(302)
            .header("Location", attachmentUrl)
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .build());
    }
}
