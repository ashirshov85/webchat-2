#!/usr/bin/env bash
# Media-plane load validation of LiveKit SFU (009-video-conferences T068, SC-008,
# research.md §12; quickstart.md §4 "Медиа-нагрузка").
#
# Runs `lk load-test` (livekit-cli, simulated publishers/subscribers) against a
# dedicated livekit-server constrained to the dev-stand budget of
# deploy/k8s/base/livekit.yaml (limits cpu 1500m / memory 1Gi; ROADMAP:
# VPS 4 vCPU / 6 ГБ, cpu ~1500m–2 / mem 1–1.5Gi) and records:
#   - client-side: per-tester bitrates/packet loss from lk summaries
#   - server-side: Prometheus metrics of livekit-server
#     (livekit_session_join_latency_ms, livekit_packet_loss_percent,
#     livekit_rtt_ms, livekit_jitter_us, participants/rooms/track counters)
#   - container CPU/mem of the SFU and of the generator fleet
#
# Profiles (stand capacity target ~10 simultaneous 1:1 / ~2 group, ROADMAP):
#   B "1v1-audio":  10 rooms x (2 audio pubs + 2 subs)        — capacity baseline
#   C "1v1-video":  ramp 2->4->6->8->10 rooms x (2 av pubs
#                   720p vp8 simulcast + 2 subs, speaker)     — capacity limit
#   D "group":      2 rooms x (4 av + 4 audio pubs + 8 subs,
#                   3x3 layout)                               — group budget
#
# Results (summaries, CSV samples, Prometheus snapshots) land in
# load/lk/results/<run-ts>/ ; the analyzed report is load/REPORT.md.
#
# By default the script spins up its own constrained SFU. To re-run against the
# actual dev stand (or any live instance), export:
#   LOADTEST_TARGET_URL=http://<host>:7880
#   LOADTEST_TARGET_KEY=... LOADTEST_TARGET_SECRET=...
# — generators will point there, local SFU/stats collection is skipped.
#
# Usage: bash load/lk/media-loadtest.sh [phase...]   # phases: B C D (default all)
set -euo pipefail

LIVEKIT_IMAGE=livekit/livekit-server:v1.13.10   # pinned per deploy/k8s/overlays/dev
CLI_IMAGE=livekit/livekit-cli:latest            # lk 2.19.0 (load-test)
SFU=lk-sfu
NET=lk-loadtest
SFU_CPUS=1.5                                    # dev k8s limit (base manifest)
SFU_MEM=1g
RESULTS="load/lk/results/$(date -u +%Y%m%dT%H%M%SZ)"
METRICS_URL=http://localhost:19780/metrics
SAMPLE_INTERVAL=5
if (( $# == 0 )); then PHASES=(B C D); else PHASES=("$@"); fi

mkdir -p "$RESULTS"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# ---------------------------------------------------------------- infrastructure
ensure_net() { docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET"; }

ensure_sfu() {
  if docker inspect "$SFU" >/dev/null 2>&1 && [[ "$(docker inspect -f '{{.State.Status}}' "$SFU")" == running ]]; then
    return
  fi
  docker rm -f "$SFU" >/dev/null 2>&1 || true
  # --dev single node (media plane identical to the single dev pod, research §11);
  # no webhooks: media-plane-only isolation, control plane covered by k6 smoke (T058/T067)
  docker run -d --name "$SFU" --network "$NET" --cpus "$SFU_CPUS" --memory "$SFU_MEM" \
    -p 19780:9780 \
    -e LIVEKIT_CONFIG='
port: 7880
bind_addresses:
  - 0.0.0.0
keys:
  devkey: secret
room:
  enable_remote_unmute: true
rtc:
  tcp_port: 7881
  udp_port: 7882
prometheus:
  port: 9780
logging:
  level: info
' \
    "$LIVEKIT_IMAGE" --dev >/dev/null
  for _ in $(seq 1 20); do
    docker exec "$SFU" wget -qO- http://127.0.0.1:7880/ >/dev/null 2>&1 && { log "SFU up"; return; }
    sleep 1
  done
  echo "SFU failed to start" >&2; exit 1
}

prom_snapshot() { # prom_snapshot <name>
  curl -s "$METRICS_URL" >"$RESULTS/prom-$1.txt" || :   # absent in external-target mode
}

# cumulative single-value metric delta between two snapshots
prom_value() { # prom_value <file> <metric-name-prefix>
  grep "^$2" "$1" | grep -v '^#' | awk '{s+=$NF} END {print s+0}'
}

start_sampler() { # sampler writes CSV rows every SAMPLE_INTERVAL
  : >"$RESULTS/samples.csv"
  echo 'ts,phase,sfu_cpu_perc,sfu_mem_mib,gen_cpu_perc,participants,rooms' >>"$RESULTS/samples.csv"
  : >"$RESULTS/.phase"
  (
    set +e   # robust to transient docker stats failures on vanishing containers
    while :; do
      ts=$(date -u +%FT%TZ)
      SAMPLER_PHASE=$(cat "$RESULTS/.phase" 2>/dev/null || echo '')
      sfu=$(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$SFU" 2>/dev/null | \
            awk '{cpu=$1; sub(/%/,"",cpu); m=$2; sub(/MiB/,"",m); print cpu","m}')
      gen=0
      for c in $(docker ps --format '{{.Names}}' | grep '^lk-gen-'); do
        g=$(docker stats --no-stream --format '{{.CPUPerc}}' "$c" 2>/dev/null | tr -d '%')
        gen=$(awk -v a="$gen" -v b="${g:-0}" 'BEGIN{print a+b}')
      done
      met=$(curl -s "$METRICS_URL")
      part=$(echo "$met" | grep '^livekit_participant_total' | grep -v '^#' | awk '{s+=$NF} END {print s+0}')
      rooms=$(echo "$met" | grep '^livekit_room_total' | grep -v '^#' | awk '{s+=$NF} END {print s+0}')
      echo "$ts,$SAMPLER_PHASE,${sfu:-,},$gen,$part,$rooms" >>"$RESULTS/samples.csv"
      sleep "$SAMPLE_INTERVAL"
    done
  ) & SAMPLER_PID=$!
}

stop_sampler() { kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; }

# ---------------------------------------------------------------- load generators
# one lk container per room; lk load-test models exactly one room per invocation
gen() { # gen <phase> <idx> <duration> <room> <audio-pubs> <video-pubs> <subs> <layout>
  local phase=$1 idx=$2 duration=$3 room=$4 apubs=$5 vpubs=$6 subs=$7 layout=$8
  local name="lk-gen-$phase-$idx"
  local url=${LOADTEST_TARGET_URL:-http://lk-sfu:7880}
  local key=${LOADTEST_TARGET_KEY:-devkey} secret=${LOADTEST_TARGET_SECRET:-secret}
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --network "$NET" "$CLI_IMAGE" \
    --url "$url" --api-key "$key" --api-secret "$secret" \
    load-test --room "$room" --duration "$duration" \
    --audio-publishers "$apubs" --video-publishers "$vpubs" \
    --video-resolution high --video-codec vp8 \
    --subscribers "$subs" --layout "$layout" \
    >"$RESULTS/gen-$phase-$idx.docker" 2>&1
}

wait_gens() { # wait_gens <phase> — block until phase containers exit, save summaries
  local phase=$1
  local names
  names=$(docker ps -a --format '{{.Names}}' | grep "^lk-gen-$phase-" | sort || true)
  for n in $names; do
    docker wait "$n" >/dev/null
    out="$RESULTS/${n//lk-gen-/summary-}.log"
    k=2
    while [[ -e "$out" ]]; do out="$RESULTS/${n//lk-gen-/summary-}.$k.log"; ((k++)); done
    docker logs "$n" >"$out" 2>&1
    docker rm "$n" >/dev/null
  done
}

drain() { # wait until all testers left the rooms
  for _ in $(seq 1 60); do
    local p
    p=$(curl -s "$METRICS_URL" | grep '^livekit_participant_total' | grep -v '^#' | awk '{s+=$NF} END {print s+0}')
    [[ "$p" == 0 ]] && return 0
    sleep 2
  done
  log "WARN: drain timeout, participants left: $p"
}

phase_header() { # phase_header <phase> <label> — pin phase start in samples
  echo "$1" >"$RESULTS/.phase"
  prom_snapshot "$1-start"
  local b_part b_rooms
  b_part=$(prom_value "$RESULTS/prom-$1-start.txt" livekit_participant_total)
  b_rooms=$(prom_value "$RESULTS/prom-$1-start.txt" livekit_room_total)
  log "=== phase $1 ($2) start: cumulative rooms=$b_rooms participants=$b_part"
}

phase_footer() { # phase_footer <phase>
  prom_snapshot "$1-end"
  drain
  log "=== phase $1 end"
}

# ---------------------------------------------------------------- phases
phase_B() { # 10 x 1:1 audio-only
  phase_header B "10 rooms 1:1 audio (2 audio pubs + 2 subs each)"
  for i in $(seq 0 9); do gen B "$i" 180s "lt-b-1v1-audio-$i" 2 0 2 speaker; done
  wait_gens B
  phase_footer B
}

phase_C() { # ramp to 10 x 1:1 video 720p vp8 simulcast; each step is an isolated
  # measurement: n fresh rooms, a clean steady window, then full teardown
  local n i c
  phase_header C "1:1 video ramp 2->10 rooms (2 av pubs 720p vp8 simulcast + 2 subs)"
  for n in 2 4 6 8 10; do
    for i in $(seq 0 $((n-1))); do
      gen "C$n" "$i" 150s "lt-c${n}-1v1-video-$i" 2 2 2 speaker
    done
    echo "C@${n}rooms" >"$RESULTS/.phase"
    log "  C: holding $n rooms (step $n)"
    sleep 150
    for i in $(seq 0 $((n-1))); do
      c="lk-gen-C$n-$i"
      docker wait "$c" >/dev/null 2>&1 || true
      docker logs "$c" >"$RESULTS/summary-C$n-$i.log" 2>&1 || true
      docker rm "$c" >/dev/null 2>&1 || true
    done
    prom_snapshot "C-${n}rooms-end"
    drain
  done
  phase_footer C
}

phase_D() { # 2 x group rooms (16 participants, 3x3)
  phase_header D "2 group rooms (4 av pubs + 4 audio pubs + 8 subs, 3x3)"
  for i in 0 1; do gen D "$i" 180s "lt-d-group-$i" 4 4 8 3x3; done
  wait_gens D
  phase_footer D
}

# ---------------------------------------------------------------- main
ensure_net
if [[ -n "${LOADTEST_TARGET_URL:-}" ]]; then
  log "external target mode: generators -> $LOADTEST_TARGET_URL (no local SFU/stats)"
else
  ensure_sfu
fi
prom_snapshot 00-idle
start_sampler
trap stop_sampler EXIT

for p in "${PHASES[@]}"; do
  case "$p" in
    B) phase_B ;;
    C) phase_C ;;
    D) phase_D ;;
    *) echo "unknown phase $p (use B C D)" >&2; exit 1 ;;
  esac
done

prom_snapshot 99-final
log "done: results in $RESULTS"
