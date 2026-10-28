# Unicode emoji catalog / Unicode 表情目录

The single source catalog is `catalog.tsv.gz`: Unicode emoji-test **16.0**, fully-qualified sequences, with **CLDR 47** annotations. `sources.json` pins every upstream URL and SHA-256. The Unicode license is included in each platform resource directory. No glyph images, fonts, picker runtime dependency or network service is added.

TSV columns: exact sequence, Unicode group, subgroup, base sequence without skin modifiers/VS16 (grouping only), then label/keywords for en, zh-Hans, zh-Hant, ja, ko. Original sequences are never normalized for insertion. Base families expose their existing skin variants; other gender/family sequences remain discoverable entries. Search uses NFKC/case folding and Japanese kana equivalence, exclusively in the index. Three CLDR keywords per language keep release data compact; all 3,781 labels are retained. `supplements.json` supplies reviewed localized Sark names absent from CLDR annotations; its English name comes from CLDR territory CQ.

To update, fetch the files named in `sources.json` into a temporary directory, verify their hashes, then run `python3 tools/generate-emoji.py --sources DIRECTORY`. Update the pinned version, hashes and supplements deliberately when upgrading. The generator creates the two identical platform resources; each package ships only its own copy. `python3 tools/check-emoji.py` verifies offline platform/catalog/license consistency. Run `EmojiCatalogTests` for sequence/search/variant/recents behavior.

Recent entries stay local in existing UI preferences (Android) / profile settings (desktop), at most 30 exact sequences, deduplicated newest first. No history synchronization. Unsupported glyphs use the system fallback and remain labeled/selectable.

共享源为 Unicode 16.0 / CLDR 47，3,781 条完整序列，五语标签和精简搜索词。分类网格、搜索、最近30项、肤色变体共用此数据。简繁/日/韩标签来自CLDR，Sark补充名称单独记录；不会在插入时拆分或规范化序列。更新时按 sources.json 下载并核对，再运行上述生成器；离线检查两端资源与许可一致。不捆绑表情图片/字体，不加运行时依赖、不联网搜索。实际字形取决于系统；Android长按查看变体，桌面选中后点“变体”，双击/Enter/插入按钮可连续插入，不发送消息。
