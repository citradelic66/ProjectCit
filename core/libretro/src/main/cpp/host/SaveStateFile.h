#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace retrohost {

/**
 * On-disk save state: a fixed 128-byte little-endian header followed by the raw
 * retro_serialize() payload. The header lets us refuse a state from a different
 * core and detect truncated/corrupted files (CRC-32) *before* handing bytes to
 * retro_unserialize, which most cores do not validate.
 *
 *   0  char[8]  magic "PCSTATE1"
 *   8  u32      header size (128)
 *  12  u32      format version (1)
 *  16  char[48] core library_name (NUL padded)
 *  64  char[32] core library_version
 *  96  u64      unix time (ms)
 * 104  u64      payload size
 * 112  u32      payload CRC-32
 * 116  u8[12]   reserved
 */
struct SaveStateHeader {
    std::string coreName, coreVersion;
    uint64_t timestampMs = 0;
    uint64_t payloadSize = 0;
    uint32_t payloadCrc = 0;
};

uint32_t crc32(const uint8_t* data, size_t size);

bool writeSaveState(const std::string& path, const SaveStateHeader& header, const std::vector<uint8_t>& payload, std::string& error);
bool readSaveState(const std::string& path, SaveStateHeader& header, std::vector<uint8_t>& payload, std::string& error);

/** Writes to path.tmp then rename()s, so a crash never leaves a half-written file. */
bool writeFileAtomic(const std::string& path, const uint8_t* data, size_t size, std::string& error);
bool readFile(const std::string& path, std::vector<uint8_t>& out);

}  // namespace retrohost
