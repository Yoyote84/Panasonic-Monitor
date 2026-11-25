package fr.panasonic.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Step 1: enumerate USB devices, request Android permissions, and inspect descriptors.
 * No video endpoint is opened and no video frames are captured in this diagnostic build.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 41;
    private static final int USB_CLASS_VIDEO = 0x0E;

    private UsbManager usbManager;
    private TextView statusView;
    private TextView reportView;
    private final StringBuilder report = new StringBuilder();
    private final ArrayList<UsbDevice> permissionQueue = new ArrayList<>();
    private UsbDevice pendingCameraDevice;
    private boolean waitingForUsbPermission;
    private String permissionAction;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!permissionAction.equals(intent.getAction())) {
                return;
            }

            UsbDevice device = getUsbDeviceExtra(intent);
            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            waitingForUsbPermission = false;

            if (device == null) {
                append("Réponse d'autorisation USB reçue sans appareil associé.\n");
            } else if (granted) {
                append("\nAutorisation USB accordée : " + deviceLabel(device) + "\n");
                inspectRawDescriptors(device);
            } else {
                append("\nAutorisation USB refusée : " + deviceLabel(device) + "\n");
            }

            renderReport();
            requestNextUsbPermission();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        permissionAction = getPackageName() + ".USB_PERMISSION";
        buildUi();
        registerUsbPermissionReceiver();

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            statusView.setText("USB Host non disponible sur cet appareil.");
        } else {
            refreshStatus();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xFF10151C);

        TextView title = new TextView(this);
        title.setText("PANASONIC MONITOR");
        title.setTextColor(0xFFF2F5F8);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setLetterSpacing(0.06f);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("ÉTAPE 1 · DIAGNOSTIC USB");
        subtitle.setTextColor(0xFF7DB6D8);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        TextView explanation = new TextView(this);
        explanation.setText("Branche le NV-GS140 au téléphone avec un adaptateur USB-C OTG et un câble de données. Allume le caméscope, choisis son mode USB, puis lance l'analyse. Le test lit les descripteurs, pas la vidéo.");
        explanation.setTextColor(0xFFD3DCE5);
        explanation.setTextSize(14);
        explanation.setPadding(0, 0, 0, dp(12));
        root.addView(explanation);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFD18A);
        statusView.setTextSize(13);
        statusView.setPadding(0, 0, 0, dp(12));
        root.addView(statusView);

        Button scanButton = new Button(this);
        scanButton.setText("ANALYSER LES APPAREILS USB");
        scanButton.setOnClickListener(v -> scanConnectedDevices());
        root.addView(scanButton, fullWidthButton());

        Button shareButton = new Button(this);
        shareButton.setText("PARTAGER / EXPORTER LE RAPPORT");
        shareButton.setOnClickListener(v -> shareReport());
        root.addView(shareButton, fullWidthButton());

        Button clearButton = new Button(this);
        clearButton.setText("EFFACER LE RAPPORT");
        clearButton.setOnClickListener(v -> {
            report.setLength(0);
            append("Rapport effacé. Branche le caméscope puis relance l'analyse.\n");
            renderReport();
        });
        root.addView(clearButton, fullWidthButton());

        TextView note = new TextView(this);
        note.setText("Note Android : une autorisation « Caméra » peut être demandée pour accéder à un périphérique USB vidéo. Panasonic Monitor n'utilise pas la caméra du téléphone.");
        note.setTextColor(0xFF9AA8B7);
        note.setTextSize(12);
        note.setPadding(0, dp(10), 0, dp(12));
        root.addView(note);

        reportView = new TextView(this);
        reportView.setTextColor(0xFFE7EDF3);
        reportView.setTextSize(12);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setText("Aucun rapport.\n");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(reportView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private LinearLayout.LayoutParams fullWidthButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        params.bottomMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void registerUsbPermissionReceiver() {
        IntentFilter filter = new IntentFilter(permissionAction);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void refreshStatus() {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        statusView.setText("USB Host prêt · " + devices.size()
                + " appareil(s) USB actuellement détecté(s).");
    }

    private void scanConnectedDevices() {
        report.setLength(0);
        permissionQueue.clear();
        waitingForUsbPermission = false;
        pendingCameraDevice = null;

        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.FRANCE).format(new Date());
        append("PANASONIC MONITOR — RAPPORT DE DIAGNOSTIC USB\n");
        append("Date du test : " + timestamp + "\n");
        append("Téléphone : " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n");
        append("USB Host : "
                + getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                + "\n\n");

        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        append("Appareils détectés : " + devices.size() + "\n");

        if (devices.isEmpty()) {
            append("Aucun périphérique visible. Vérifie le câble de données, l'adaptateur OTG, l'alimentation du caméscope et son mode USB.\n");
            renderReport();
            refreshStatus();
            return;
        }

        for (UsbDevice device : devices.values()) {
            append("\n==================================================\n");
            append(describeDevice(device));
            if (usbManager.hasPermission(device)) {
                inspectRawDescriptors(device);
            } else {
                permissionQueue.add(device);
            }
        }

        renderReport();
        refreshStatus();
        requestNextUsbPermission();
    }

    private String describeDevice(UsbDevice device) {
        StringBuilder out = new StringBuilder();
        out.append("Appareil : ").append(deviceLabel(device)).append('\n');
        out.append(String.format(Locale.US, "VID:PID : %04X:%04X\n",
                device.getVendorId(), device.getProductId()));
        out.append(String.format(Locale.US,
                "Classe appareil : 0x%02X · sous-classe : 0x%02X · protocole : 0x%02X\n",
                device.getDeviceClass(), device.getDeviceSubclass(), device.getDeviceProtocol()));
        out.append("Nom système : ").append(device.getDeviceName()).append('\n');
        out.append("Interfaces : ").append(device.getInterfaceCount()).append('\n');

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            out.append(String.format(Locale.US,
                    "  Interface %d : classe=0x%02X sous-classe=0x%02X protocole=0x%02X alt=%d endpoints=%d\n",
                    intf.getId(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                    intf.getInterfaceProtocol(), intf.getAlternateSetting(),
                    intf.getEndpointCount()));

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                out.append(String.format(Locale.US,
                        "    Endpoint 0x%02X · %s · %s · paquet max=%d · intervalle=%d\n",
                        endpoint.getAddress(),
                        endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        endpointType(endpoint.getType()),
                        endpoint.getMaxPacketSize(),
                        endpoint.getInterval()));
            }
        }
        return out.toString();
    }

    private void inspectRawDescriptors(UsbDevice device) {
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                append("Impossible d'ouvrir l'appareil pour lire ses descripteurs bruts.\n");
                return;
            }

            byte[] raw = connection.getRawDescriptors();
            append("\n--- DESCRIPTEURS USB BRUTS ---\n");
            if (raw == null || raw.length == 0) {
                append("Android n'a retourné aucun descripteur brut.\n");
                return;
            }

            append("Taille brute : " + raw.length + " octets\n");
            UsbDescriptorParser.ParseResult parsed = UsbDescriptorParser.parse(raw);
            append(parsed.toReport());

            append("\n--- INTERPRÉTATION PRUDENTE ---\n");
            if (parsed.hasDvFormat) {
                append("Le périphérique annonce un descripteur UVC de format DV. Cela ne prouve pas encore que le flux DV peut être ouvert ni décodé sur Android.\n");
            } else {
                append("Aucun VS_FORMAT_DV n'a été trouvé dans les descripteurs lus. Cela peut signifier que ce mode n'est pas exposé dans la configuration USB active, ou que le caméscope doit être placé dans un autre mode.\n");
            }
            if (parsed.hasMjpegFormat) {
                append("Le périphérique annonce aussi un descripteur UVC de format MJPEG.\n");
            }
            if (parsed.hasIsochronousVideoEndpoint) {
                append("Un endpoint vidéo isochrone est décrit. L'API USB Java standard d'Android ne sait pas le transférer : un transport natif devra être étudié à l'étape 2.\n");
            }
        } catch (SecurityException e) {
            append("Accès refusé par Android : " + safeMessage(e) + "\n");
        } catch (RuntimeException e) {
            append("Erreur pendant la lecture des descripteurs : " + safeMessage(e) + "\n");
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void requestNextUsbPermission() {
        if (waitingForUsbPermission || pendingCameraDevice != null || permissionQueue.isEmpty()) {
            renderReport();
            return;
        }

        UsbDevice device = permissionQueue.remove(0);
        if (isVideoDevice(device)
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCameraDevice = device;
            append("\nAndroid exige l'autorisation « Caméra » pour accéder à ce périphérique USB vidéo. L'application n'ouvre pas la caméra du téléphone.\n");
            renderReport();
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }

        requestUsbPermission(device);
    }

    private void requestUsbPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            inspectRawDescriptors(device);
            renderReport();
            requestNextUsbPermission();
            return;
        }

        Intent permissionIntent = new Intent(permissionAction);
        permissionIntent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, permissionIntent, flags);

        waitingForUsbPermission = true;
        append("\nDemande d'autorisation USB pour " + deviceLabel(device) + "…\n");
        renderReport();
        usbManager.requestPermission(device, pendingIntent);
    }

    private boolean isVideoDevice(UsbDevice device) {
        if (device.getDeviceClass() == USB_CLASS_VIDEO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }

        UsbDevice device = pendingCameraDevice;
        pendingCameraDevice = null;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (granted && device != null) {
            requestUsbPermission(device);
        } else {
            append("\nAutorisation « Caméra » refusée : Android ne permet donc pas à l'application de demander l'accès USB à ce périphérique vidéo.\n");
            renderReport();
            requestNextUsbPermission();
        }
    }

    private UsbDevice getUsbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private String deviceLabel(UsbDevice device) {
        String product = device.getProductName();
        String manufacturer = device.getManufacturerName();
        StringBuilder label = new StringBuilder();
        if (manufacturer != null && !manufacturer.trim().isEmpty()) {
            label.append(manufacturer.trim()).append(' ');
        }
        if (product != null && !product.trim().isEmpty()) {
            label.append(product.trim());
        }
        if (label.length() == 0) {
            label.append("Périphérique USB");
        }
        return label.toString();
    }

    private String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "ISOCHRONOUS";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "INTERRUPT";
            default:
                return "INCONNU";
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private void append(String text) {
        report.append(text);
    }

    private void renderReport() {
        reportView.setText(report.toString());
    }

    private void shareReport() {
        if (report.length() == 0) {
            Toast.makeText(this, "Aucun rapport à partager.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Panasonic Monitor — diagnostic USB");
        send.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(send, "Partager le rapport USB"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }
}
