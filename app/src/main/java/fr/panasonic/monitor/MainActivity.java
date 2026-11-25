package fr.panasonic.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Step 3: camera mode selection, native UVC diagnostics and live video preview.
 * The USB event thread copies UVC packets to a bounded native queue; FFmpeg
 * assembles and decodes frames on a worker before rendering them to a Surface.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 41;
    private static final int USB_CLASS_VIDEO = 0x0E;
    private static final int PANASONIC_VENDOR_ID = 0x04DA;
    private static final String PREFS = "panasonic_monitor";
    private static final String PREF_MODE = "selected_mode";

    private static boolean nativeAvailable;
    static {
        try {
            System.loadLibrary("panasonic_usb");
            nativeAvailable = true;
        } catch (UnsatisfiedLinkError error) {
            nativeAvailable = false;
        }
    }

    private UsbManager usbManager;
    private TextView statusView;
    private TextView selectedModeView;
    private TextView reportView;
    private Button nativeTestButton;
    private Button previewStartButton;
    private Button previewStopButton;
    private SurfaceView previewSurfaceView;
    private volatile boolean previewSurfaceReady;
    private volatile boolean previewRunning;
    private RadioButton webcamRadio;
    private RadioButton dvRadio;
    private final StringBuilder report = new StringBuilder();
    private final ArrayList<UsbDevice> permissionQueue = new ArrayList<>();
    private UsbDevice pendingCameraDevice;
    private UsbDevice selectedDevice;
    private UsbDescriptorParser.ParseResult selectedDescriptorResult;
    private boolean waitingForUsbPermission;
    private boolean testRunning;
    private String permissionAction;
    private PanasonicMode selectedMode = PanasonicMode.MOTION_DV;

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
                selectedDevice = device;
                selectedDescriptorResult = inspectRawDescriptors(device);
                appendModeCompatibility(device);
            } else {
                append("\nAutorisation USB refusée : " + deviceLabel(device) + "\n");
            }

            renderReport();
            refreshStatus();
            updateNativeButton();
            requestNextUsbPermission();
        }
    };

    private native String nativeProbeAndSample(int usbFileDescriptor, int mode, int durationMs);
    private native String nativeStartPreview(int usbFileDescriptor, int mode, Surface surface);
    private native void nativePreparePreview();
    private native void nativeStopPreview();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        permissionAction = getPackageName() + ".USB_PERMISSION";

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedMode = PanasonicMode.valueOf(preferences.getString(
                PREF_MODE, PanasonicMode.MOTION_DV.name()));

        buildUi();
        registerUsbPermissionReceiver();

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            statusView.setText("USB Host non disponible sur cet appareil.");
        } else {
            refreshStatus();
        }
        updateNativeButton();
        updatePreviewButtons();
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
        subtitle.setText("ÉTAPE 3 · DÉCODAGE ET APERÇU VIDÉO EN DIRECT");
        subtitle.setTextColor(0xFF7DB6D8);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle);

        TextView explanation = new TextView(this);
        explanation.setText("Choisis le moteur logiciel Webcam (UVC MJPEG) ou Monitor DV (UVC DV). La compatibilité est vérifiée dans les descripteurs réels du périphérique ; le VID/PID est affiché à titre informatif et ne verrouille pas ton choix.");
        explanation.setTextColor(0xFFD3DCE5);
        explanation.setTextSize(14);
        explanation.setPadding(0, 0, 0, dp(10));
        root.addView(explanation);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(LinearLayout.VERTICAL);

        webcamRadio = new RadioButton(this);
        webcamRadio.setId(View.generateViewId());
        webcamRadio.setText("WEBCAM  ·  UVC MJPEG");
        webcamRadio.setTextColor(0xFFE7EDF3);
        webcamRadio.setTextSize(15);
        modeGroup.addView(webcamRadio);

        dvRadio = new RadioButton(this);
        dvRadio.setId(View.generateViewId());
        dvRadio.setText("MONITOR DV / MOTION DV  ·  UVC DV");
        dvRadio.setTextColor(0xFFE7EDF3);
        dvRadio.setTextSize(15);
        modeGroup.addView(dvRadio);

        modeGroup.check(selectedMode == PanasonicMode.WEBCAM
                ? webcamRadio.getId() : dvRadio.getId());
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (previewRunning) {
                Toast.makeText(this, "Arrête l'aperçu avant de changer de moteur.", Toast.LENGTH_SHORT).show();
                modeGroup.check(selectedMode == PanasonicMode.WEBCAM
                        ? webcamRadio.getId() : dvRadio.getId());
                return;
            }
            selectedMode = checkedId == webcamRadio.getId()
                    ? PanasonicMode.WEBCAM : PanasonicMode.MOTION_DV;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_MODE, selectedMode.name()).apply();
            selectedModeView.setText("Moteur choisi : " + selectedMode.label
                    + " — format requis : " + selectedMode.descriptorName());
            refreshStatus();
            updateNativeButton();
            updatePreviewButtons();
        });
        root.addView(modeGroup);

        selectedModeView = new TextView(this);
        selectedModeView.setTextColor(0xFFFFD18A);
        selectedModeView.setTextSize(13);
        selectedModeView.setPadding(0, dp(4), 0, dp(10));
        selectedModeView.setText("Moteur choisi : " + selectedMode.label
                + " — format requis : " + selectedMode.descriptorName());
        root.addView(selectedModeView);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFD18A);
        statusView.setTextSize(13);
        statusView.setPadding(0, 0, 0, dp(10));
        root.addView(statusView);

        TextView previewLabel = new TextView(this);
        previewLabel.setText("APERÇU CAMÉSCOPE");
        previewLabel.setTextColor(0xFF7DB6D8);
        previewLabel.setTextSize(13);
        previewLabel.setPadding(0, dp(4), 0, dp(6));
        root.addView(previewLabel);

        previewSurfaceView = new SurfaceView(this);
        previewSurfaceView.setBackgroundColor(0xFF000000);
        previewSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                previewSurfaceReady = true;
                updatePreviewButtons();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                previewSurfaceReady = holder.getSurface() != null
                        && holder.getSurface().isValid();
                updatePreviewButtons();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                previewSurfaceReady = false;
                stopLivePreview();
                updatePreviewButtons();
            }
        });
        root.addView(previewSurfaceView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        previewStartButton = new Button(this);
        previewStartButton.setText("DÉMARRER L'APERÇU EN DIRECT");
        previewStartButton.setOnClickListener(v -> startLivePreview());
        root.addView(previewStartButton, fullWidthButton());

        previewStopButton = new Button(this);
        previewStopButton.setText("ARRÊTER L'APERÇU");
        previewStopButton.setOnClickListener(v -> stopLivePreview());
        root.addView(previewStopButton, fullWidthButton());

        Button scanButton = new Button(this);
        scanButton.setText("DÉTECTER ET ANALYSER LE MODE USB");
        scanButton.setOnClickListener(v -> scanConnectedDevices());
        root.addView(scanButton, fullWidthButton());

        nativeTestButton = new Button(this);
        nativeTestButton.setText("TESTER LE TRANSPORT NATIF (5 SECONDES)");
        nativeTestButton.setOnClickListener(v -> startNativeTransportTest());
        root.addView(nativeTestButton, fullWidthButton());

        Button shareButton = new Button(this);
        shareButton.setText("PARTAGER / EXPORTER LE RAPPORT");
        shareButton.setOnClickListener(v -> shareReport());
        root.addView(shareButton, fullWidthButton());

        Button clearButton = new Button(this);
        clearButton.setText("EFFACER LE RAPPORT");
        clearButton.setOnClickListener(v -> {
            report.setLength(0);
            append("Rapport effacé. Choisis le mode, branche le caméscope et relance la détection.\n");
            renderReport();
        });
        root.addView(clearButton, fullWidthButton());

        TextView note = new TextView(this);
        note.setText("L'aperçu décode DV ou MJPEG avec FFmpeg sur un thread séparé du transport USB. L'audio et sa synchronisation seront ajoutés après validation de l'image en direct.");
        note.setTextColor(0xFF9AA8B7);
        note.setTextSize(12);
        note.setPadding(0, dp(8), 0, dp(10));
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
        String compatibility = selectedDescriptorResult == null
                ? " · format UVC non analysé"
                : (selectedMode.isAdvertisedBy(selectedDescriptorResult)
                    ? " · format UVC compatible"
                    : " · format UVC demandé non annoncé");
        statusView.setText("USB Host prêt · " + devices.size()
                + " appareil(s) USB · moteur demandé : " + selectedMode.label
                + compatibility);
    }

    private boolean isUsbCandidate(UsbDevice device) {
        // Ignore arbitrary UVC cameras: this build targets the Panasonic device,
        // but deliberately does not lock either software engine to a product ID.
        return device.getVendorId() == PANASONIC_VENDOR_ID;
    }

    private boolean isSelectedFormatAvailable() {
        return selectedMode.isAdvertisedBy(selectedDescriptorResult);
    }

    private void appendModeCompatibility(UsbDevice device) {
        append("\n--- COMPATIBILITÉ DU MOTEUR SÉLECTIONNÉ ---\n");
        append("Moteur demandé : " + selectedMode.label + "\n");
        append("Descripteur requis : " + selectedMode.descriptorName() + "\n");
        append(String.format(Locale.US,
                "VID:PID connecté : %04X:%04X (informatif uniquement ; non utilisé pour sélectionner le moteur)\n",
                device.getVendorId(), device.getProductId()));
        if (selectedDescriptorResult == null) {
            append("Compatibilité : impossible à déterminer (descripteurs bruts indisponibles).\n");
            return;
        }

        append("Formats UVC annoncés : "
                + (selectedDescriptorResult.hasMjpegFormat ? "MJPEG " : "")
                + (selectedDescriptorResult.hasDvFormat ? "DV" : "")
                + ((!selectedDescriptorResult.hasMjpegFormat
                && !selectedDescriptorResult.hasDvFormat) ? "aucun format demandé" : "")
                + "\n");
        append("Compatibilité du moteur : "
                + (isSelectedFormatAvailable() ? "OUI" : "NON — descripteur requis absent")
                + "\n");
    }

    private void scanConnectedDevices() {
        report.setLength(0);
        permissionQueue.clear();
        waitingForUsbPermission = false;
        pendingCameraDevice = null;
        selectedDevice = null;
        selectedDescriptorResult = null;

        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.FRANCE).format(new Date());
        append("PANASONIC MONITOR — RAPPORT ÉTAPE 2\n");
        append("Date du test : " + timestamp + "\n");
        append("Téléphone : " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n");
        append("Moteur sélectionné : " + selectedMode.label + "\n");
        append("Descripteur UVC recherché : " + selectedMode.descriptorName() + "\n");
        append("Le VID/PID connecté sera rapporté, mais ne verrouille pas le moteur.\n\n");

        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        append("Appareils détectés : " + devices.size() + "\n");

        if (devices.isEmpty()) {
            append("Aucun périphérique USB visible. Vérifie OTG, câble de données, alimentation et mode USB du caméscope.\n");
            renderReport();
            refreshStatus();
            updateNativeButton();
            return;
        }

        for (UsbDevice device : devices.values()) {
            append("\n==================================================\n");
            append(describeDevice(device));

            if (isUsbCandidate(device)) {
                append("Périphérique Panasonic détecté. PID conservé comme information de diagnostic.\n");
                if (usbManager.hasPermission(device)) {
                    append("Autorisation USB déjà accordée.\n");
                    selectedDevice = device;
                    selectedDescriptorResult = inspectRawDescriptors(device);
                    appendModeCompatibility(device);
                } else {
                    permissionQueue.add(device);
                }
            }
        }

        if (selectedDevice == null && permissionQueue.isEmpty()) {
            append("\nAucun périphérique Panasonic détecté.\n");
        }

        renderReport();
        refreshStatus();
        updateNativeButton();
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

    private UsbDescriptorParser.ParseResult inspectRawDescriptors(UsbDevice device) {
        UsbDeviceConnection connection = null;
        UsbDescriptorParser.ParseResult parsed = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                append("Impossible d'ouvrir l'appareil pour lire ses descripteurs bruts.\n");
                return null;
            }

            byte[] raw = connection.getRawDescriptors();
            append("\n--- DESCRIPTEURS USB BRUTS ---\n");
            if (raw == null || raw.length == 0) {
                append("Android n'a retourné aucun descripteur brut.\n");
                return null;
            }

            append("Taille brute : " + raw.length + " octets\n");
            parsed = UsbDescriptorParser.parse(raw);
            append(parsed.toReport());

            if (parsed.hasDvFormat) {
                append("\nLe périphérique annonce VS_FORMAT_DV.\n");
            }
            if (parsed.hasMjpegFormat) {
                append("Le périphérique annonce VS_FORMAT_MJPEG.\n");
            }
            if (parsed.hasIsochronousVideoEndpoint) {
                append("Endpoint vidéo isochrone IN détecté.\n");
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
        return parsed;
    }

    private void requestNextUsbPermission() {
        if (waitingForUsbPermission || pendingCameraDevice != null || permissionQueue.isEmpty()) {
            renderReport();
            updateNativeButton();
            return;
        }

        UsbDevice device = permissionQueue.remove(0);
        if (isVideoDevice(device)
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCameraDevice = device;
            append("\nAndroid demande l'autorisation « Caméra » pour l'accès USB vidéo. Panasonic Monitor n'ouvre pas la caméra du téléphone.\n");
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
            selectedDevice = device;
            selectedDescriptorResult = inspectRawDescriptors(device);
            appendModeCompatibility(device);
            renderReport();
            updateNativeButton();
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
            append("\nAutorisation « Caméra » refusée. Android ne permet pas à l'application de demander l'accès à ce périphérique vidéo.\n");
            renderReport();
            requestNextUsbPermission();
        }
    }

    private void startNativeTransportTest() {
        if (!nativeAvailable) {
            append("\nBibliothèque native absente. Réinstalle une APK construite avec le workflow étape 2.\n");
            renderReport();
            return;
        }
        if (selectedDevice == null || !isSelectedFormatAvailable()) {
            Toast.makeText(this,
                    "Le périphérique est détecté, mais le format UVC requis n'est pas annoncé. Vérifie le mode du caméscope et relance l'analyse.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!usbManager.hasPermission(selectedDevice)) {
            Toast.makeText(this, "Autorisation USB manquante. Relance la détection.", Toast.LENGTH_LONG).show();
            return;
        }
        if (testRunning || previewRunning) {
            return;
        }

        testRunning = true;
        updateNativeButton();
        append("\n--- TEST NATIF UVC / ISOCHRONE ---\n");
        append("Mode : " + selectedMode.label + " · échantillonnage demandé : 5 secondes\n");
        if (selectedMode == PanasonicMode.MOTION_DV) {
            append("Conseil : place le caméscope en mode VCR/lecture avec une cassette en lecture pour vérifier la sortie DV.\n");
        } else {
            append("Mode Webcam : place le caméscope dans le mode caméra/webcam.\n");
        }
        renderReport();

        final UsbDevice device = selectedDevice;
        final int nativeMode = selectedMode.nativeValue;

        new Thread(() -> {
            String result;
            UsbDeviceConnection connection = null;
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) {
                    result = "UsbManager.openDevice() a retourné null. Vérifie les permissions et les contrôles de confidentialité Android.";
                } else {
                    int fd = connection.getFileDescriptor();
                    if (fd < 0) {
                        result = "Descripteur de fichier USB invalide : " + fd;
                    } else {
                        result = nativeProbeAndSample(fd, nativeMode, 5000);
                    }
                }
            } catch (Throwable error) {
                result = "Exception pendant le test natif : " + error.getClass().getSimpleName()
                        + " — " + safeMessage(error);
            } finally {
                if (connection != null) {
                    connection.close();
                }
            }

            final String finalResult = result;
            runOnUiThread(() -> {
                append(finalResult + "\n");
                testRunning = false;
                renderReport();
                updateNativeButton();
                updatePreviewButtons();
            });
        }, "Panasonic-UVC-Probe").start();
    }

    private void updateNativeButton() {
        if (nativeTestButton == null) {
            return;
        }
        boolean ready = nativeAvailable
                && selectedDevice != null
                && isSelectedFormatAvailable()
                && usbManager.hasPermission(selectedDevice)
                && !testRunning
                && !previewRunning;
        nativeTestButton.setEnabled(ready);
        if (!nativeAvailable) {
            nativeTestButton.setText("TRANSPORT NATIF INDISPONIBLE DANS CETTE APK");
        } else if (testRunning) {
            nativeTestButton.setText("TEST NATIF EN COURS…");
        } else if (selectedDevice != null && !isSelectedFormatAvailable()) {
            nativeTestButton.setText("FORMAT UVC REQUIS NON ANNONCÉ");
        } else if (!ready) {
            nativeTestButton.setText("DÉTECTER LE CAMÉSCOPE AVANT LE TEST NATIF");
        } else {
            nativeTestButton.setText("TESTER LE TRANSPORT NATIF (5 SECONDES)");
        }
    }

    private void startLivePreview() {
        if (!nativeAvailable) {
            Toast.makeText(this, "Décodeur natif indisponible dans cette APK.", Toast.LENGTH_LONG).show();
            return;
        }
        if (previewRunning || testRunning) return;
        if (!previewSurfaceReady || previewSurfaceView == null
                || previewSurfaceView.getHolder().getSurface() == null
                || !previewSurfaceView.getHolder().getSurface().isValid()) {
            Toast.makeText(this, "Surface d'aperçu non prête. Réessaie dans un instant.", Toast.LENGTH_LONG).show();
            return;
        }
        if (selectedDevice == null || !isSelectedFormatAvailable()) {
            Toast.makeText(this, "Détecte le caméscope et vérifie le format UVC demandé.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!usbManager.hasPermission(selectedDevice)) {
            Toast.makeText(this, "Autorisation USB manquante. Relance la détection.", Toast.LENGTH_LONG).show();
            return;
        }

        final UsbDevice device = selectedDevice;
        final int nativeMode = selectedMode.nativeValue;
        final Surface surface = previewSurfaceView.getHolder().getSurface();
        previewRunning = true;
        nativePreparePreview();
        append("\n--- APERÇU VIDÉO EN DIRECT ---\n");
        append("Moteur : " + selectedMode.label + " · arrêt manuel.\n");
        if (selectedMode == PanasonicMode.MOTION_DV) {
            append("VCR/lecture et cassette en lecture requis. DV PAL attendu : 144 000 octets par trame.\n");
        } else {
            append("Mode Webcam MJPEG : EBUSY a déjà bloqué la réservation de l'interface ; ce test permettra de confirmer si elle est libérée.\n");
        }
        renderReport();
        updateNativeButton();
        updatePreviewButtons();

        new Thread(() -> {
            String result;
            UsbDeviceConnection connection = null;
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) {
                    result = "UsbManager.openDevice() a retourné null. Vérifie l'autorisation USB Android.";
                } else {
                    int fd = connection.getFileDescriptor();
                    if (fd < 0) {
                        result = "Descripteur USB Android invalide : " + fd;
                    } else {
                        result = nativeStartPreview(fd, nativeMode, surface);
                    }
                }
            } catch (Throwable error) {
                result = "Exception pendant l'aperçu : " + error.getClass().getSimpleName()
                        + " — " + safeMessage(error);
            } finally {
                if (connection != null) connection.close();
            }

            final String finalResult = result;
            runOnUiThread(() -> {
                append(finalResult + "\n");
                previewRunning = false;
                renderReport();
                updateNativeButton();
                updatePreviewButtons();
            });
        }, "Panasonic-UVC-LivePreview").start();
    }

    private void stopLivePreview() {
        if (!previewRunning) return;
        if (nativeAvailable) nativeStopPreview();
        append("\nArrêt de l'aperçu demandé ; les transferts en cours seront annulés avant la libération de l'interface.\n");
        renderReport();
        updatePreviewButtons();
    }

    private void updatePreviewButtons() {
        if (previewStartButton == null || previewStopButton == null) return;
        boolean canStart = nativeAvailable
                && previewSurfaceReady
                && selectedDevice != null
                && isSelectedFormatAvailable()
                && usbManager != null
                && usbManager.hasPermission(selectedDevice)
                && !previewRunning
                && !testRunning;
        previewStartButton.setEnabled(canStart);
        previewStopButton.setEnabled(previewRunning);
        if (previewRunning) {
            previewStartButton.setText("APERÇU EN COURS…");
        } else if (!previewSurfaceReady) {
            previewStartButton.setText("SURFACE VIDÉO EN ATTENTE…");
        } else if (selectedDevice == null) {
            previewStartButton.setText("DÉTECTER LE CAMÉSCOPE AVANT L'APERÇU");
        } else {
            previewStartButton.setText("DÉMARRER L'APERÇU EN DIRECT");
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

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
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
        send.putExtra(Intent.EXTRA_SUBJECT, "Panasonic Monitor — rapport USB");
        send.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(send, "Partager le rapport USB"));
    }

    @Override
    protected void onPause() {
        stopLivePreview();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLivePreview();
        super.onDestroy();
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }
}
