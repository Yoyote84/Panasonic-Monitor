package fr.panasonic.monitor;

/** The two USB personalities confirmed on the user's NV-GS140. */
public enum PanasonicMode {
    WEBCAM(0x231A, "Webcam", "Mode webcam USB"),
    MOTION_DV(0x2318, "Monitor DV", "Mode Motion DV / flux DV");

    public final int productId;
    public final String label;
    public final String description;

    PanasonicMode(int productId, String label, String description) {
        this.productId = productId;
        this.label = label;
        this.description = description;
    }

    public static PanasonicMode fromProductId(int productId) {
        for (PanasonicMode mode : values()) {
            if (mode.productId == productId) {
                return mode;
            }
        }
        return null;
    }
}
