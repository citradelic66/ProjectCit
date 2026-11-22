#pragma once
#include <GLES3/gl3.h>

#include <cstdint>
#include <string>
#include <vector>

#include "../host/FrameBuffer.h"
#include "../host/Outputs.h"

namespace retrohost {

/**
 * GLES 3.0 presenter. The core frame is uploaded once per new frame into a single
 * texture; each Quad then draws a UV-cropped region of it. NDS = two quads over one
 * 256×384 texture (top half / bottom half), so the screens can be placed, scaled,
 * swapped or split across a hinge independently with zero extra copies.
 * Nearest-neighbour sampling keeps pixels crisp.
 */
class GlRenderer {
public:
    bool init(std::string& error);
    void destroy();
    /** Uploads the front frame if it changed since the last upload. */
    void upload(const FrameBuffer& frame);
    /** Draws into the currently bound framebuffer of size w×h (top-left origin coordinates). */
    void draw(int surfaceW, int surfaceH, const std::vector<Quad>& quads);
    bool hasTexture() const { return texW_ > 0; }

private:
    GLuint program_ = 0, texture_ = 0, vao_ = 0;
    GLint uDest_ = -1, uSrc_ = -1, uSurface_ = -1, uBgr_ = -1, uTex_ = -1;
    int texW_ = 0, texH_ = 0;
    PixelFormat texFormat_ = PixelFormat::RGB565;
    uint64_t uploadedSeq_ = UINT64_MAX;
};

}  // namespace retrohost
