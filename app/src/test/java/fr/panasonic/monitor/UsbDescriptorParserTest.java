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
        return new byte[]{
                18, 1, 0, 2, 0, 0, 0, 64, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1,
                9, 2, 34, 0, 1, 1, 0, (byte) 0x80, 50,
                9, 4, 1, 0, 1, 14, 2, 0, 0,
                7, 0x24, 0x0C, 0, 0, 0, 0,
                7, 5, (byte) 0x81, 1, 0, 4, 1
        };
    }
}
