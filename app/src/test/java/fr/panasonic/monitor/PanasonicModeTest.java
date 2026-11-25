package fr.panasonic.monitor;

import org.junit.Test;

import static org.junit.Assert.*;

public class PanasonicModeTest {
    @Test
    public void webcamModeRequiresMjpegFormatDescriptor() {
        UsbDescriptorParser.ParseResult mjpeg = new UsbDescriptorParser.ParseResult();
        mjpeg.hasMjpegFormat = true;
        UsbDescriptorParser.ParseResult dv = new UsbDescriptorParser.ParseResult();
        dv.hasDvFormat = true;

        assertTrue(PanasonicMode.WEBCAM.isAdvertisedBy(mjpeg));
        assertFalse(PanasonicMode.WEBCAM.isAdvertisedBy(dv));
    }

    @Test
    public void monitorDvModeRequiresDvFormatDescriptor() {
        UsbDescriptorParser.ParseResult dv = new UsbDescriptorParser.ParseResult();
        dv.hasDvFormat = true;
        UsbDescriptorParser.ParseResult mjpeg = new UsbDescriptorParser.ParseResult();
        mjpeg.hasMjpegFormat = true;

        assertTrue(PanasonicMode.MOTION_DV.isAdvertisedBy(dv));
        assertFalse(PanasonicMode.MOTION_DV.isAdvertisedBy(mjpeg));
    }

    @Test
    public void unknownOrMissingDescriptorDoesNotAuthorizeEitherMode() {
        assertFalse(PanasonicMode.WEBCAM.isAdvertisedBy(null));
        assertFalse(PanasonicMode.MOTION_DV.isAdvertisedBy(null));
        UsbDescriptorParser.ParseResult empty = new UsbDescriptorParser.ParseResult();
        assertFalse(PanasonicMode.WEBCAM.isAdvertisedBy(empty));
        assertFalse(PanasonicMode.MOTION_DV.isAdvertisedBy(empty));
    }

    @Test
    public void nativeModeValuesRemainStable() {
        assertEquals(0, PanasonicMode.WEBCAM.nativeValue);
        assertEquals(1, PanasonicMode.MOTION_DV.nativeValue);
    }
}
