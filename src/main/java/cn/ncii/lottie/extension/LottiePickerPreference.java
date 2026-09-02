package cn.ncii.lottie.extension;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/** Stores picker history in Halo so it is available across devices. */
@GVK(group = "lottie.halo.run", version = "v1alpha1", kind = "LottiePickerPreference",
    plural = "lottieprefs", singular = "lottiepref")
public class LottiePickerPreference extends AbstractExtension {

    private Spec spec = new Spec();

    public Spec getSpec() {
        return spec;
    }

    public void setSpec(Spec spec) {
        this.spec = spec;
    }

    @Data
    public static class Spec {
        private List<String> recentNames = new ArrayList<>();
    }
}
