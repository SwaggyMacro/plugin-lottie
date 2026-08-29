package cn.ncii.lottie.extension;

import lombok.Data;
import java.util.List;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "lottie.halo.run", version = "v1alpha1", kind = "LottieAnimation",
    plural = "lottieanimations", singular = "lottieanimation")
public class LottieAnimation extends AbstractExtension {

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
        private String groupName;
        private String mediaType = "application/json";
        private String format = "json";
        private String sha256;
        /** Name of the Halo attachment containing the payload. */
        private LottieConfig defaults = new LottieConfig();
        private Boolean enabled = true;
        private String sourceFileName;
        private List<String> tags = new java.util.ArrayList<>();
        /** Halo attachment backing the animation payload. */
        private String attachmentName;
        private String attachmentUrl;
    }
}
