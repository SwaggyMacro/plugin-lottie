package cn.ncii.lottie.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Mono;

/**
 * Fallback for installations where the plugin ReverseProxy extension has not
 * been reconciled yet. The normal route remains /plugins/lottie/assets/**;
 * only the bundled runtime files are exposed here.
 */
@RestController
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LottieStaticController {

    private static final CacheControl CACHE = CacheControl.maxAge(365, TimeUnit.DAYS)
        .cachePublic().immutable();

    /**
     * Spring 6 uses PathPatternParser by default. The capture syntax is
     * important here: a plain `/**` mapping can be claimed by Halo's static
     * resource handler and result in a 200 response with an empty MIME type.
     */
    @RequestMapping(method = RequestMethod.GET, path = "/plugins/lottie/assets/{*assetPath}")
    public Mono<ResponseEntity<byte[]>> asset(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String prefix = "/plugins/lottie/assets/";
        String relative = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        if (relative.startsWith("ui/")) {
            String uiPath = relative.substring("ui/".length());
            if (uiPath.isBlank() || uiPath.contains("..") || uiPath.contains("\\")) {
                return Mono.just(ResponseEntity.notFound().build());
            }
            MediaType mediaType = mediaTypeFor(uiPath);
            if (mediaType == null) {
                return Mono.just(ResponseEntity.notFound().build());
            }
            return resource("ui/" + uiPath, mediaType);
        }
        if (path.endsWith("/lottie-runtime.js")) {
            return resource("static/lottie-runtime.js", MediaType.parseMediaType("application/javascript"));
        }
        if (path.endsWith("/lottie/dotlottie-web.js")) {
            return resource("static/lottie/dotlottie-web.js", MediaType.parseMediaType("application/javascript"));
        }
        if (path.endsWith("/lottie/dotlottie-player.wasm")) {
            return resource("static/lottie/dotlottie-player.wasm", MediaType.parseMediaType("application/wasm"));
        }
        return Mono.just(ResponseEntity.<byte[]>notFound().build());
    }

    private MediaType mediaTypeFor(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return MediaType.parseMediaType("application/javascript");
        }
        if (lower.endsWith(".css")) {
            return MediaType.parseMediaType("text/css");
        }
        if (lower.endsWith(".wasm")) {
            return MediaType.parseMediaType("application/wasm");
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return MediaType.APPLICATION_JSON;
        }
        return null;
    }

    private Mono<ResponseEntity<byte[]>> resource(String classpath, MediaType mediaType) {
        // Resolve through the plugin class loader.  Using the host loader
        // makes every plugin-owned resource look absent in both DevTools and
        // a packaged PF4J JAR.
        return Mono.fromCallable(() -> new ClassPathResource(classpath,
            LottieStaticController.class.getClassLoader()))
            .flatMap(value -> {
                if (!value.exists() || !value.isReadable()) {
                    return Mono.just(ResponseEntity.<byte[]>notFound().build());
                }
                try {
                    byte[] bytes = value.getInputStream().readAllBytes();
                    return Mono.just(ResponseEntity.ok()
                        .cacheControl(CACHE)
                        .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                        .contentType(mediaType)
                        .contentLength(bytes.length)
                        .body(bytes));
                } catch (IOException exception) {
                    return Mono.just(ResponseEntity.<byte[]>status(500).build());
                }
            });
    }
}
