#include "uvc_frame_assembler.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

using panasonic::UvcFrameAssembler;
using Frame = std::vector<uint8_t>;

static void testEofIsAuthoritativeAndSameFidTailIsIgnored() {
    UvcFrameAssembler assembler(4, UvcFrameAssembler::Policy::ExactSize);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t a[] = {1, 2}, b[] = {3, 4}, ignored[] = {99, 100};
    const uint8_t c[] = {5, 6}, d[] = {7, 8};

    assembler.push(0x00, a, sizeof(a), collect);
    assembler.push(0x02, b, sizeof(b), collect);
    assembler.push(0x00, ignored, sizeof(ignored), collect);
    assembler.push(0x01, c, sizeof(c), collect);
    assembler.push(0x03, d, sizeof(d), collect);

    assert(frames.size() == 2);
    assert((frames[0] == Frame{1, 2, 3, 4}));
    assert((frames[1] == Frame{5, 6, 7, 8}));
    assert(assembler.statistics().payloadsAfterEof == 1);
}

static void testFidFallbackWhenEofIsAbsent() {
    UvcFrameAssembler assembler(4, UvcFrameAssembler::Policy::ExactSize);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t a[] = {1, 2}, b[] = {3, 4}, c[] = {5, 6}, d[] = {7, 8};
    assembler.push(0x00, a, sizeof(a), collect);
    assembler.push(0x00, b, sizeof(b), collect);
    assembler.push(0x01, c, sizeof(c), collect);
    assembler.push(0x01, d, sizeof(d), collect);
    assembler.push(0x00, nullptr, 0, collect);
    assert(frames.size() == 2);
    assert((frames[0] == Frame{1, 2, 3, 4}));
    assert((frames[1] == Frame{5, 6, 7, 8}));
}

static void testTruncatedFrameIsRejected() {
    UvcFrameAssembler assembler(4, UvcFrameAssembler::Policy::ExactSize);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t partial[] = {1, 2}, a[] = {3, 4}, b[] = {5, 6};
    assembler.push(0x00, partial, sizeof(partial), collect);
    assembler.push(0x01, a, sizeof(a), collect);
    assembler.push(0x01, b, sizeof(b), collect);
    assembler.push(0x03, nullptr, 0, collect);
    assert(frames.size() == 1);
    assert((frames[0] == Frame{3, 4, 5, 6}));
    assert(assembler.statistics().incompleteFrames == 1);
}

static void testHeaderOnlyEofClosesFrame() {
    UvcFrameAssembler assembler(4, UvcFrameAssembler::Policy::ExactSize);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t a[] = {1, 2}, b[] = {3, 4};
    assembler.push(0x00, a, sizeof(a), collect);
    assembler.push(0x00, b, sizeof(b), collect);
    assembler.push(0x02, nullptr, 0, collect);
    assert(frames.size() == 1);
    assert((frames[0] == Frame{1, 2, 3, 4}));
}

static void testOversizedFrameIsDroppedUntilBoundary() {
    UvcFrameAssembler assembler(3, UvcFrameAssembler::Policy::UpToMaximum);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t oversized[] = {1, 2, 3, 4}, valid[] = {5, 6, 7};
    assembler.push(0x00, oversized, sizeof(oversized), collect);
    assembler.push(0x02, nullptr, 0, collect);
    assembler.push(0x01, valid, sizeof(valid), collect);
    assembler.push(0x03, nullptr, 0, collect);
    assert(frames.size() == 1);
    assert((frames[0] == Frame{5, 6, 7}));
    assert(assembler.statistics().oversizedFrames == 1);
}

static void testMjpegCanFlushOnlyWithEoiMarker() {
    UvcFrameAssembler assembler(16, UvcFrameAssembler::Policy::UpToMaximum);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t jpeg[] = {0xFF, 0xD8, 0x10, 0x20, 0xFF, 0xD9};
    assembler.push(0x00, jpeg, sizeof(jpeg), collect);
    assembler.flushAtStop(collect, true);
    assert(frames.size() == 1);
    assert(frames[0].size() == sizeof(jpeg));
}

static void testResyncWaitsForNextFid() {
    UvcFrameAssembler assembler(4, UvcFrameAssembler::Policy::ExactSize);
    std::vector<Frame> frames;
    auto collect = [&frames](const Frame& frame) { frames.push_back(frame); };
    const uint8_t partial[] = {1, 2}, stale[] = {90, 91};
    const uint8_t a[] = {3, 4}, b[] = {5, 6};
    assembler.push(0x00, partial, sizeof(partial), collect);
    assembler.resync();
    assembler.push(0x00, stale, sizeof(stale), collect);
    assembler.push(0x01, a, sizeof(a), collect);
    assembler.push(0x01, b, sizeof(b), collect);
    assembler.push(0x03, nullptr, 0, collect);
    assert(frames.size() == 1);
    assert((frames[0] == Frame{3, 4, 5, 6}));
    assert(assembler.statistics().resyncDrops == 1);
}

int main() {
    testEofIsAuthoritativeAndSameFidTailIsIgnored();
    testFidFallbackWhenEofIsAbsent();
    testTruncatedFrameIsRejected();
    testHeaderOnlyEofClosesFrame();
    testOversizedFrameIsDroppedUntilBoundary();
    testMjpegCanFlushOnlyWithEoiMarker();
    testResyncWaitsForNextFid();
    std::cout << "UVC frame assembler: 7 tests passed\n";
    return 0;
}
