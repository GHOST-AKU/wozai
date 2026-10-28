//! Small embedded message catalog. Commands and machine-facing data remain unchanged.
use std::{collections::BTreeMap, ffi::OsString, io::Read, path::PathBuf, sync::OnceLock};

type Catalog = BTreeMap<String, BTreeMap<String, String>>;
fn catalog() -> &'static Catalog {
    static CATALOG: OnceLock<Catalog> = OnceLock::new();
    CATALOG.get_or_init(|| {
        serde_json::from_str(include_str!("../i18n/messages.json"))
            .expect("validated embedded messages")
    })
}
pub fn has(key: &str) -> bool {
    catalog().contains_key(key)
}
pub fn text(language: &str, key: &str) -> String {
    let language = effective_language(language);
    catalog()
        .get(key)
        .and_then(|m| m.get(language).or_else(|| m.get("en")))
        .cloned()
        .unwrap_or_else(|| key.into())
}
pub fn format(language: &str, key: &str, values: &[(&str, &str)]) -> String {
    let template = text(language, key);
    let mut rest = template.as_str();
    let mut result = String::new();
    while let Some((before, after)) = rest.split_once('{') {
        result.push_str(before);
        let Some((name, tail)) = after.split_once('}') else {
            result.push('{');
            result.push_str(after);
            return result;
        };
        if let Some((_, value)) = values.iter().find(|(k, _)| *k == name) {
            result.push_str(value);
        } else {
            result.push('{');
            result.push_str(name);
            result.push('}');
        }
        rest = tail;
    }
    result.push_str(rest);
    result
}
pub const LANGUAGES: [&str; 5] = ["en", "zh-CN", "zh-Hant", "ja", "ko"];
pub fn effective_language(selection: &str) -> &'static str {
    if selection == "system" {
        system_language()
    } else {
        locale_language(selection)
    }
}
pub fn supported(selection: &str) -> bool {
    LANGUAGES.contains(&selection)
        || matches!(
            selection,
            "system"
                | "en"
                | "zh-CN"
                | "zh-Hans"
                | "zh-Hant"
                | "zh-TW"
                | "zh-HK"
                | "zh-MO"
                | "ja"
                | "ko"
        )
}
pub fn locale_language(locale: &str) -> &'static str {
    if locale.contains('@') {
        return "en";
    }
    let locale = locale
        .trim()
        .split('.')
        .next()
        .unwrap_or("")
        .replace('_', "-")
        .to_ascii_lowercase();
    let parts: Vec<_> = locale.split('-').collect();
    if parts
        .iter()
        .any(|p| p.is_empty() || p.len() > 8 || !p.bytes().all(|b| b.is_ascii_alphanumeric()))
    {
        return "en";
    }
    match parts.first().copied().unwrap_or("") {
        "zh" if parts.contains(&"hant") => "zh-Hant",
        "zh" if parts.contains(&"hans") => "zh-CN",
        "zh" if parts.iter().any(|p| ["tw", "hk", "mo"].contains(p)) => "zh-Hant",
        "zh" => "zh-CN",
        "ja" => "ja",
        "ko" => "ko",
        _ => "en",
    }
}
pub fn system_language() -> &'static str {
    for key in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(value) = std::env::var(key) {
            if !value.trim().is_empty() {
                return locale_language(&value);
            }
        }
    }
    #[cfg(windows)]
    {
        let mut name = [0u16; 85];
        // This official API writes at most the supplied locale-name buffer.
        let count = unsafe {
            windows_sys::Win32::Globalization::GetUserDefaultLocaleName(
                name.as_mut_ptr(),
                name.len() as i32,
            )
        };
        if count > 1 {
            return locale_language(&String::from_utf16_lossy(&name[..count as usize - 1]));
        }
    }
    "en"
}
pub fn default_profile() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("nearbyim-terminal")
}
/// Help probes only a small settings file; it never opens/locks a Profile.
pub fn cli_language(args: &[OsString]) -> String {
    let mut profile = None;
    let mut args = args.iter().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--" {
            break;
        }
        if arg == "--profile" {
            profile = args.next().map(PathBuf::from);
        } else if let Some(value) = arg.to_str().and_then(|s| s.strip_prefix("--profile=")) {
            profile = Some(PathBuf::from(value));
        } else if [
            "--name",
            "--theme",
            "--timeout",
            "--receive-dir",
            "--listen",
            "--address",
            "--text",
            "--file",
            "--qr",
            "--output",
            "--peer",
            "--before",
            "--limit",
        ]
        .iter()
        .any(|v| arg == *v)
        {
            args.next();
        }
    }
    let path = profile.unwrap_or_else(default_profile).join("config.json");
    if std::fs::symlink_metadata(&path).is_ok_and(|m| m.is_file() && m.len() <= 8192)
        && let Ok(file) = std::fs::File::open(path)
        && let Ok(bytes) = std::io::BufReader::new(file)
            .take(8193)
            .bytes()
            .collect::<std::io::Result<Vec<_>>>()
        && bytes.len() <= 8192
        && let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes)
        && let Some(language) = value.get("language").and_then(|v| v.as_str())
        && supported(language)
    {
        return effective_language(language).into();
    }
    system_language().into()
}

thread_local! { static CURRENT: std::cell::RefCell<String> = std::cell::RefCell::new("en".into()); }
pub fn set_current(language: &str) {
    CURRENT.with(|value| *value.borrow_mut() = effective_language(language).into());
}
pub fn current_state(code: &str) -> String {
    let key = std::format!("state.{code}");
    if has(&key) {
        current_text(&key)
    } else {
        code.into()
    }
}
pub fn current_text(key: &str) -> String {
    CURRENT.with(|value| text(&value.borrow(), key))
}
pub fn current_format(key: &str, values: &[(&str, &str)]) -> String {
    CURRENT.with(|value| format(&value.borrow(), key, values))
}
pub fn current_diagnostic(value: &str) -> String {
    CURRENT.with(|language| diagnostic(&language.borrow(), value))
}

/// Match only complete application diagnostic templates, never user-content substrings.
pub fn diagnostic(language: &str, value: &str) -> String {
    if effective_language(language) == "en" || value.is_empty() {
        return value.into();
    }
    for (id, message) in catalog().iter().filter(|(id, _)| id.starts_with("error.")) {
        let pattern = &message["en"];
        if value == pattern {
            return text(language, id);
        }
        if let Some(detail) = value
            .strip_prefix(pattern)
            .and_then(|tail| tail.strip_prefix(": "))
        {
            return std::format!(
                "{}\n{}",
                text(language, id),
                format(language, "human.diagnostic", &[("detail", detail)])
            );
        }
        let mut rest = pattern.as_str();
        let mut input = value;
        let mut params = Vec::new();
        let mut matches = true;
        while let Some((prefix, after)) = rest.split_once('{') {
            let Some((name, tail)) = after.split_once('}') else {
                matches = false;
                break;
            };
            let Some(after_prefix) = input.strip_prefix(prefix) else {
                matches = false;
                break;
            };
            let suffix = tail.split('{').next().unwrap_or("");
            let (parameter, remaining) = if suffix.is_empty() {
                (after_prefix, "")
            } else if let Some(pos) = after_prefix.find(suffix) {
                (&after_prefix[..pos], &after_prefix[pos..])
            } else {
                matches = false;
                break;
            };
            params.push((name, parameter));
            input = remaining;
            rest = tail;
        }
        if matches && !params.is_empty() && input == rest {
            let expanded: Vec<_> = params
                .iter()
                .map(|(name, value)| {
                    (
                        *name,
                        if matches!(*name, "e" | "e:#" | "reason") {
                            diagnostic(language, value)
                        } else {
                            value.to_string()
                        },
                    )
                })
                .collect();
            let refs: Vec<_> = expanded
                .iter()
                .map(|(name, value)| (*name, value.as_str()))
                .collect();
            return format(language, id, &refs);
        }
    }
    format(language, "human.diagnostic", &[("detail", value)])
}

#[cfg(test)]
mod tests {
    use super::*;
    fn parameters(value: &str) -> std::collections::BTreeSet<&str> {
        value
            .split('{')
            .skip(1)
            .filter_map(|v| v.split_once('}').map(|p| p.0))
            .collect()
    }
    #[test]
    fn catalog_has_matching_languages_and_named_parameters() {
        for (id, message) in catalog() {
            assert_eq!(message.len(), 5, "{id}");
            for lang in LANGUAGES {
                assert!(!message.get(lang).unwrap().trim().is_empty(), "{id}/{lang}");
                assert_eq!(
                    parameters(&message["en"]),
                    parameters(&message[lang]),
                    "{id}/{lang}"
                );
            }
            assert_eq!(
                parameters(&message["en"]),
                parameters(&message["zh-CN"]),
                "{id}"
            );
        }
    }
    #[test]
    fn five_language_locale_and_diagnostics_preserve_literal_values() {
        for (locale, expected) in [
            ("zh_TW.UTF-8", "zh-Hant"),
            ("zh-Hans-HK", "zh-CN"),
            ("zh-Hant-CN", "zh-Hant"),
            ("ja_JP.UTF-8", "ja"),
            ("ko_KR.UTF-8", "ko"),
            ("zh___", "en"),
            ("zh_CN@unknown", "en"),
        ] {
            assert_eq!(locale_language(locale), expected);
        }
        for language in LANGUAGES {
            let value = diagnostic(
                language,
                "text must contain 1–32768 UTF-8 bytes; draft preserved",
            );
            assert!(value.contains("32768"));
            assert!(!value.contains("{limit}"));
            let literal = "O'Brien {limit} 中文";
            assert!(format(language, "human.qr_saved", &[("path", literal)]).contains(literal));
            let unknown = diagnostic(language, "Operating system code 1234");
            assert!(unknown.contains("Operating system code 1234"));
        }
    }
    #[test]
    fn values_are_not_expanded_as_templates() {
        assert_eq!(
            format("zh-CN", "header.brand", &[("name", "{name}")]),
            "NearbyIM Terminal · {name}"
        );
        assert_eq!(locale_language("zh_CN.UTF-8"), "zh-CN");
        assert_eq!(locale_language("zh-Hans-CN"), "zh-CN");
        assert_eq!(locale_language("zh_TW.UTF-8"), "zh-Hant");
        assert_eq!(locale_language("fr_FR.UTF-8"), "en");
    }
}
