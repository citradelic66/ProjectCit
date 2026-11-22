#include "CoreOptions.h"

#include <algorithm>

#include "Log.h"

namespace retrohost {

void CoreOptions::setOverrides(std::vector<std::pair<std::string, std::string>> overrides) {
    std::lock_guard<std::mutex> lock(mutex_);
    overrides_.clear();
    for (auto& kv : overrides) overrides_[kv.first] = kv.second;
}

void CoreOptions::define(Option opt) {
    if (opt.defaultValue.empty() && !opt.values.empty()) opt.defaultValue = opt.values.front();
    opt.value = opt.defaultValue;
    auto ov = overrides_.find(opt.key);
    if (ov != overrides_.end()) {
        const bool allowed = opt.values.empty() ||
            std::find(opt.values.begin(), opt.values.end(), ov->second) != opt.values.end();
        if (allowed) opt.value = ov->second;
        else LOGW("override %s=%s rejected: not an allowed value", opt.key.c_str(), ov->second.c_str());
    }
    // A redefinition keeps a value the user already changed, if still valid.
    auto existing = options_.find(opt.key);
    if (existing != options_.end() && existing->second.value != existing->second.defaultValue &&
        (opt.values.empty() || std::find(opt.values.begin(), opt.values.end(), existing->second.value) != opt.values.end())) {
        opt.value = existing->second.value;
    }
    options_[opt.key] = std::move(opt);
    dirty_ = true;
}

void CoreOptions::defineLegacy(const retro_variable* vars) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (; vars && vars->key; ++vars) {
        // "Description; default|value2|value3"
        Option o;
        o.key = vars->key;
        std::string spec = vars->value ? vars->value : "";
        const size_t semi = spec.find(';');
        o.description = spec.substr(0, semi);
        std::string list = semi == std::string::npos ? "" : spec.substr(semi + 1);
        list.erase(0, list.find_first_not_of(' '));
        size_t start = 0;
        while (start <= list.size()) {
            const size_t bar = list.find('|', start);
            std::string v = list.substr(start, bar == std::string::npos ? std::string::npos : bar - start);
            if (!v.empty()) o.values.push_back(v);
            if (bar == std::string::npos) break;
            start = bar + 1;
        }
        define(std::move(o));
    }
}

void CoreOptions::defineV1(const retro_core_option_definition* defs) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (; defs && defs->key; ++defs) {
        Option o;
        o.key = defs->key;
        o.description = defs->desc ? defs->desc : "";
        for (const auto& v : defs->values) { if (!v.value) break; o.values.emplace_back(v.value); }
        if (defs->default_value) o.defaultValue = defs->default_value;
        define(std::move(o));
    }
}

void CoreOptions::defineV2(const retro_core_options_v2* opts) {
    if (!opts) return;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const retro_core_option_v2_definition* d = opts->definitions; d && d->key; ++d) {
        Option o;
        o.key = d->key;
        o.description = d->desc ? d->desc : "";
        for (const auto& v : d->values) { if (!v.value) break; o.values.emplace_back(v.value); }
        if (d->default_value) o.defaultValue = d->default_value;
        define(std::move(o));
    }
}

const char* CoreOptions::get(const char* key) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = options_.find(key ? key : "");
    return it == options_.end() ? nullptr : it->second.value.c_str();
}

bool CoreOptions::set(const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = options_.find(key);
    if (it == options_.end()) {
        // Not declared yet: remember it so it applies when the core defines it.
        overrides_[key] = value;
        return false;
    }
    auto& o = it->second;
    if (!o.values.empty() && std::find(o.values.begin(), o.values.end(), value) == o.values.end()) return false;
    if (o.value != value) { o.value = value; dirty_ = true; }
    return true;
}

void CoreOptions::setVisible(const char* key, bool visible) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = options_.find(key ? key : "");
    if (it != options_.end()) it->second.visible = visible;
}

bool CoreOptions::consumeDirty() {
    std::lock_guard<std::mutex> lock(mutex_);
    const bool d = dirty_;
    dirty_ = false;
    return d;
}

std::vector<CoreOptions::Option> CoreOptions::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Option> out;
    out.reserve(options_.size());
    for (const auto& kv : options_) out.push_back(kv.second);
    return out;
}

}  // namespace retrohost
