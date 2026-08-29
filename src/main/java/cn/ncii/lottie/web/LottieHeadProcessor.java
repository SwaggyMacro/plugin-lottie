package cn.ncii.lottie.web;

import org.springframework.stereotype.Component;
import org.thymeleaf.model.AttributeValueQuotes;
import org.thymeleaf.model.IModel;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/** Makes the custom element available to all activated themes. */
@Component
public class LottieHeadProcessor implements TemplateHeadProcessor {

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        var factory = context.getModelFactory();
        // Use the stable single-attribute overload and add the second
        // attribute through the model factory. Some Halo distributions ship
        // a Thymeleaf API without the Map overload.
        var script = factory.createOpenElementTag("script", "type", "module",
            false);
        script = factory.setAttribute(script, "src",
            "/plugins/lottie/assets/lottie-runtime.js", AttributeValueQuotes.DOUBLE);
        model.add(script);
        model.add(factory.createCloseElementTag("script"));
        return Mono.empty();
    }
}
