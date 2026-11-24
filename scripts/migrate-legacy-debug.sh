#!/usr/bin/env bash
# Both package data directories stay on the selected device.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo 'Usage: scripts/migrate-legacy-debug.sh <adb-device> [com.siaovon.khub|com.live.tv]' >&2
    exit 2
fi
adb_device=("${ADB:-adb}" -s "$1")
legacy=${2:-com.siaovon.khub}
case "$legacy" in
    com.siaovon.khub|com.live.tv) ;;
    *) echo 'Unsupported migration source package' >&2; exit 2 ;;
esac
target=com.siaovon.kanah
stage=khub-migration
runner=com.live.tv.media.KHubMigrationRunner

run_helper() {
    local output
    output=$("${adb_device[@]}" shell am instrument -w -r -e operation "$2" "$1.test/$runner")
    printf '%s\n' "$output"
    [[ "$output" == *"migration=ok"* ]]
}

"${adb_device[@]}" shell "run-as $legacy id >/dev/null && run-as $target id >/dev/null"
"${adb_device[@]}" shell "run-as $legacy test ! -e $stage && run-as $target test ! -e $stage"
"${adb_device[@]}" shell "am force-stop $legacy; am force-stop $target"
run_helper "$legacy" export
trap '"${adb_device[@]}" shell "run-as $legacy rm -f $stage/sources.json; run-as $legacy rmdir $stage 2>/dev/null; run-as $target rm -f $stage/sources.json; true"' EXIT
"${adb_device[@]}" shell "am force-stop $legacy; run-as $target mkdir $stage"

paths=(
    files/iptv-cache.m3u files/vod-episode-cache files/music-cache-v1
    cache/vod-frame-cache cache/live-last.jpg
    databases/exoplayer_internal.db databases/exoplayer_internal.db-journal databases/exoplayer_internal.db-wal
    shared_prefs/iptv.xml shared_prefs/MainActivity.xml shared_prefs/app-session.xml
    shared_prefs/media-progress.xml shared_prefs/music-playback.xml
    shared_prefs/vod-frame-index.xml shared_prefs/vod-session.xml shared_prefs/vod-recent.xml
)
existing=()
for path in "${paths[@]}"; do
    if "${adb_device[@]}" shell "run-as $legacy test -e $path"; then
        "${adb_device[@]}" shell "run-as $target test ! -e $path"
        existing+=("$path")
    fi
done
if ((${#existing[@]})); then
    "${adb_device[@]}" shell "set -o pipefail; run-as $legacy tar -cf - ${existing[*]} | run-as $target tar -xf - -C $stage"
    # Compare all payload bytes without transferring media or filenames to this computer.
    "${adb_device[@]}" shell "
        set -o pipefail
        hash_data() {
            pkg=\$1; dir=\$2
            run-as \"\$pkg\" sh -c \"set -o pipefail; cd \$dir && find ${existing[*]} -type f -print0 | sort -z | xargs -0 sha256sum\" | sha256sum
        }
        old=\$(hash_data $legacy .) && new=\$(hash_data $target $stage) && test \"\$old\" = \"\$new\"
    "
fi
if "${adb_device[@]}" shell "run-as $legacy test -f $stage/sources.json"; then
    "${adb_device[@]}" shell "set -o pipefail; run-as $legacy cat $stage/sources.json | run-as $target sh -c 'umask 077; cat > $stage/sources.json'"
fi
run_helper "$target" import
run_helper "$target" verify-cache
"${adb_device[@]}" shell "run-as $target rm -r $stage"
