package cn.ncii.lottie.extension;

import lombok.Data;

/** Shared animation defaults used by the library and editor insertion options. */
@Data
public class LottieConfig {

    private Integer width = 160;
    private Integer height = 160;
    private Boolean autoplay = true;
    private Boolean loop = true;
    private Double speed = 1.0;
    private String fit = "contain";
    private String align = "center";
    private Boolean controls = false;
    private Boolean hoverPlay = false;
    private Boolean freezeOnOffscreen = true;
    private String ariaLabel;
}
