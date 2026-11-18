#!/usr/bin/env bash
# Refresh shared/ from the Android app sources (run from the app repo checkout path given as $1).
set -euo pipefail
APP="${1:?path to rates-app}"; cd "$(dirname "$0")/.."
for d in parser content market; do rm -rf shared/$d; mkdir -p shared/$d; cp "$APP"/app/src/main/java/com/djphoenix/moasherlibya/$d/*.kt shared/$d/; done
rm -f shared/content/ContentRepo.kt shared/market/MarketsApi.kt shared/market/CryptoLive.kt
# keep source names out of the public repo: the news scrub patterns are read from SOURCES_JSON at runtime
python3 - <<'PY'
p='shared/content/ContentParsers.kt'; s=open(p).read()
a=s.index('    private val sourceRe = Regex('); b=s.index('\n', s.index('    private val datelineRe = Regex('))
s=s[:a]+'''    // server copy: the source-specific patterns come from the SOURCES_JSON secret (tools/sync_shared.sh applies this patch)
    private val sourceRe = Regex(tools.Src.s.newsScrub.ifEmpty { "(?i)(https?://\\\\S+|www\\\\.\\\\S+)" })
    private val datelineRe = Regex(tools.Src.s.newsDateline.ifEmpty { "(?!)" })'''+s[b:]
open(p,'w').write(s)
PY
