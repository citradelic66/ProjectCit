#include "SaveStateFile.h"

#include <cstdio>
#include <cstring>

namespace retrohost {

namespace {
constexpr char kMagic[8] = {'P', 'C', 'S', 'T', 'A', 'T', 'E', '1'};
constexpr uint32_t kHeaderSize = 128;
constexpr uint32_t kVersion = 1;

void putU32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i)); }
void putU64(uint8_t* p, uint64_t v) { for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i)); }
uint32_t getU32(const uint8_t* p) { uint32_t v = 0; for (int i = 3; i >= 0; --i) v = (v << 8) | p[i]; return v; }
uint64_t getU64(const uint8_t* p) { uint64_t v = 0; for (int i = 7; i >= 0; --i) v = (v << 8) | p[i]; return v; }
void putStr(uint8_t* p, size_t cap, const std::string& s) { std::memcpy(p, s.data(), s.size() < cap - 1 ? s.size() : cap - 1); }
std::string getStr(const uint8_t* p, size_t cap) { return std::string(reinterpret_cast<const char*>(p), strnlen(reinterpret_cast<const char*>(p), cap)); }
}  // namespace

uint32_t crc32(const uint8_t* data, size_t size) {
    static uint32_t table[256];
    static bool init = false;
    if (!init) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        init = true;
    }
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < size; ++i) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

bool writeFileAtomic(const std::string& path, const uint8_t* data, size_t size, std::string& error) {
    const std::string tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if (!f) { error = "cannot open " + tmp; return false; }
    const bool ok = (size == 0 || std::fwrite(data, 1, size, f) == size) && std::fflush(f) == 0;
    std::fclose(f);
    if (!ok) { std::remove(tmp.c_str()); error = "write failed: " + tmp; return false; }
    if (std::rename(tmp.c_str(), path.c_str()) != 0) { std::remove(tmp.c_str()); error = "rename failed: " + path; return false; }
    return true;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    out.clear();
    uint8_t chunk[65536];
    size_t n;
    while ((n = std::fread(chunk, 1, sizeof chunk, f)) > 0) out.insert(out.end(), chunk, chunk + n);
    const bool ok = !std::ferror(f);
    std::fclose(f);
    return ok;
}

bool writeSaveState(const std::string& path, const SaveStateHeader& h, const std::vector<uint8_t>& payload, std::string& error) {
    std::vector<uint8_t> file(kHeaderSize + payload.size(), 0);
    uint8_t* p = file.data();
    std::memcpy(p, kMagic, 8);
    putU32(p + 8, kHeaderSize);
    putU32(p + 12, kVersion);
    putStr(p + 16, 48, h.coreName);
    putStr(p + 64, 32, h.coreVersion);
    putU64(p + 96, h.timestampMs);
    putU64(p + 104, payload.size());
    putU32(p + 112, crc32(payload.data(), payload.size()));
    if (!payload.empty()) std::memcpy(p + kHeaderSize, payload.data(), payload.size());
    return writeFileAtomic(path, file.data(), file.size(), error);
}

bool readSaveState(const std::string& path, SaveStateHeader& h, std::vector<uint8_t>& payload, std::string& error) {
    std::vector<uint8_t> file;
    if (!readFile(path, file)) { error = "cannot read " + path; return false; }
    if (file.size() < kHeaderSize || std::memcmp(file.data(), kMagic, 8) != 0) { error = "not a ProjectCit save state"; return false; }
    const uint8_t* p = file.data();
    const uint32_t headerSize = getU32(p + 8);
    if (headerSize < kHeaderSize || headerSize > file.size()) { error = "bad header size"; return false; }
    if (getU32(p + 12) > kVersion) { error = "save state is from a newer app version"; return false; }
    h.coreName = getStr(p + 16, 48);
    h.coreVersion = getStr(p + 64, 32);
    h.timestampMs = getU64(p + 96);
    h.payloadSize = getU64(p + 104);
    h.payloadCrc = getU32(p + 112);
    if (h.payloadSize != file.size() - headerSize) { error = "save state is truncated"; return false; }
    payload.assign(file.begin() + headerSize, file.end());
    if (crc32(payload.data(), payload.size()) != h.payloadCrc) { error = "save state is corrupted (CRC mismatch)"; return false; }
    return true;
}

}  // namespace retrohost
