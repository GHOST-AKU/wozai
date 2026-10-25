//! Offline safety-code QR; decoded content never supplies a connection or a new root.
use anyhow::{Result, ensure};
use qrcode::{Color, EcLevel, QrCode};
use std::{
    collections::BTreeSet,
    io::{Cursor, Write},
    path::Path,
};
pub fn payload(local: &[u8], remote: &[u8]) -> Result<String> {
    crate::profile::validate_root(local)?;
    crate::profile::validate_root(remote)?;
    Ok(format!(
        "WOZAI-ID1:{}",
        crate::profile::safety(local, remote)
    ))
}
fn symbol(value: &str) -> Result<QrCode> {
    ensure!(
        value.len() == 74
            && value.starts_with("WOZAI-ID1:")
            && value[10..]
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
        "invalid identity QR payload"
    );
    Ok(QrCode::with_error_correction_level(
        value.as_bytes(),
        EcLevel::M,
    )?)
}
pub fn matches(value: &str, local: &[u8], remote: &[u8]) -> Result<bool> {
    let expected = payload(local, remote)?;
    if value.len() != expected.len() {
        return Ok(false);
    }
    Ok(value
        .bytes()
        .zip(expected.bytes())
        .fold(0u8, |different, (a, b)| different | (a ^ b))
        == 0)
}
pub fn png(value: &str) -> Result<Vec<u8>> {
    let qr = symbol(value)?;
    let edge = qr.width() + 8;
    let image = image::GrayImage::from_fn((edge * 8) as u32, (edge * 8) as u32, |x, y| {
        let x = x as usize / 8;
        let y = y as usize / 8;
        image::Luma([
            if x >= 4 && y >= 4 && x < edge - 4 && y < edge - 4 && qr[(x - 4, y - 4)] == Color::Dark
            {
                0
            } else {
                255
            },
        ])
    });
    let mut bytes = Cursor::new(Vec::new());
    image.write_to(&mut bytes, image::ImageFormat::Png)?;
    Ok(bytes.into_inner())
}
pub fn export(value: &str, path: &Path) -> Result<()> {
    let bytes = png(value)?;
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)?;
    let result = (|| -> Result<()> {
        file.write_all(&bytes)?;
        file.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        drop(file);
        let _ = std::fs::remove_file(path);
    }
    result
}
pub fn read_image(path: &Path) -> Result<Vec<String>> {
    let image = crate::avatar::decode_image(path)?
        .thumbnail(2048, 2048)
        .into_luma8();
    let mut prepared = rqrr::PreparedImage::prepare(image);
    let grids = prepared.detect_grids();
    ensure!(grids.len() <= 8, "too many QR codes in one image");
    let mut values = BTreeSet::new();
    for grid in grids {
        if let Ok((_, value)) = grid.decode() {
            ensure!(
                value.len() <= 128,
                "QR content exceeds identity payload bounds"
            );
            values.insert(value);
        }
    }
    Ok(values.into_iter().collect())
}
/// Monochrome output includes the complete four-module quiet zone.
pub fn terminal(value: &str, ascii: bool) -> Result<String> {
    let qr = symbol(value)?;
    let edge = qr.width() + 8;
    let dark = |x: usize, y: usize| {
        x >= 4 && y >= 4 && x < edge - 4 && y < edge - 4 && qr[(x - 4, y - 4)] == Color::Dark
    };
    let mut out = String::new();
    for y in (0..edge).step_by(if ascii { 1 } else { 2 }) {
        for x in 0..edge {
            if ascii {
                out.push_str(if dark(x, y) { "##" } else { "  " })
            } else {
                out.push(match (dark(x, y), dark(x, y + 1)) {
                    (true, true) => '█',
                    (true, false) => '▀',
                    (false, true) => '▄',
                    _ => ' ',
                });
            }
        }
        out.push('\n');
    }
    Ok(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn qr_round_trip_preserves_full_safety_code_and_never_trusts_another_pair() {
        let dir = tempfile::tempdir().unwrap();
        let a = crate::profile::Profile::open(dir.path().join("a"), None).unwrap();
        let b = crate::profile::Profile::open(dir.path().join("b"), None).unwrap();
        let c = crate::profile::Profile::open(dir.path().join("c"), None).unwrap();
        let value = payload(&a.identity.public, &b.identity.public).unwrap();
        let path = dir.path().join("qr.png");
        export(&value, &path).unwrap();
        assert!(export(&value, &path).is_err());
        assert_eq!(
            read_image(&path).unwrap().as_slice(),
            std::slice::from_ref(&value)
        );
        assert!(matches(&value, &a.identity.public, &b.identity.public).unwrap());
        assert!(!matches(&value, &a.identity.public, &c.identity.public).unwrap());
        for altered in [
            value.to_lowercase(),
            format!("{value}\n"),
            format!("https://example.com/{value}"),
        ] {
            assert!(!matches(&altered, &a.identity.public, &b.identity.public).unwrap());
        }
        assert!(!terminal(&value, true).unwrap().contains('\u{1b}'));
    }
}
