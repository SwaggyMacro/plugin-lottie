package cn.ncii.lottie;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.extension.LottieGroup;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.Scheme;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/** Registers the plugin-owned resources used by the animation library. */
@Component
public class LottiePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public LottiePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(LottieGroup.class);
        schemeManager.register(LottieAnimation.class);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(LottieAnimation.class));
        schemeManager.unregister(Scheme.buildFromType(LottieGroup.class));
    }
}
