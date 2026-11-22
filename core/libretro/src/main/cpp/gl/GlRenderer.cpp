#include "GlRenderer.h"

#include "../host/Log.h"

namespace retrohost {

namespace {
const char* kVertex = R"(#version 300 es
uniform vec4 uDest;    // x, y, w, h in surface pixels, top-left origin
uniform vec4 uSrc;     // u0, v0, u1, v1 into the core frame (v=0 is the top row)
uniform vec2 uSurface; // surface size in pixels
out vec2 vUv;
void main() {
    // Triangle strip corners from gl_VertexID: (0,0) (1,0) (0,1) (1,1). No vertex buffers.
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 px = uDest.xy + c * uDest.zw;
    gl_Position = vec4(px.x / uSurface.x * 2.0 - 1.0, 1.0 - px.y / uSurface.y * 2.0, 0.0, 1.0);
    vUv = mix(uSrc.xy, uSrc.zw, c);
}
)";

const char* kFragment = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform bool uBgr;     // XRGB8888 is uploaded as RGBA bytes, which are B,G,R,X in memory
in vec2 vUv;
out vec4 outColor;
void main() {
    vec4 c = texture(uTex, vUv);
    outColor = vec4(uBgr ? c.bgr : c.rgb, 1.0);
}
)";

GLuint compile(GLenum type, const char* src, std::string& error) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        error = std::string("shader compile failed: ") + log;
        glDeleteShader(s);
        return 0;
    }
    return s;
}
}  // namespace

bool GlRenderer::init(std::string& error) {
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex, error);
    if (!vs) return false;
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment, error);
    if (!fs) { glDeleteShader(vs); return false; }
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {};
        glGetProgramInfoLog(program_, sizeof log, nullptr, log);
        error = std::string("program link failed: ") + log;
        return false;
    }
    uDest_ = glGetUniformLocation(program_, "uDest");
    uSrc_ = glGetUniformLocation(program_, "uSrc");
    uSurface_ = glGetUniformLocation(program_, "uSurface");
    uBgr_ = glGetUniformLocation(program_, "uBgr");
    uTex_ = glGetUniformLocation(program_, "uTex");

    glGenVertexArrays(1, &vao_);
    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return glGetError() == GL_NO_ERROR || (error = "GL error during init", false);
}

void GlRenderer::destroy() {
    if (texture_) glDeleteTextures(1, &texture_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (program_) glDeleteProgram(program_);
    texture_ = vao_ = program_ = 0;
    texW_ = texH_ = 0;
    uploadedSeq_ = UINT64_MAX;
}

void GlRenderer::upload(const FrameBuffer& frame) {
    frame.read([&](const FrameBuffer::View& v) {
        if (v.sequence == uploadedSeq_ && texW_ > 0) return;
        glBindTexture(GL_TEXTURE_2D, texture_);
        glPixelStorei(GL_UNPACK_ALIGNMENT, v.format == PixelFormat::RGB565 ? 2 : 4);
        const bool realloc = static_cast<int>(v.width) != texW_ || static_cast<int>(v.height) != texH_ || v.format != texFormat_;
        const GLenum fmt = v.format == PixelFormat::RGB565 ? GL_RGB : GL_RGBA;
        const GLenum type = v.format == PixelFormat::RGB565 ? GL_UNSIGNED_SHORT_5_6_5 : GL_UNSIGNED_BYTE;
        if (realloc) {
            const GLint internal = v.format == PixelFormat::RGB565 ? GL_RGB565 : GL_RGBA8;
            glTexImage2D(GL_TEXTURE_2D, 0, internal, static_cast<GLsizei>(v.width), static_cast<GLsizei>(v.height), 0, fmt, type, v.pixels);
            texW_ = static_cast<int>(v.width);
            texH_ = static_cast<int>(v.height);
            texFormat_ = v.format;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, static_cast<GLsizei>(v.width), static_cast<GLsizei>(v.height), fmt, type, v.pixels);
        }
        uploadedSeq_ = v.sequence;
    });
}

void GlRenderer::draw(int surfaceW, int surfaceH, const std::vector<Quad>& quads) {
    glViewport(0, 0, surfaceW, surfaceH);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (texW_ == 0 || quads.empty()) return;
    glUseProgram(program_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glUniform1i(uTex_, 0);
    glUniform1i(uBgr_, texFormat_ == PixelFormat::XRGB8888 ? 1 : 0);
    glUniform2f(uSurface_, static_cast<float>(surfaceW), static_cast<float>(surfaceH));
    for (const Quad& q : quads) {
        if (q.w <= 0 || q.h <= 0) continue;
        glUniform4f(uDest_, q.x, q.y, q.w, q.h);
        glUniform4f(uSrc_, q.u0, q.v0, q.u1, q.v1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
}

}  // namespace retrohost
