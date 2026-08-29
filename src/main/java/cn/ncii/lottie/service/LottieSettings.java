package cn.ncii.lottie.service;

/** Values exposed by the plugin's lottie-settings definition. */
public class LottieSettings {

    private Boolean readAnimationDimensions = true;
    private Integer defaultWidth = 160;
    private Integer defaultHeight = 160;

    public Boolean getReadAnimationDimensions() {
        return readAnimationDimensions;
    }

    public void setReadAnimationDimensions(Boolean readAnimationDimensions) {
        this.readAnimationDimensions = readAnimationDimensions;
    }

    public Integer getDefaultWidth() {
        return defaultWidth;
    }

    public void setDefaultWidth(Integer defaultWidth) {
        this.defaultWidth = defaultWidth;
    }

    public Integer getDefaultHeight() {
        return defaultHeight;
    }

    public void setDefaultHeight(Integer defaultHeight) {
        this.defaultHeight = defaultHeight;
    }
}