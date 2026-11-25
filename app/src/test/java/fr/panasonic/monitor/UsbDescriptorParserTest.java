package fr.panasonic.monitor;

import org.junit.Test;

import static org.junit.Assert.*;

public class UsbDescriptorParserTest {

    @Test
    public void detectsUvcDvFormat() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(sampleDescriptors(0x0C));

        assertTrue(result.hasVideoStreaming);
        assertTrue(result.hasDvFormat);
        assertFalse(result.hasMjpegFormat);
        assertFalse(result.malformed);
    }

    @Test
    public void detectsUvcMjpegFormat() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(sampleDescriptors(0x06));

        assertTrue(result.hasVideoStreaming);
        assertTrue(result.hasMjpegFormat);
        assertFalse(result.hasDvFormat);
    }

    @Test
    public void reportsIsochronousStreamingEndpoint() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(sampleDescriptorsWithIsoEndpoint());

        assertTrue(result.hasIsochronousVideoEndpoint);
    }

    @Test
    public void webcamEngineRequiresUvcMjpegDescriptor() {
        UsbDescriptorParser.ParseResult mjpeg =
                UsbDescriptorParser.parse(sampleDescriptors(0x06));
        UsbDescriptorParser.ParseResult dv =
                UsbDescriptorParser.parse(sampleDescriptors(0x0C));

        assertTrue(PanasonicMode.WEBCAM.isAdvertisedBy(mjpeg));
        assertFalse(PanasonicMode.WEBCAM.isAdvertisedBy(dv));
    }

    @Test
    public void monitorDvEngineRequiresUvcDvDescriptor() {
        UsbDescriptorParser.ParseResult dv =
                UsbDescriptorParser.parse(sampleDescriptors(0x0C));
        UsbDescriptorParser.ParseResult mjpeg =
                UsbDescriptorParser.parse(sampleDescriptors(0x06));

        assertTrue(PanasonicMode.MOTION_DV.isAdvertisedBy(dv));
        assertFalse(PanasonicMode.MOTION_DV.isAdvertisedBy(mjpeg));
    }

    @Test
    public void ignoresIsochronousOutEndpointForVideoInput() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(sampleDescriptorsWithEndpointAddress(0x01));
        assertFalse(result.hasIsochronousVideoEndpoint);
    }

    @Test
    public void handlesTruncatedDescriptorWithoutCrashing() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(new byte[]{9, 2, 0});

        assertTrue(result.malformed);
        assertFalse(result.hasDvFormat);
    }

    @Test
    public void handlesEmptyInput() {
        UsbDescriptorParser.ParseResult result =
                UsbDescriptorParser.parse(new byte[0]);

        assertTrue(result.malformed);
        assertEquals(0, result.descriptorCount);
    }

    private byte[] sampleDescriptors(int videoSubtype) {
        return new byte[]{
                18, 1, 0, 2, 0, 0, 0, 64, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1,
                9, 2, 34, 0, 1, 1, 0, (byte) 0x80, 50,
                9, 4, 1, 0, 0, 14, 2, 0, 0,
                7, 0x24, (byte) videoSubtype, 0, 0, 0, 0
        };
    }

    private byte[] sampleDescriptorsWithIsoEndpoint() {
        return sampleDescriptorsWithEndpointAddress(0x81);
    }

    private byte[] sampleDescriptorsWithEndpointAddress(int address) {
        return new byte[]{
                18, 1, 0, 2, 0, 0, 0, 64, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1,
                9, 2, 34, 0, 1, 1, 0, (byte) 0x80, 50,
                9, 4, 1, 0, 1, 14, 2, 0, 0,
                7, 0x24, 0x0C, 0, 0, 0, 0,
                7, 5, (byte) address, 1, 0, 4, 1
        };
    }
}
