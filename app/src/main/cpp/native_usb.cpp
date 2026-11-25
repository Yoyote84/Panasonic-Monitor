#include <jni.h>
#include <libusb.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <new>
#include <sys/time.h>
#include <vector>
#include <unistd.h>

namespace {

constexpr uint16_t kPanasonicVid = 0x04DA;
constexpr uint16_t kWebcamPid = 0x231A;
constexpr uint16_t kMotionDvPid = 0x2318;

constexpr uint8_t kUsbClassVideo = 0x0E;
constexpr uint8_t kUsbSubclassVideoStreaming = 0x02;
constexpr uint8_t kCsInterface = 0x24;
constexpr uint8_t kVsFormatMjpeg = 0x06;
constexpr uint8_t kVsFrameMjpeg = 0x07;
constexpr uint8_t kVsFormatDv = 0x0C;

constexpr uint8_t kClassInterfaceIn = 0xA1;
constexpr uint8_t kClassInterfaceOut = 0x21;
constexpr uint8_t kGetCur = 0x81;
constexpr uint8_t kSetCur = 0x01;
constexpr uint8_t kGetLen = 0x85;
constexpr uint8_t kVsProbeControl = 0x01;
constexpr uint8_t kVsCommitControl = 0x02;

constexpr int kTransferCount = 32;
constexpr int kPacketsPerTransfer = 128;
constexpr int kSampleDurationMsDefault = 5000;

uint16_t readLe16(const unsigned char* p) {
    return static_cast<uint16_t>(p[0]) |
           (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readLe32(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

std::string errorName(int code) {
    switch (code) {
        case LIBUSB_SUCCESS: return "LIBUSB_SUCCESS";
        case LIBUSB_ERROR_IO: return "LIBUSB_ERROR_IO";
        case LIBUSB_ERROR_INVALID_PARAM: return "LIBUSB_ERROR_INVALID_PARAM";
        case LIBUSB_ERROR_ACCESS: return "LIBUSB_ERROR_ACCESS";
        case LIBUSB_ERROR_NO_DEVICE: return "LIBUSB_ERROR_NO_DEVICE";
        case LIBUSB_ERROR_NOT_FOUND: return "LIBUSB_ERROR_NOT_FOUND";
        case LIBUSB_ERROR_BUSY: return "LIBUSB_ERROR_BUSY";
        case LIBUSB_ERROR_TIMEOUT: return "LIBUSB_ERROR_TIMEOUT";
        case LIBUSB_ERROR_OVERFLOW: return "LIBUSB_ERROR_OVERFLOW";
        case LIBUSB_ERROR_PIPE: return "LIBUSB_ERROR_PIPE";
        case LIBUSB_ERROR_INTERRUPTED: return "LIBUSB_ERROR_INTERRUPTED";
        case LIBUSB_ERROR_NO_MEM: return "LIBUSB_ERROR_NO_MEM";
        case LIBUSB_ERROR_NOT_SUPPORTED: return "LIBUSB_ERROR_NOT_SUPPORTED";
        default: return "LIBUSB_ERROR_UNKNOWN";
    }
}

std::string describeError(const char* operation, int code) {
    std::ostringstream out;
    out << operation << " : " << code << " (" << errorName(code)
        << ", " << libusb_error_name(code) << ")";
    return out.str();
}

struct UvcFormat {
    bool found = false;
    bool dv = false;
    uint8_t formatIndex = 0;
    uint8_t frameIndex = 0;
    uint32_t frameInterval = 0;
    int interfaceNumber = -1;
};

bool parseUvcFormat(const libusb_interface_descriptor& alt,
                    bool wantDv,
                    UvcFormat& selected) {
    const unsigned char* cursor = alt.extra;
    int remaining = alt.extra_length;

    while (cursor != nullptr && remaining >= 2) {
        const int length = cursor[0];
        if (length < 2 || length > remaining) {
            break;
        }

        if (cursor[1] == kCsInterface && length >= 4) {
            const uint8_t subtype = cursor[2];
            if (wantDv && subtype == kVsFormatDv && length >= 4) {
                selected.found = true;
                selected.dv = true;
                selected.formatIndex = cursor[3];
                selected.frameIndex = 0;
                selected.interfaceNumber = alt.bInterfaceNumber;
                return true;
            }

            if (!wantDv && subtype == kVsFormatMjpeg && length >= 7) {
                selected.found = true;
                selected.dv = false;
                selected.formatIndex = cursor[3];
                selected.frameIndex = cursor[6];
                selected.interfaceNumber = alt.bInterfaceNumber;
                if (selected.frameIndex == 0) {
                    selected.frameIndex = 1;
                }

                // Locate the matching default frame descriptor, if present, to
                // preserve the camera's advertised frame interval in PROBE.
                const unsigned char* frameCursor = alt.extra;
                int frameRemaining = alt.extra_length;
                while (frameCursor != nullptr && frameRemaining >= 2) {
                    const int frameLength = frameCursor[0];
                    if (frameLength < 2 || frameLength > frameRemaining) {
                        break;
                    }
                    if (frameCursor[1] == kCsInterface &&
                        frameLength >= 26 &&
                        frameCursor[2] == kVsFrameMjpeg &&
                        frameCursor[3] == selected.frameIndex) {
                        selected.frameInterval = readLe32(frameCursor + 21);
                        break;
                    }
                    frameCursor += frameLength;
                    frameRemaining -= frameLength;
                }
                return true;
            }
        }

        cursor += length;
        remaining -= length;
    }
    return false;
}

bool findModeFormat(libusb_config_descriptor* config,
                    bool wantDv,
                    UvcFormat& format,
                    std::ostringstream& report) {
    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& interface = config->interface[i];
        for (int a = 0; a < interface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = interface.altsetting[a];
            if (alt.bInterfaceClass != kUsbClassVideo ||
                alt.bInterfaceSubClass != kUsbSubclassVideoStreaming) {
                continue;
            }
            if (parseUvcFormat(alt, wantDv, format)) {
                report << "Interface UVC VideoStreaming : "
                       << format.interfaceNumber << "\n";
                report << "Format " << (wantDv ? "DV" : "MJPEG")
                       << " index : " << static_cast<int>(format.formatIndex)
                       << " · frame index : " << static_cast<int>(format.frameIndex)
                       << "\n";
                if (format.frameInterval != 0) {
                    report << "Intervalle image annoncé : "
                           << format.frameInterval << " (100 ns)\n";
                }
                return true;
            }
        }
    }
    return false;
}

struct IsoEndpoint {
    bool found = false;
    uint8_t address = 0;
    int alternateSetting = 0;
    uint16_t rawPacketSize = 0;
    uint32_t packetCapacity = 0;
};

IsoEndpoint selectIsoEndpoint(libusb_config_descriptor* config,
                              int interfaceNumber,
                              uint32_t requestedPayload,
                              std::ostringstream& report) {
    IsoEndpoint best;
    uint32_t bestCapacity = 0;

    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& interface = config->interface[i];
        for (int a = 0; a < interface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = interface.altsetting[a];
            if (alt.bInterfaceNumber != interfaceNumber ||
                alt.bAlternateSetting == 0) {
                continue;
            }
            for (uint8_t e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[e];
                const bool isIn = (endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK)
                                  == LIBUSB_ENDPOINT_IN;
                const bool isIso = (endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK)
                                   == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
                if (!isIn || !isIso) {
                    continue;
                }

                const uint16_t raw = endpoint.wMaxPacketSize;
                const uint32_t packet = raw & 0x07FF;
                const uint32_t transactions = 1U + ((raw >> 11) & 0x03);
                const uint32_t capacity = packet * transactions;
                report << "Endpoint ISO candidat : 0x" << std::hex
                       << static_cast<int>(endpoint.bEndpointAddress) << std::dec
                       << " · interface " << interfaceNumber
                       << " alt " << static_cast<int>(alt.bAlternateSetting)
                       << " · capacité paquet " << capacity << " octets\n";

                if (capacity > bestCapacity) {
                    best.found = true;
                    best.address = endpoint.bEndpointAddress;
                    best.alternateSetting = alt.bAlternateSetting;
                    best.rawPacketSize = raw;
                    best.packetCapacity = capacity;
                    bestCapacity = capacity;
                }
                if (capacity >= requestedPayload) {
                    return best;
                }
            }
        }
    }
    return best;
}

struct IsoStats {
    bool stopping = false;
    int inFlight = 0;
    uint64_t completedPackets = 0;
    uint64_t packetsWithData = 0;
    uint64_t payloadBytes = 0;
    uint64_t rawBytes = 0;
    uint64_t packetErrors = 0;
    uint64_t uvcErrorFlags = 0;
    int submitErrors = 0;
};

struct TransferState {
    IsoStats* stats = nullptr;
    bool inFlight = false;
};

void LIBUSB_CALL isoCallback(libusb_transfer* transfer) {
    auto* state = static_cast<TransferState*>(transfer->user_data);
    IsoStats* stats = state->stats;

    state->inFlight = false;
    --stats->inFlight;

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[i];
            ++stats->completedPackets;
            if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
                ++stats->packetErrors;
                continue;
            }
            if (packet.actual_length == 0) {
                continue;
            }

            ++stats->packetsWithData;
            stats->rawBytes += packet.actual_length;
            const unsigned char* data =
                libusb_get_iso_packet_buffer_simple(transfer, static_cast<unsigned int>(i));
            const unsigned int actual = packet.actual_length;
            if (data != nullptr && actual >= 2) {
                const unsigned int headerLength = data[0];
                if (headerLength >= 2 && headerLength <= actual) {
                    if ((data[1] & 0x40) != 0) {
                        ++stats->uvcErrorFlags;
                    }
                    stats->payloadBytes += actual - headerLength;
                }
            }
        }
    } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
        ++stats->packetErrors;
    }

    if (!stats->stopping && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) {
        const int submitted = libusb_submit_transfer(transfer);
        if (submitted == LIBUSB_SUCCESS) {
            state->inFlight = true;
            ++stats->inFlight;
            return;
        }
        ++stats->submitErrors;
    }
}

bool runIsoSample(libusb_context* ctx,
                  libusb_device_handle* handle,
                  const IsoEndpoint& endpoint,
                  int durationMs,
                  IsoStats& stats,
                  std::ostringstream& report) {
    std::vector<libusb_transfer*> transfers;
    std::vector<TransferState> states(kTransferCount);
    transfers.reserve(kTransferCount);

    for (int i = 0; i < kTransferCount; ++i) {
        libusb_transfer* transfer = libusb_alloc_transfer(kPacketsPerTransfer);
        if (transfer == nullptr) {
            report << "Allocation d'un transfert isochrone impossible.\n";
            stats.stopping = true;
            break;
        }

        const int bufferLength =
            static_cast<int>(endpoint.packetCapacity) * kPacketsPerTransfer;
        unsigned char* buffer = new (std::nothrow) unsigned char[bufferLength];
        if (buffer == nullptr) {
            libusb_free_transfer(transfer);
            report << "Allocation du tampon isochrone impossible.\n";
            stats.stopping = true;
            break;
        }
        std::memset(buffer, 0, static_cast<size_t>(bufferLength));

        states[static_cast<size_t>(i)].stats = &stats;
        libusb_fill_iso_transfer(
            transfer,
            handle,
            endpoint.address,
            buffer,
            bufferLength,
            kPacketsPerTransfer,
            isoCallback,
            &states[static_cast<size_t>(i)],
            0
        );
        libusb_set_iso_packet_lengths(transfer, endpoint.packetCapacity);
        transfer->flags = LIBUSB_TRANSFER_FREE_BUFFER;
        transfers.push_back(transfer);
    }

    if (transfers.size() != static_cast<size_t>(kTransferCount)) {
        stats.stopping = true;
        for (libusb_transfer* transfer : transfers) {
            libusb_free_transfer(transfer);
        }
        return false;
    }

    for (size_t i = 0; i < transfers.size(); ++i) {
        const int result = libusb_submit_transfer(transfers[i]);
        if (result != LIBUSB_SUCCESS) {
            ++stats.submitErrors;
            report << describeError("libusb_submit_transfer", result) << "\n";
            break;
        }
        states[i].inFlight = true;
        ++stats.inFlight;
    }

    const auto endTime = std::chrono::steady_clock::now()
                       + std::chrono::milliseconds(durationMs);
    while (std::chrono::steady_clock::now() < endTime &&
           stats.inFlight > 0 &&
           stats.submitErrors == 0) {
        timeval timeout {};
        timeout.tv_sec = 0;
        timeout.tv_usec = 100000;
        const int result = libusb_handle_events_timeout_completed(ctx, &timeout, nullptr);
        if (result < 0 && result != LIBUSB_ERROR_INTERRUPTED) {
            report << describeError("libusb_handle_events", result) << "\n";
            stats.stopping = true;
            break;
        }
    }

    stats.stopping = true;
    for (size_t i = 0; i < transfers.size(); ++i) {
        if (states[i].inFlight) {
            const int result = libusb_cancel_transfer(transfers[i]);
            if (result != LIBUSB_SUCCESS &&
                result != LIBUSB_ERROR_NOT_FOUND &&
                result != LIBUSB_ERROR_NO_DEVICE) {
                report << describeError("libusb_cancel_transfer", result) << "\n";
            }
        }
    }

    while (stats.inFlight > 0) {
        timeval timeout {};
        timeout.tv_sec = 0;
        timeout.tv_usec = 100000;
        const int result = libusb_handle_events_timeout_completed(ctx, &timeout, nullptr);
        if (result < 0 && result != LIBUSB_ERROR_INTERRUPTED) {
            // Keep pumping until all submitted transfers have delivered their callbacks.
            usleep(10000);
        }
    }

    for (libusb_transfer* transfer : transfers) {
        libusb_free_transfer(transfer);
    }
    return stats.packetsWithData > 0;
}

std::string runProbe(int fd, int selectedMode, int durationMs) {
    std::ostringstream report;
    report << "PANASONIC MONITOR — TEST TRANSPORT NATIF\n";
    report << "Moteur demandé : " << (selectedMode == 1 ? "Monitor DV / Motion DV" : "Webcam") << "\n";
    report << "libusb : version Android native (wrap_sys_device)\n";

    if (fd < 0) {
        return report.str() + "Descripteur USB Android invalide.\n";
    }
    if (selectedMode != 0 && selectedMode != 1) {
        return report.str() + "Mode logiciel inconnu.\n";
    }

    const int ownedFd = dup(fd);
    if (ownedFd < 0) {
        return report.str() + "dup() du descripteur USB Android a échoué.\n";
    }

    int result = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (result != LIBUSB_SUCCESS) {
        close(ownedFd);
        return report.str() + describeError("libusb_set_option(NO_DEVICE_DISCOVERY)", result) + "\n";
    }

    libusb_context* ctx = nullptr;
    result = libusb_init(&ctx);
    if (result != LIBUSB_SUCCESS) {
        close(ownedFd);
        return report.str() + describeError("libusb_init", result) + "\n";
    }

    libusb_device_handle* handle = nullptr;
    result = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(ownedFd), &handle);
    if (result != LIBUSB_SUCCESS || handle == nullptr) {
        close(ownedFd);
        libusb_exit(ctx);
        return report.str() + describeError("libusb_wrap_sys_device", result) + "\n";
    }

    bool interfaceClaimed = false;
    libusb_config_descriptor* config = nullptr;
    libusb_device_descriptor deviceDescriptor {};
    const bool wantDv = selectedMode == 1;
    UvcFormat format;
    result = libusb_get_device_descriptor(libusb_get_device(handle), &deviceDescriptor);
    if (result != LIBUSB_SUCCESS) {
        report << describeError("libusb_get_device_descriptor", result) << "\n";
        goto cleanup;
    }

    report << "USB VID:PID : " << std::hex << std::uppercase
           << deviceDescriptor.idVendor << ":" << deviceDescriptor.idProduct
           << std::dec << "\n";

    if (deviceDescriptor.idVendor != kPanasonicVid ||
        deviceDescriptor.idProduct != (selectedMode == 1 ? kMotionDvPid : kWebcamPid)) {
        report << "Le périphérique ouvert ne correspond pas au mode sélectionné.\n";
        goto cleanup;
    }

    result = libusb_get_active_config_descriptor(libusb_get_device(handle), &config);
    if (result != LIBUSB_SUCCESS) {
        result = libusb_get_config_descriptor(libusb_get_device(handle), 0, &config);
    }
    if (result != LIBUSB_SUCCESS || config == nullptr) {
        report << describeError("Lecture configuration USB", result) << "\n";
        goto cleanup;
    }

    if (!findModeFormat(config, wantDv, format, report)) {
        report << "Aucun descripteur UVC " << (wantDv ? "VS_FORMAT_DV" : "VS_FORMAT_MJPEG")
               << " trouvé dans la configuration active.\n";
        report << "Le mode Webcam devra être vérifié avec son rapport de descripteurs complet.\n";
        goto cleanup;
    }

    result = libusb_claim_interface(handle, format.interfaceNumber);
    if (result != LIBUSB_SUCCESS) {
        report << describeError("libusb_claim_interface", result) << "\n";
        report << "Vérifie qu'aucune autre application USB n'utilise le caméscope.\n";
        goto cleanup;
    }
    interfaceClaimed = true;

    {
        unsigned char lengthBytes[2] = {0, 0};
        const uint16_t probeValue = static_cast<uint16_t>(kVsProbeControl) << 8;
        int controlLength = libusb_control_transfer(
            handle, kClassInterfaceIn, kGetLen, probeValue,
            static_cast<uint16_t>(format.interfaceNumber),
            lengthBytes, sizeof(lengthBytes), 1500);

        int probeLength = 26;
        if (controlLength == 2) {
            probeLength = readLe16(lengthBytes);
        } else {
            report << "GET_LEN UVC non accepté (" << controlLength
                   << "), essai avec le bloc UVC 1.0 de 26 octets.\n";
        }
        probeLength = std::max(26, std::min(probeLength, 48));

        std::vector<unsigned char> probe(static_cast<size_t>(probeLength), 0);
        probe[2] = format.formatIndex;
        probe[3] = format.frameIndex;
        if (format.frameInterval != 0) {
            probe[4] = static_cast<unsigned char>(format.frameInterval & 0xFF);
            probe[5] = static_cast<unsigned char>((format.frameInterval >> 8) & 0xFF);
            probe[6] = static_cast<unsigned char>((format.frameInterval >> 16) & 0xFF);
            probe[7] = static_cast<unsigned char>((format.frameInterval >> 24) & 0xFF);
        }

        result = libusb_control_transfer(
            handle, kClassInterfaceOut, kSetCur, probeValue,
            static_cast<uint16_t>(format.interfaceNumber),
            probe.data(), static_cast<uint16_t>(probe.size()), 1500);
        if (result < 0) {
            report << describeError("UVC SET_CUR(PROBE)", result) << "\n";
            goto cleanup;
        }

        result = libusb_control_transfer(
            handle, kClassInterfaceIn, kGetCur, probeValue,
            static_cast<uint16_t>(format.interfaceNumber),
            probe.data(), static_cast<uint16_t>(probe.size()), 1500);
        if (result < 0) {
            report << describeError("UVC GET_CUR(PROBE)", result) << "\n";
            goto cleanup;
        }
        if (result < 26) {
            report << "Réponse PROBE trop courte : " << result << " octets.\n";
            goto cleanup;
        }

        const uint32_t maxFrameSize = readLe32(probe.data() + 18);
        const uint32_t maxPayload = readLe32(probe.data() + 22);
        report << "UVC PROBE négocié : longueur=" << result
               << " · format=" << static_cast<int>(probe[2])
               << " · frame=" << static_cast<int>(probe[3])
               << " · maxFrame=" << maxFrameSize
               << " · maxPayload=" << maxPayload << " octets\n";

        const uint16_t commitValue = static_cast<uint16_t>(kVsCommitControl) << 8;
        result = libusb_control_transfer(
            handle, kClassInterfaceOut, kSetCur, commitValue,
            static_cast<uint16_t>(format.interfaceNumber),
            probe.data(), static_cast<uint16_t>(probe.size()), 1500);
        if (result < 0) {
            report << describeError("UVC SET_CUR(COMMIT)", result) << "\n";
            goto cleanup;
        }
        report << "UVC COMMIT : accepté (" << result << " octets).\n";

        const IsoEndpoint endpoint =
            selectIsoEndpoint(config, format.interfaceNumber, maxPayload, report);
        if (!endpoint.found) {
            report << "Aucun endpoint isochrone IN trouvé pour le flux vidéo.\n";
            goto cleanup;
        }

        result = libusb_set_interface_alt_setting(
            handle, format.interfaceNumber, endpoint.alternateSetting);
        if (result != LIBUSB_SUCCESS) {
            report << describeError("libusb_set_interface_alt_setting", result) << "\n";
            goto cleanup;
        }

        report << "Alt-setting sélectionné : " << endpoint.alternateSetting
               << " · endpoint IN 0x" << std::hex
               << static_cast<int>(endpoint.address) << std::dec
               << " · paquet " << endpoint.packetCapacity << " octets\n";
        report << "Échantillonnage isochrone : " << durationMs << " ms"
               << " · " << kTransferCount << " transferts × "
               << kPacketsPerTransfer << " paquets\n";

        IsoStats stats;
        const bool received = runIsoSample(ctx, handle, endpoint, durationMs, stats, report);
        report << "Paquets complétés : " << stats.completedPackets << "\n";
        report << "Paquets avec données : " << stats.packetsWithData << "\n";
        report << "Octets USB reçus : " << stats.rawBytes << "\n";
        report << "Octets de payload UVC : " << stats.payloadBytes << "\n";
        report << "Flags d'erreur UVC : " << stats.uvcErrorFlags << "\n";
        report << "Erreurs de paquets / soumission : "
               << stats.packetErrors << " / " << stats.submitErrors << "\n";

        if (received) {
            report << "RÉSULTAT : le transport isochrone a livré des données au téléphone.\n";
            report << "Cela valide le transport, pas encore le décodage ou la synchronisation A/V.\n";
        } else {
            report << "RÉSULTAT : aucun payload vidéo reçu pendant l'échantillonnage.\n";
            if (wantDv) {
                report << "Vérifie le mode Motion DV, le mode VCR/lecture et la lecture effective d'une cassette.\n";
            } else {
                report << "Vérifie le mode Webcam et refais un test après reconnexion USB.\n";
            }
        }
    }

cleanup:
    if (config != nullptr) {
        libusb_free_config_descriptor(config);
    }
    if (interfaceClaimed) {
        libusb_release_interface(handle, format.interfaceNumber);
    }
    libusb_close(handle);
    libusb_exit(ctx);
    return report.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_fr_panasonic_monitor_MainActivity_nativeProbeAndSample(
        JNIEnv* env, jobject /* thiz */, jint usbFileDescriptor,
        jint mode, jint durationMs) {
    const int safeDuration = std::max(1000, std::min(
            static_cast<int>(durationMs), kSampleDurationMsDefault));
    const std::string result = runProbe(
            static_cast<int>(usbFileDescriptor),
            static_cast<int>(mode),
            safeDuration);
    return env->NewStringUTF(result.c_str());
}
