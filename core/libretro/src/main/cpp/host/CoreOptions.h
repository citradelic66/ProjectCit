#pragma once
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "../libretro/libretro.h"

namespace retrohost {

/**
 * Core option store. Understands every way a core can declare options
 * (legacy SET_VARIABLES, v1, v1-intl, v2, v2-intl) and applies host overrides
 * (e.g. force melonDS DS to a top/bottom 256×384 frame) as definitions arrive.
 */
class CoreOptions {
public:
    struct Option {
        std::string key, description, value, defaultValue;
        std::vector<std::string> values;
        bool visible = true;
    };

    void setOverrides(std::vector<std::pair<std::string, std::string>> overrides);

    void defineLegacy(const retro_variable* vars);
    void defineV1(const retro_core_option_definition* defs);
    void defineV2(const retro_core_options_v2* opts);

    /** Pointer stays valid until this key's value changes (libretro contract). */
    const char* get(const char* key) const;
    /** Host or core changes a value. Returns false for unknown keys or disallowed values. */
    bool set(const std::string& key, const std::string& value);
    void setVisible(const char* key, bool visible);
    /** RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE semantics: true once after any change. */
    bool consumeDirty();

    std::vector<Option> snapshot() const;

private:
    void define(Option opt);
    std::map<std::string, Option> options_;
    std::map<std::string, std::string> overrides_;
    bool dirty_ = false;
    mutable std::mutex mutex_;
};

}  // namespace retrohost
