package fr.panasonic.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Defensive parser for the byte stream returned by UsbDeviceConnection.getRawDescriptors().
 * It intentionally inspects descriptors only; it does not start a video stream.
 */
public final class UsbDescriptorParser {
    private static final int USB_DT_INTERFACE = 0x04;
    private static final int USB_DT_ENDPOINT = 0x05;
    private static final int USB_DT_CS_INTERFACE = 0x24;
    private static final int USB_CLASS_VIDEO = 0x0E;
    private static final int USB_SUBCLASS_VIDEO_CONTROL = 0x01;
    private static final int USB_SUBCLASS_VIDEO_STREAMING = 0x02;
    private static final int VS_FORMAT_MJPEG = 0x06;
    private static final int VS_FORMAT_DV = 0x0C;

    private UsbDescriptorParser() {
    }

    public static final class ParseResult {
        public final List<String> lines = new ArrayList<>();
        public boolean hasVideoControl;
        public boolean hasVideoStreaming;
        public boolean hasDvFormat;
        public boolean hasMjpegFormat;
        public boolean hasIsochronousVideoEndpoint;
        public int descriptorCount;
        public boolean malformed;

        public String toReport() {
            StringBuilder out = new StringBuilder();
            out.append("Descripteurs lus : ").append(descriptorCount).append('\n');
            out.append("Interface UVC VideoControl : ").append(yesNo(hasVideoControl)).append('\n');
            out.append("Interface UVC VideoStreaming : ").append(yesNo(hasVideoStreaming)).append('\n');
            out.append("Format UVC DV (VS_FORMAT_DV 0x0C) : ").append(yesNo(hasDvFormat)).append('\n');
            out.append("Format UVC MJPEG (VS_FORMAT_MJPEG 0x06) : ").append(yesNo(hasMjpegFormat)).append('\n');
            out.append("Point de terminaison vidéo isochrone IN : ")
                    .append(yesNo(hasIsochronousVideoEndpoint)).append('\n');
            if (malformed) {
                out.append("ATTENTION : descripteurs incomplets ou mal formés détectés.\n");
            }
            out.append("\nDétail des descripteurs :\n");
            for (String line : lines) {
                out.append(line).append('\n');
            }
            return out.toString();
        }

        private static String yesNo(boolean value) {
            return value ? "OUI" : "NON / non annoncé";
        }
    }

    public static ParseResult parse(byte[] raw) {
        ParseResult result = new ParseResult();
        if (raw == null || raw.length == 0) {
            result.malformed = true;
            result.lines.add("Aucun descripteur brut retourné par Android.");
            return result;
        }

        int offset = 0;
        int currentClass = -1;
        int currentSubclass = -1;
        int currentInterface = -1;
        int currentAlternate = -1;

        while (offset < raw.length) {
            if (raw.length - offset < 2) {
                result.malformed = true;
                result.lines.add(String.format(Locale.US,
                        "Offset 0x%04X : en-tête tronqué.", offset));
                break;
            }

            int length = u8(raw[offset]);
            int type = u8(raw[offset + 1]);

            if (length < 2 || offset + length > raw.length) {
                result.malformed = true;
                result.lines.add(String.format(Locale.US,
                        "Offset 0x%04X : longueur invalide (%d octets).", offset, length));
                break;
            }

            result.descriptorCount++;
            String detail = String.format(Locale.US,
                    "0x%04X  len=%d  type=0x%02X", offset, length, type);

            if (type == USB_DT_INTERFACE && length >= 9) {
                currentInterface = u8(raw[offset + 2]);
                currentAlternate = u8(raw[offset + 3]);
                currentClass = u8(raw[offset + 5]);
                currentSubclass = u8(raw[offset + 6]);

                if (currentClass == USB_CLASS_VIDEO) {
                    if (currentSubclass == USB_SUBCLASS_VIDEO_CONTROL) {
                        result.hasVideoControl = true;
                        detail += "  Interface VideoControl"
                                + " #" + currentInterface + " alt=" + currentAlternate;
                    } else if (currentSubclass == USB_SUBCLASS_VIDEO_STREAMING) {
                        result.hasVideoStreaming = true;
                        detail += "  Interface VideoStreaming"
                                + " #" + currentInterface + " alt=" + currentAlternate;
                    } else {
                        detail += "  Interface vidéo sous-classe=0x"
                                + hex2(currentSubclass);
                    }
                } else {
                    detail += "  Interface classe=0x" + hex2(currentClass)
                            + " sous-classe=0x" + hex2(currentSubclass);
                }
            } else if (type == USB_DT_CS_INTERFACE && length >= 3
                    && currentClass == USB_CLASS_VIDEO
                    && currentSubclass == USB_SUBCLASS_VIDEO_STREAMING) {
                int subtype = u8(raw[offset + 2]);
                if (subtype == VS_FORMAT_DV) {
                    result.hasDvFormat = true;
                    detail += "  VS_FORMAT_DV (format DV annoncé)";
                } else if (subtype == VS_FORMAT_MJPEG) {
                    result.hasMjpegFormat = true;
                    detail += "  VS_FORMAT_MJPEG (format MJPEG annoncé)";
                } else {
                    detail += "  Descripteur UVC VideoStreaming subtype=0x" + hex2(subtype);
                }
            } else if (type == USB_DT_ENDPOINT && length >= 7) {
                int address = u8(raw[offset + 2]);
                int attributes = u8(raw[offset + 3]);
                int transferType = attributes & 0x03;
                int packetSize = u8(raw[offset + 4]) | (u8(raw[offset + 5]) << 8);
                String transferName = transferTypeName(transferType);
                detail += "  Endpoint 0x" + hex2(address) + " " + transferName
                        + " maxPacket=" + packetSize;
                if (currentClass == USB_CLASS_VIDEO
                        && currentSubclass == USB_SUBCLASS_VIDEO_STREAMING
                        && transferType == 1
                        && (address & 0x80) != 0) {
                    result.hasIsochronousVideoEndpoint = true;
                }
            }

            result.lines.add(detail);
            offset += length;
        }

        return result;
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    private static String hex2(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private static String transferTypeName(int type) {
        switch (type) {
            case 0:
                return "CONTROL";
            case 1:
                return "ISOCHRONOUS";
            case 2:
                return "BULK";
            case 3:
                return "INTERRUPT";
            default:
                return "INCONNU";
        }
    }
}
