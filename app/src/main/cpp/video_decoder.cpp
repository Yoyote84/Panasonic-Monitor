#include "video_decoder.h"
#include "uvc_frame_assembler.h"

#include <android/native_window.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <exception>
#include <limits>
#include <sstream>
#include <thread>
#include <vector>

namespace {

constexpr size_t kPreviewRingBytes = 8U * 1024U * 1024U;
constexpr size_t kWorkerChunkBytes = 32U * 1024U;
constexpr size_t kMaximumMjpegFrameBytes = 8U * 1024U * 1024U;
constexpr uint8_t kUvcPts = 0x04;
constexpr uint8_t kUvcScr = 0x08;
constexpr uint8_t kUvcErr = 0x40;

/** Single-producer/single-consumer byte ring. The libusb callback never waits. */
class SpscByteRing {
public:
    explicit SpscByteRing(size_t capacity) : bytes_(capacity, 0) {}

    bool write(const uint8_t* source, size_t length) {
        if (source == nullptr || length == 0 || length > bytes_.size()) {
            return length == 0;
        }

        const uint64_t writePosition = writePosition_.load(std::memory_order_relaxed);
        const uint64_t readPosition = readPosition_.load(std::memory_order_acquire);
        const uint64_t used = writePosition - readPosition;
        if (length > bytes_.size() - static_cast<size_t>(used)) {
            return false;
        }

        const size_t offset = static_cast<size_t>(writePosition % bytes_.size());
        const size_t first = std::min(length, bytes_.size() - offset);
        std::memcpy(bytes_.data() + offset, source, first);
        if (length > first) {
            std::memcpy(bytes_.data(), source + first, length - first);
        }
        writePosition_.store(writePosition + length, std::memory_order_release);
        return true;
    }

    size_t read(uint8_t* destination, size_t maximum) {
        if (destination == nullptr || maximum == 0) return 0;

        const uint64_t readPosition = readPosition_.load(std::memory_order_relaxed);
        const uint64_t writePosition = writePosition_.load(std::memory_order_acquire);
        const uint64_t available = writePosition - readPosition;
        const size_t length = static_cast<size_t>(
                std::min<uint64_t>(available, maximum));
        if (length == 0) return 0;

        const size_t offset = static_cast<size_t>(readPosition % bytes_.size());
        const size_t first = std::min(length, bytes_.size() - offset);
        std::memcpy(destination, bytes_.data() + offset, first);
        if (length > first) {
            std::memcpy(destination + first, bytes_.data(), length - first);
        }
        readPosition_.store(readPosition + length, std::memory_order_release);
        return length;
    }

    uint64_t available() const {
        const uint64_t writePosition = writePosition_.load(std::memory_order_acquire);
        const uint64_t readPosition = readPosition_.load(std::memory_order_acquire);
        return writePosition - readPosition;
    }

private:
    std::vector<uint8_t> bytes_;
    alignas(64) std::atomic<uint64_t> readPosition_{0};
    alignas(64) std::atomic<uint64_t> writePosition_{0};
};

std::string ffmpegError(int error) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(error, buffer, sizeof(buffer));
    return std::string(buffer);
}

}  // namespace

struct PreviewDecoder::Impl {
    Impl(bool useDv, uint32_t frameSize, ANativeWindow* nativeWindow)
        : dvMode(useDv),
          negotiatedFrameSize(frameSize),
          window(nativeWindow),
          ring(kPreviewRingBytes) {
        if (window != nullptr) {
            ANativeWindow_acquire(window);
        }
    }

    ~Impl() {
        stop();
        if (sws != nullptr) {
            sws_freeContext(sws);
            sws = nullptr;
        }
        if (decodedFrame != nullptr) {
            av_frame_free(&decodedFrame);
        }
        if (packet != nullptr) {
            av_packet_free(&packet);
        }
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        if (window != nullptr) {
            ANativeWindow_release(window);
            window = nullptr;
        }
    }

    bool start(std::string& error) {
        if (window == nullptr) {
            error = "Surface Android invalide.";
            return false;
        }
        if (dvMode && negotiatedFrameSize != 144000U
                && negotiatedFrameSize != 120000U) {
            std::ostringstream out;
            out << "Taille de trame DV inattendue : " << negotiatedFrameSize
                << " octets (PAL=144000, NTSC=120000).";
            error = out.str();
            return false;
        }

        const AVCodecID codecId = dvMode ? AV_CODEC_ID_DVVIDEO : AV_CODEC_ID_MJPEG;
        const AVCodec* codec = avcodec_find_decoder(codecId);
        if (codec == nullptr) {
            error = dvMode ? "Décodeur FFmpeg DV indisponible."
                           : "Décodeur FFmpeg MJPEG indisponible.";
            return false;
        }

        codecContext = avcodec_alloc_context3(codec);
        if (codecContext == nullptr) {
            error = "Allocation du contexte FFmpeg impossible.";
            return false;
        }
        codecContext->thread_count = 2;
        codecContext->thread_type = FF_THREAD_SLICE;

        int result = avcodec_open2(codecContext, codec, nullptr);
        if (result < 0) {
            error = "Ouverture du décodeur FFmpeg impossible : " + ffmpegError(result);
            return false;
        }

        packet = av_packet_alloc();
        decodedFrame = av_frame_alloc();
        if (packet == nullptr || decodedFrame == nullptr) {
            error = "Allocation des buffers FFmpeg impossible.";
            return false;
        }

        if (dvMode) {
            if (negotiatedFrameSize == 0) {
                error = "Le périphérique n'a pas négocié de taille de trame DV.";
                return false;
            }
            dvAssembler = panasonic::UvcFrameAssembler(
                    negotiatedFrameSize,
                    panasonic::UvcFrameAssembler::Policy::ExactSize);
        }
        stopping.store(false, std::memory_order_release);
        running.store(true, std::memory_order_release);
        try {
            worker = std::thread(&Impl::workerMain, this);
        } catch (const std::exception& exception) {
            running.store(false, std::memory_order_release);
            error = std::string("Démarrage du thread de décodage impossible : ")
                    + exception.what();
            return false;
        }
        return true;
    }

    bool pushUvcPacket(const uint8_t* data, size_t length) {
        if (!running.load(std::memory_order_acquire)
                || stopping.load(std::memory_order_acquire)
                || data == nullptr || length < 2) {
            return false;
        }

        const size_t headerLength = data[0];
        const uint8_t flags = data[1];
        const size_t minimumHeaderLength = 2U
                + ((flags & kUvcPts) != 0 ? 4U : 0U)
                + ((flags & kUvcScr) != 0 ? 6U : 0U);
        if (headerLength < minimumHeaderLength || headerLength > length) {
            malformedHeaders.fetch_add(1, std::memory_order_relaxed);
            return true;
        }
        if ((flags & kUvcErr) != 0) {
            // Panasonic marks some valid DV payload during tape-tract lock.
            // Keep the bytes; the DV decoder decides whether a frame is usable.
            uvcErrorFlags.fetch_add(1, std::memory_order_relaxed);
        }

        const uint8_t* payload = data + headerLength;
        const size_t payloadLength = length - headerLength;

        // Preserve header-only UVC payloads too: they can carry EOF/FID even
        // when no video bytes are present. Assembly runs on the worker thread.
        constexpr size_t kRecordHeader = 3;
        if (payloadLength > std::numeric_limits<uint16_t>::max()) {
            queueDrops.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        std::array<uint8_t, 16384> record{};
        if (payloadLength + kRecordHeader > record.size()) {
            queueDrops.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        record[0] = flags;
        record[1] = static_cast<uint8_t>(payloadLength & 0xFF);
        record[2] = static_cast<uint8_t>((payloadLength >> 8) & 0xFF);
        if (payloadLength > 0) {
            std::memcpy(record.data() + kRecordHeader, payload, payloadLength);
        }
        const bool written = ring.write(record.data(), payloadLength + kRecordHeader);

        if (!written) {
            queueDrops.fetch_add(1, std::memory_order_relaxed);
            resyncRequested.store(true, std::memory_order_release);
        } else {
            payloadBytes.fetch_add(payloadLength, std::memory_order_relaxed);
        }
        return written;
    }

    void stop() {
        stopping.store(true, std::memory_order_release);
        if (worker.joinable()) {
            worker.join();
        }
        running.store(false, std::memory_order_release);
    }

    std::string summary() const {
        std::ostringstream out;
        out << "Décodage FFmpeg : "
            << (dvMode ? "DV" : "MJPEG") << "\n";
        out << "Payload confié au décodeur : "
            << payloadBytes.load(std::memory_order_relaxed) << " octets\n";
        out << "Images décodées : "
            << decodedFrames.load(std::memory_order_relaxed) << "\n";
        out << "Images affichées : "
            << renderedFrames.load(std::memory_order_relaxed) << "\n";
        out << "Paquets UVC marqués ERR conservés : "
            << uvcErrorFlags.load(std::memory_order_relaxed) << "\n";
        out << "En-têtes UVC invalides : "
            << malformedHeaders.load(std::memory_order_relaxed) << "\n";
        out << "Paquets écartés (tampon plein) : "
            << queueDrops.load(std::memory_order_relaxed) << "\n";
        out << "Trames rejetées (taille / assemblage) : "
            << assemblyDrops.load(std::memory_order_relaxed) << "\n";
        out << "Erreurs de décodage / affichage : "
            << decodeErrors.load(std::memory_order_relaxed) << " / "
            << renderErrors.load(std::memory_order_relaxed) << "\n";
        const auto frameStats = dvMode
                ? dvAssembler.statistics() : jpegAssembler.statistics();
        out << "Trames complètes reconstruites : " << frameStats.completedFrames << "\n";
        out << "Trames incomplètes rejetées : " << frameStats.incompleteFrames << "\n";
        out << "Trames trop volumineuses rejetées : " << frameStats.oversizedFrames << "\n";
        out << "Payloads ignorés après EOF (même FID) : " << frameStats.payloadsAfterEof << "\n";
        out << "Resynchronisations après perte de tampon : " << frameStats.resyncDrops << "\n";
        if (!lastError.empty()) {
            out << "Dernier détail : " << lastError << "\n";
        }
        return out.str();
    }

private:
    void workerMain() {
        std::array<uint8_t, kWorkerChunkBytes> chunk{};
        std::vector<uint8_t> uvcRecords;
        uvcRecords.reserve(64U * 1024U);
        const auto onFrame = [this](const std::vector<uint8_t>& frame) {
            decodeFrame(frame.data(), frame.size());
        };

        while (!stopping.load(std::memory_order_acquire) || ring.available() > 0) {
            const size_t count = ring.read(chunk.data(), chunk.size());
            if (count == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }

            if (resyncRequested.exchange(false, std::memory_order_acq_rel)) {
                resetAssemblers();
                uvcRecords.clear();
            }

            uvcRecords.insert(uvcRecords.end(), chunk.begin(),
                              chunk.begin() + static_cast<std::ptrdiff_t>(count));
            if (dvMode) {
                consumeDvRecords(uvcRecords);
            } else {
                consumeMjpegRecords(uvcRecords);
            }
        }

        // Keep a complete last DV frame even without EOF; MJPEG needs EOI.
        if (dvMode) dvAssembler.flushAtStop(onFrame);
        else jpegAssembler.flushAtStop(onFrame, true);
    }

    void resetAssemblers() {
        dvAssembler.resync();
        jpegAssembler.resync();
    }

    void consumeDvRecords(std::vector<uint8_t>& records) {
        constexpr size_t kRecordHeader = 3;
        size_t consumed = 0;
        while (records.size() - consumed >= kRecordHeader) {
            const uint8_t flags = records[consumed];
            const size_t payloadLength =
                    static_cast<size_t>(records[consumed + 1])
                    | (static_cast<size_t>(records[consumed + 2]) << 8);
            if (records.size() - consumed < kRecordHeader + payloadLength) {
                break;
            }
            pushDvPayload(flags, records.data() + consumed + kRecordHeader,
                          payloadLength);
            consumed += kRecordHeader + payloadLength;
        }

        if (consumed > 0) {
            records.erase(records.begin(),
                          records.begin() + static_cast<std::ptrdiff_t>(consumed));
        }
        if (records.size() > 2U * 1024U * 1024U) {
            records.clear();
            dvAssembler.resync();
            assemblyDrops.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void pushDvPayload(uint8_t flags, const uint8_t* payload, size_t length) {
        dvAssembler.push(flags, payload, length,
                [this](const std::vector<uint8_t>& frame) {
                    decodeFrame(frame.data(), frame.size());
                });
    }

    void consumeMjpegRecords(std::vector<uint8_t>& records) {
        constexpr size_t kRecordHeader = 3;
        size_t consumed = 0;
        while (records.size() - consumed >= kRecordHeader) {
            const uint8_t flags = records[consumed];
            const size_t payloadLength =
                    static_cast<size_t>(records[consumed + 1])
                    | (static_cast<size_t>(records[consumed + 2]) << 8);
            if (records.size() - consumed < kRecordHeader + payloadLength) {
                break;
            }
            pushMjpegPayload(flags, records.data() + consumed + kRecordHeader,
                             payloadLength);
            consumed += kRecordHeader + payloadLength;
        }

        if (consumed > 0) {
            records.erase(records.begin(),
                          records.begin() + static_cast<std::ptrdiff_t>(consumed));
        }
        if (records.size() > 2U * 1024U * 1024U) {
            records.clear();
            jpegAssembler.resync();
            assemblyDrops.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void pushMjpegPayload(uint8_t flags, const uint8_t* payload, size_t length) {
        jpegAssembler.push(flags, payload, length,
                [this](const std::vector<uint8_t>& frame) {
                    decodeFrame(frame.data(), frame.size());
                });
    }

    void decodeFrame(const uint8_t* bytes, size_t length) {
        if (bytes == nullptr || length == 0
                || length > static_cast<size_t>(std::numeric_limits<int>::max())) {
            assemblyDrops.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        av_packet_unref(packet);
        int result = av_new_packet(packet, static_cast<int>(length));
        if (result < 0) {
            decodeErrors.fetch_add(1, std::memory_order_relaxed);
            lastError = "Allocation paquet FFmpeg : " + ffmpegError(result);
            return;
        }
        std::memcpy(packet->data, bytes, length);

        result = avcodec_send_packet(codecContext, packet);
        if (result == AVERROR(EAGAIN)) {
            drainDecoder();
            result = avcodec_send_packet(codecContext, packet);
        }
        if (result < 0) {
            decodeErrors.fetch_add(1, std::memory_order_relaxed);
            lastError = "avcodec_send_packet : " + ffmpegError(result);
            av_packet_unref(packet);
            return;
        }
        av_packet_unref(packet);
        drainDecoder();
    }

    void drainDecoder() {
        for (;;) {
            const int result = avcodec_receive_frame(codecContext, decodedFrame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                return;
            }
            if (result < 0) {
                decodeErrors.fetch_add(1, std::memory_order_relaxed);
                lastError = "avcodec_receive_frame : " + ffmpegError(result);
                return;
            }

            decodedFrames.fetch_add(1, std::memory_order_relaxed);
            renderFrame(decodedFrame);
            av_frame_unref(decodedFrame);
        }
    }

    void renderFrame(const AVFrame* source) {
        if (source == nullptr || source->width <= 0 || source->height <= 0) {
            renderErrors.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        if (windowWidth != source->width || windowHeight != source->height) {
            const int result = ANativeWindow_setBuffersGeometry(
                    window, source->width, source->height, WINDOW_FORMAT_RGBA_8888);
            if (result != 0) {
                renderErrors.fetch_add(1, std::memory_order_relaxed);
                lastError = "ANativeWindow_setBuffersGeometry a échoué : "
                        + std::to_string(result);
                return;
            }
            windowWidth = source->width;
            windowHeight = source->height;
        }

        sws = sws_getCachedContext(
                sws,
                source->width,
                source->height,
                static_cast<AVPixelFormat>(source->format),
                source->width,
                source->height,
                AV_PIX_FMT_RGBA,
                SWS_FAST_BILINEAR,
                nullptr,
                nullptr,
                nullptr);
        if (sws == nullptr) {
            renderErrors.fetch_add(1, std::memory_order_relaxed);
            lastError = "Création du convertisseur couleur FFmpeg impossible.";
            return;
        }

        ANativeWindow_Buffer buffer{};
        const int lockResult = ANativeWindow_lock(window, &buffer, nullptr);
        if (lockResult != 0) {
            renderErrors.fetch_add(1, std::memory_order_relaxed);
            lastError = "ANativeWindow_lock a échoué : " + std::to_string(lockResult);
            return;
        }

        uint8_t* destination[4] = {
                static_cast<uint8_t*>(buffer.bits), nullptr, nullptr, nullptr
        };
        const int destinationStride[4] = {buffer.stride * 4, 0, 0, 0};
        const int rows = sws_scale(
                sws,
                source->data,
                source->linesize,
                0,
                source->height,
                destination,
                destinationStride);
        const int postResult = ANativeWindow_unlockAndPost(window);
        if (rows <= 0 || postResult != 0) {
            renderErrors.fetch_add(1, std::memory_order_relaxed);
            lastError = "Conversion ou publication de l'image sur Surface impossible.";
            return;
        }
        renderedFrames.fetch_add(1, std::memory_order_relaxed);
    }

    bool dvMode;
    uint32_t negotiatedFrameSize;
    ANativeWindow* window = nullptr;
    SpscByteRing ring;

    std::atomic<bool> stopping{false};
    std::atomic<bool> running{false};
    std::atomic<bool> resyncRequested{false};
    std::thread worker;

    AVCodecContext* codecContext = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* decodedFrame = nullptr;
    SwsContext* sws = nullptr;

    panasonic::UvcFrameAssembler dvAssembler{
            0, panasonic::UvcFrameAssembler::Policy::ExactSize};
    panasonic::UvcFrameAssembler jpegAssembler{
            kMaximumMjpegFrameBytes,
            panasonic::UvcFrameAssembler::Policy::UpToMaximum};

    std::atomic<uint64_t> payloadBytes{0};
    std::atomic<uint64_t> decodedFrames{0};
    std::atomic<uint64_t> renderedFrames{0};
    std::atomic<uint64_t> uvcErrorFlags{0};
    std::atomic<uint64_t> malformedHeaders{0};
    std::atomic<uint64_t> queueDrops{0};
    std::atomic<uint64_t> assemblyDrops{0};
    std::atomic<uint64_t> decodeErrors{0};
    std::atomic<uint64_t> renderErrors{0};
    int windowWidth = 0;
    int windowHeight = 0;
    std::string lastError;
};

PreviewDecoder::PreviewDecoder(bool dvMode,
                               uint32_t negotiatedFrameSize,
                               ANativeWindow* window)
    : impl_(new Impl(dvMode, negotiatedFrameSize, window)) {}

PreviewDecoder::~PreviewDecoder() = default;

bool PreviewDecoder::start(std::string& error) {
    return impl_ != nullptr && impl_->start(error);
}

bool PreviewDecoder::pushUvcPacket(const uint8_t* packet, size_t packetLength) {
    return impl_ != nullptr && impl_->pushUvcPacket(packet, packetLength);
}

void PreviewDecoder::stop() {
    if (impl_ != nullptr) impl_->stop();
}

std::string PreviewDecoder::summary() const {
    return impl_ == nullptr ? std::string() : impl_->summary();
}
