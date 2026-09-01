package cn.ncii.lottie.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the runtime before Halo's reverse-proxy/static handlers get a chance
 * to turn a missing resource into an empty 200 response. A filter is used here
 * because it is registered as a normal Spring bean and therefore does not
 * depend on the custom API controller route registry.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LottieAssetWebFilter implements WebFilter {

    private static final String PREFIX = "/plugins/lottie/assets/";
    private static final CacheControl CACHE = CacheControl.maxAge(365, TimeUnit.DAYS)
        .cachePublic().immutable();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        Asset asset = asset(path);
        if (asset == null) {
            return chain.filter(exchange);
        }
        // Plugin resources are loaded by PF4J's isolated class loader.  The
        // one-argument ClassPathResource constructor uses the application
        // class loader instead, which cannot see files packaged by a plugin
        // (and is especially visible in DevTools directory mode).
        Resource resource = new ClassPathResource(asset.classpath(),
            LottieAssetWebFilter.class.getClassLoader());
        if (!resource.exists() || !resource.isReadable()) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return Mono.fromCallable(() -> resource.getInputStream().readAllBytes())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(bytes -> {
                var response = exchange.getResponse();
                response.setStatusCode(HttpStatus.OK);
                response.getHeaders().setContentType(asset.mediaType());
                response.getHeaders().setContentLength(bytes.length);
                response.getHeaders().setCacheControl(CACHE);
                response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "inline");
                return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
            })
            .onErrorResume(IOException.class, error -> {
                exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return exchange.getResponse().setComplete();
            });
    }

    private static Asset asset(String path) {
        if (!path.startsWith(PREFIX)) return null;
        String relative = path.substring(PREFIX.length());
        if (relative.startsWith("ui/")) {
            String uiPath = relative.substring("ui/".length());
            // The UI bundler emits hashed files and nested chunks. Resolve
            // only known static extensions and reject traversal attempts.
            if (uiPath.isBlank() || uiPath.contains("..") || uiPath.contains("\\")) {
                return null;
            }
            MediaType mediaType = mediaTypeFor(uiPath);
            return mediaType == null ? null : new Asset("ui/" + uiPath, mediaType);
        }
        return switch (relative) {
            case "lottie-runtime.js" -> new Asset("static/lottie-runtime.js",
                MediaType.parseMediaType("application/javascript"));
            case "lottie/dotlottie-web.js" -> new Asset("static/lottie/dotlottie-web.js",
                MediaType.parseMediaType("application/javascript"));
            case "lottie/dotlottie-player.wasm" -> new Asset("static/lottie/dotlottie-player.wasm",
                MediaType.parseMediaType("application/wasm"));
            case "lottie/lottie-skeleton.css" -> new Asset("static/lottie/lottie-skeleton.css",
                MediaType.parseMediaType("text/css"));
            default -> null;
        };
    }

    private static MediaType mediaTypeFor(String path) {
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
        if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".map")) {
            return MediaType.APPLICATION_JSON;
        }
        return null;
    }

    private record Asset(String classpath, MediaType mediaType) {}
}
