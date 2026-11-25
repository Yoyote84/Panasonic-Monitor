package fr.panasonic.monitor;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanasonicModeTest {
    @Test
    public void mapsObservedWebcamProductId() {
        assertEquals(PanasonicMode.WEBCAM, PanasonicMode.fromProductId(0x231A));
    }

    @Test
    public void mapsObservedMotionDvProductId() {
        assertEquals(PanasonicMode.MOTION_DV, PanasonicMode.fromProductId(0x2318));
    }

    @Test
    public void rejectsUnknownProductId() {
        assertNull(PanasonicMode.fromProductId(0x1234));
    }
}
