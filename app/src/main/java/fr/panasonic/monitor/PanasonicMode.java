package fr.panasonic.monitor;

/**
 * Software engines offered by Panasonic Monitor.
 * The camera mode is identified from UVC VideoStreaming descriptors.
 * VID/PID values are never used to decide which engine is allowed.
 */
public enum PanasonicMode {
    WEBCAM(0, 0x06, "Webcam", "UVC MJPEG", "Flux webcam UVC Motion-JPEG"),
    MOTION_DV(1, 0x0C, "Monitor DV / Motion DV", "UVC DV", "Flux DV natif UVC");

    public final int nativeValue;
    public final int requiredFormatSubtype;
    public final String label;
    public final String formatLabel;
    public final String description;

    PanasonicMode(int nativeValue, int requiredFormatSubtype, String label,
                  String formatLabel, String description) {
        this.nativeValue = nativeValue;
        this.requiredFormatSubtype = requiredFormatSubtype;
        this.label = label;
        this.formatLabel = formatLabel;
        this.description = description;
    }

    public boolean isAdvertisedBy(UsbDescriptorParser.ParseResult descriptors) {
        if (descriptors == null) return false;
        switch (this) {
            case WEBCAM:
                return descriptors.hasMjpegFormat;
            case MOTION_DV:
                return descriptors.hasDvFormat;
            default:
                return false;
        }
    }

    public String descriptorName() {
        return this == WEBCAM ? "VS_FORMAT_MJPEG (0x06)" : "VS_FORMAT_DV (0x0C)";
    }
}
