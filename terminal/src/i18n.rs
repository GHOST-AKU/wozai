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
pub fn locale_language(locale: &str) -> &'static str {
    let locale = locale
        .split(['.', '@'])
        .next()
        .unwrap_or("")
        .replace('_', "-")
        .to_ascii_lowercase();
    if matches!(locale.as_str(), "zh" | "zh-cn" | "zh-sg" | "zh-hans")
        || locale.starts_with("zh-hans-")
    {
        "zh-CN"
    } else {
        "en"
    }
}
pub fn system_language() -> &'static str {
    for key in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(value) = std::env::var(key) {
            if !value.is_empty() {
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
        && matches!(language, "en" | "zh-CN")
    {
        return language.into();
    }
    system_language().into()
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
            assert_eq!(message.len(), 2, "{id}");
            for lang in ["en", "zh-CN"] {
                assert!(!message.get(lang).unwrap().trim().is_empty(), "{id}/{lang}");
            }
            assert_eq!(
                parameters(&message["en"]),
                parameters(&message["zh-CN"]),
                "{id}"
            );
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
        assert_eq!(locale_language("zh_TW.UTF-8"), "en");
        assert_eq!(locale_language("fr_FR.UTF-8"), "en");
    }
}
