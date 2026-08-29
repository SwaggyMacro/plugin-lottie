package cn.ncii.lottie.extension;

import lombok.Data;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "lottie.halo.run", version = "v1alpha1", kind = "LottieGroup",
    plural = "lottiegroups", singular = "lottiegroup")
public class LottieGroup extends AbstractExtension {

    private Spec spec = new Spec();

    public Spec getSpec() {
        return spec;
    }

    public void setSpec(Spec spec) {
        this.spec = spec;
    }

    @Data
    public static class Spec {
        private String displayName;
        private String parentName;
        private String description;
        private Integer sort = 0;
    }
}
