#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

struct ANativeWindow;

/**
 * Best-effort video preview fed with complete UVC payload packets.
 *
 * USB callbacks only copy bytes into a bounded SPSC ring. Frame assembly,
 * FFmpeg decoding and Surface rendering happen on a separate worker thread,
 * so a slow decoder cannot block the isochronous event loop.
 */
class PreviewDecoder {
public:
    PreviewDecoder(bool dvMode, uint32_t negotiatedFrameSize, ANativeWindow* window);
    ~PreviewDecoder();

    PreviewDecoder(const PreviewDecoder&) = delete;
    PreviewDecoder& operator=(const PreviewDecoder&) = delete;

    bool start(std::string& error);
    bool pushUvcPacket(const uint8_t* packet, size_t packetLength);
    void stop();
    std::string summary() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
