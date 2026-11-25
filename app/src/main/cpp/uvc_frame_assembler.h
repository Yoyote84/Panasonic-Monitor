#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace panasonic {

// UVC EOF is the preferred frame boundary; FID change is the fallback.
// Once EOF closes a frame, further payloads with the same FID are ignored.
class UvcFrameAssembler {
public:
    enum class Policy { ExactSize, UpToMaximum };

    struct Statistics {
        uint64_t completedFrames = 0;
        uint64_t incompleteFrames = 0;
        uint64_t oversizedFrames = 0;
        uint64_t payloadsAfterEof = 0;
        uint64_t resyncDrops = 0;
    };

    UvcFrameAssembler(size_t frameLimit, Policy policy)
        : frameLimit_(frameLimit), policy_(policy) {}

    template <typename FrameHandler>
    void push(uint8_t flags, const uint8_t* payload, size_t length,
              FrameHandler&& onFrame) {
        if (payload == nullptr && length != 0) {
            ++statistics_.incompleteFrames;
            return;
        }

        const int incomingFid = (flags & kFid) != 0 ? 1 : 0;
        if (waitingForFidChange_) {
            if (incomingFid == fid_) {
                if (length != 0) ++statistics_.payloadsAfterEof;
                return;
            }
            fid_ = incomingFid;
            waitingForFidChange_ = false;
            frameClosed_ = false;
        } else if (!haveFid_) {
            fid_ = incomingFid;
            haveFid_ = true;
            frameClosed_ = false;
        } else if (incomingFid != fid_) {
            if (!frameClosed_) closeFrame(onFrame);
            fid_ = incomingFid;
            frameClosed_ = false;
        }

        if (frameClosed_) {
            if (length != 0) ++statistics_.payloadsAfterEof;
            return;
        }

        if (!dropping_ && length != 0) {
            if (frame_.size() > frameLimit_
                    || length > frameLimit_ - frame_.size()) {
                frame_.clear();
                dropping_ = true;
                ++statistics_.oversizedFrames;
            } else {
                frame_.insert(frame_.end(), payload, payload + length);
            }
        }

        // Header-only UVC packets can still carry an EOF/FID boundary.
        if ((flags & kEof) != 0) closeFrame(onFrame);
    }

    template <typename FrameHandler>
    void flushAtStop(FrameHandler&& onFrame, bool requireJpegEndMarker = false) {
        if (!haveFid_ || frameClosed_) return;

        if (!dropping_ && !frame_.empty()) {
            bool complete = false;
            if (policy_ == Policy::ExactSize) {
                complete = frame_.size() == frameLimit_;
            } else if (requireJpegEndMarker && frame_.size() >= 2) {
                complete = frame_[frame_.size() - 2] == 0xFF
                        && frame_[frame_.size() - 1] == 0xD9;
            }
            if (complete) {
                onFrame(frame_);
                ++statistics_.completedFrames;
            } else {
                ++statistics_.incompleteFrames;
            }
        }
        frame_.clear();
        dropping_ = false;
        frameClosed_ = true;
    }

    // After a ring overflow, discard until the device announces the next FID.
    void resync() {
        if (!frame_.empty() || dropping_) ++statistics_.resyncDrops;
        frame_.clear();
        dropping_ = false;
        frameClosed_ = true;
        waitingForFidChange_ = haveFid_;
    }

    Statistics statistics() const { return statistics_; }

private:
    static constexpr uint8_t kFid = 0x01;
    static constexpr uint8_t kEof = 0x02;

    template <typename FrameHandler>
    void closeFrame(FrameHandler&& onFrame) {
        if (dropping_) {
            dropping_ = false;
        } else if (!frame_.empty()) {
            if (policy_ == Policy::ExactSize && frame_.size() != frameLimit_) {
                ++statistics_.incompleteFrames;
            } else {
                onFrame(frame_);
                ++statistics_.completedFrames;
            }
        }
        frame_.clear();
        frameClosed_ = true;
    }

    size_t frameLimit_;
    Policy policy_;
    std::vector<uint8_t> frame_;
    int fid_ = -1;
    bool haveFid_ = false;
    bool frameClosed_ = true;
    bool dropping_ = false;
    bool waitingForFidChange_ = false;
    Statistics statistics_;
};

}  // namespace panasonic
