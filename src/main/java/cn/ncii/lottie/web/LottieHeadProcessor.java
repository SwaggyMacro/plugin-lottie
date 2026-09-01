package cn.ncii.lottie.web;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.model.AttributeValueQuotes;
import org.thymeleaf.model.IModel;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/** Makes the custom element available to all activated themes. */
@Component
public class LottieHeadProcessor implements TemplateHeadProcessor {

    private static final Pattern HEX_COLOR = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    private static final SkeletonStyle DEFAULT_SKELETON_STYLE = new SkeletonStyle(
        true, true, "rgb(243 244 246 / 100%)", "#d7dbe0", "#d7dbe0", "#6b7280",
        "rgb(107 114 128 / 10%)", "inset 0 0 24px rgb(107 114 128 / 10%)", 8);

    private final ReactiveSettingFetcher settingFetcher;

    public LottieHeadProcessor(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        return settingFetcher.fetch("skeleton", LottieSkeletonSettings.class)
            .map(LottieHeadProcessor::toSkeletonStyle)
            .defaultIfEmpty(DEFAULT_SKELETON_STYLE)
            .onErrorReturn(DEFAULT_SKELETON_STYLE)
            .doOnNext(style -> addAssets(context, model, style))
            .then();
    }

    private static SkeletonStyle toSkeletonStyle(LottieSkeletonSettings settings) {
        if (settings == null) {
            return DEFAULT_SKELETON_STYLE;
        }
        String background = validColor(settings.getSkeletonBackgroundColor(), "#f3f4f6");
        String border = validColor(settings.getSkeletonBorderColor(), "#d7dbe0");
        String accent = validColor(settings.getSkeletonAccentColor(), "#6b7280");
        int opacity = clamp(settings.getSkeletonBackgroundOpacity(), 100, 0, 100);
        int radius = clamp(settings.getSkeletonBorderRadius(), 8, 0, 64);
        boolean backgroundEnabled = !Boolean.FALSE.equals(settings.getSkeletonBackgroundEnabled());
        return new SkeletonStyle(
            !Boolean.FALSE.equals(settings.getSkeletonEnabled()),
            !Boolean.FALSE.equals(settings.getSkeletonLoaderEnabled()),
            backgroundEnabled ? rgb(background, opacity) : "transparent",
            Boolean.FALSE.equals(settings.getSkeletonBorderEnabled()) ? "transparent" : border,
            border, accent, rgb(accent, 10),
            backgroundEnabled ? "inset 0 0 24px " + rgb(accent, 10) : "none", radius);
    }

    private static String validColor(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return HEX_COLOR.matcher(normalized).matches() ? normalized.toLowerCase(Locale.ROOT) : fallback;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String rgb(String hex, int opacity) {
        String expanded = hex.length() == 4
            ? "#" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2)
                + hex.charAt(3) + hex.charAt(3)
            : hex;
        int red = Integer.parseInt(expanded.substring(1, 3), 16);
        int green = Integer.parseInt(expanded.substring(3, 5), 16);
        int blue = Integer.parseInt(expanded.substring(5, 7), 16);
        return "rgb(" + red + " " + green + " " + blue + " / " + opacity + "%)";
    }

    private static void addAssets(ITemplateContext context, IModel model, SkeletonStyle style) {
        var factory = context.getModelFactory();
        if (style.enabled()) {
            var stylesheet = factory.createStandaloneElementTag("link", "rel", "stylesheet",
                false, false);
            stylesheet = factory.setAttribute(stylesheet, "href",
                "/plugins/lottie/assets/lottie/lottie-skeleton.css",
                AttributeValueQuotes.DOUBLE);
            model.add(stylesheet);
            model.add(factory.createOpenElementTag("style"));
            model.add(factory.createText(style.cssVariables()));
            model.add(factory.createCloseElementTag("style"));
        }
        // Use the stable single-attribute overload and add the second
        // attribute through the model factory. Some Halo distributions ship
        // a Thymeleaf API without the Map overload.
        var script = factory.createOpenElementTag("script", "type", "module",
            false);
        script = factory.setAttribute(script, "data-halo-lottie-runtime", "true",
            AttributeValueQuotes.DOUBLE);
        script = factory.setAttribute(script, "data-halo-lottie-skeleton-enabled",
            Boolean.toString(style.enabled()), AttributeValueQuotes.DOUBLE);
        script = factory.setAttribute(script, "src",
            "/plugins/lottie/assets/lottie-runtime.js", AttributeValueQuotes.DOUBLE);
        model.add(script);
        model.add(factory.createCloseElementTag("script"));
    }

    private record SkeletonStyle(boolean enabled, boolean loaderEnabled, String background,
                                 String frameBorder, String border, String accent,
                                 String accentShadow, String breatheShadow, int radius) {

        private String cssVariables() {
            // Custom properties on :root inherit into the comment widget's
            // Shadow DOM, while a halo-lottie selector in document CSS cannot.
            return ":root{--halo-lottie-skeleton-background:" + background
                + ";--halo-lottie-skeleton-frame-border:" + frameBorder
                + ";--halo-lottie-skeleton-border:" + border
                + ";--halo-lottie-skeleton-accent:" + accent
                + ";--halo-lottie-skeleton-accent-shadow:" + accentShadow
                + ";--halo-lottie-skeleton-loader-display:" + (loaderEnabled ? "block" : "none")
                + ";--halo-lottie-skeleton-breathe-shadow:" + breatheShadow
                + ";--halo-lottie-skeleton-radius:" + radius + "px;}";
        }
    }
}
