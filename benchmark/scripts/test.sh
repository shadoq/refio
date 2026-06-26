#!/usr/bin/env bash
# Pomiary KV cache na Ollama (Docker) - macierz model x ctx x KV quant.
# Uruchamiane LOKALNIE na serwerze z Dockerem + Ollama + GPU.
#
# Wymagania: docker, nvidia-smi, curl, jq, bc

set -euo pipefail

# ============ KONFIGURACJA ============
OLLAMA_URL="http://127.0.0.1:11434"

# Lista modeli "kandydatów". Skrypt zmierzy tylko te, ktore sa zainstalowane
# (czesc wspolna z /api/tags). Bielik dopisany - jesli na serwerze jest pod
# inna nazwa (np. bielik:11b albo SpeakLeash/...), zostanie wykryty heurystyka.
TARGET_MODELS=(
  "llama3.1:8b"
  "qwen2.5:32b"
  "qwen3:32b"
  "qwen3.5:27b"
  "qwen3.5:35b"
  "qwen3.5:122b"
  "qwen3.6:27b"
  "qwen3.6:35b"
  "gemma4:26b"
  "gemma4:31b"
  "deepseek-r1:32b"
)

CTX_SIZES=(8192 32768 131072)
KV_TYPES=("f16" "q8_0" "q4_0")

NUM_PREDICT=128            # ile tokenow wygenerowac per pomiar (wplywa na eval_duration)
WAIT_API_READY=60          # max sek czekania na gotowosc API po restarcie

OUT_CSV="${1:-kv-cache-$(date +%Y%m%d-%H%M).csv}"
PROMPT_FILE="/tmp/kv-bench-prompt.txt"
INSPECT_FILE="/tmp/kv-bench-container.json"

# Czy docker wymaga sudo? Auto-detekcja.
if docker ps >/dev/null 2>&1; then
  DOCKER="docker"
else
  DOCKER="sudo docker"
fi

# ============ POMOCNICZE ============

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

# Generuj dlugi prompt deterministycznie (yes + head). ~600K znakow wystarczy
# na ~130K tokenow w mieszanej angielskiej/polskiej tokenizacji.
generate_prompt() {
  [[ -f "$PROMPT_FILE" ]] && return
  log "Generuje dlugi prompt do $PROMPT_FILE"
  # yes dostaje SIGPIPE od head - wylacz pipefail tylko dla tej linii
  set +o pipefail
  yes "kontekst model token cache pamiec Refio JVM Kotlin agent prompt lokalny kwantyzacja attention layer transformer decoder encoder inference benchmark workflow. " 2>/dev/null \
    | head -c 600000 > "$PROMPT_FILE"
  set -o pipefail
}

detect_container() {
  $DOCKER ps --format '{{.Names}}\t{{.Image}}' \
    | awk -F'\t' '$2 ~ /ollama/ {print $1; exit}'
}

# Pelny snapshot konfiguracji - uzyty do odtworzenia kontenera z nowym env.
capture_container_config() {
  local name="$1"
  $DOCKER inspect "$name" > "$INSPECT_FILE"

  IMAGE=$(jq -r '.[0].Config.Image' "$INSPECT_FILE")
  RESTART_POLICY=$(jq -r '.[0].HostConfig.RestartPolicy.Name // "no"' "$INSPECT_FILE")
  NETWORK_MODE=$(jq -r '.[0].HostConfig.NetworkMode // "default"' "$INSPECT_FILE")

  MOUNT_ARGS=$(jq -r '
    .[0].Mounts[] |
    if .Type == "volume" then "-v \(.Name):\(.Destination)"
    else "-v \(.Source):\(.Destination)" end' "$INSPECT_FILE" | tr '\n' ' ')

  PORT_ARGS=$(jq -r '
    .[0].HostConfig.PortBindings // {} | to_entries[] as $e |
    ($e.key | split("/")[0]) as $cport |
    $e.value[] | "-p \(.HostIp // "0.0.0.0"):\(.HostPort):\($cport)"' "$INSPECT_FILE" | tr '\n' ' ')

  GPU_ARGS=""
  if jq -e '.[0].HostConfig.DeviceRequests // [] | length > 0' "$INSPECT_FILE" >/dev/null; then
    GPU_ARGS="--gpus=all"
  fi

  # Oryginalne ENV - zapisz, zeby przywrocic przy cleanup.
  ORIG_ENV=$(jq -r '.[0].Config.Env[]?' "$INSPECT_FILE" \
    | grep -E '^OLLAMA_' || true)

  log "  image=$IMAGE"
  log "  restart=$RESTART_POLICY network=$NETWORK_MODE"
  log "  mounts: $MOUNT_ARGS"
  log "  ports: $PORT_ARGS"
  log "  gpu: ${GPU_ARGS:-<brak>}"
}

# Odtworz kontener z konkretnymi env vars.
recreate_container() {
  local kv_type="$1"  # f16 | q8_0 | q4_0 | original
  local env_args=""

  if [[ "$kv_type" == "original" ]]; then
    # Przywroc oryginalne env vars
    while IFS= read -r line; do
      [[ -n "$line" ]] && env_args="$env_args -e '$line'"
    done <<< "$ORIG_ENV"
    log "Restart kontenera z ORYGINALNYM env (cleanup)"
  else
    env_args="-e OLLAMA_FLASH_ATTENTION=1 -e OLLAMA_KV_CACHE_TYPE=$kv_type"
    log "Restart kontenera z OLLAMA_KV_CACHE_TYPE=$kv_type"
  fi

  $DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true

  # eval - bo MOUNT_ARGS/PORT_ARGS/env_args zawieraja spacje i cytaty
  eval "$DOCKER run -d --name '$CONTAINER' \
    --restart='$RESTART_POLICY' \
    --network='$NETWORK_MODE' \
    $GPU_ARGS $MOUNT_ARGS $PORT_ARGS $env_args \
    '$IMAGE' >/dev/null" || { log "BLAD: docker run nie powiodl sie"; return 1; }

  # Czekaj na API
  local waited=0
  while (( waited < WAIT_API_READY )); do
    if curl -sf "$OLLAMA_URL/api/tags" >/dev/null 2>&1; then
      log "API gotowe po ${waited}s"
      sleep 2
      return 0
    fi
    sleep 2
    waited=$(( waited + 2 ))
  done
  log "BLAD: API nie odpowiada po ${WAIT_API_READY}s"
  return 1
}

# Cleanup - przywroc oryginalne env przy wyjsciu
cleanup() {
  log ""
  log "=== CLEANUP: odtwarzam kontener z oryginalnym env ==="
  recreate_container "original" || log "UWAGA: cleanup nie powiodl sie - sprawdz kontener recznie"
}
trap cleanup EXIT

read_vram_mib() {
  # DGX Spark (Grace-Blackwell GB10) = unified memory.
  # nvidia-smi zwraca 0, bo nie ma osobnego VRAM. Mierzymy pamiec systemowa
  # z /proc/meminfo: used = MemTotal - MemAvailable, w MiB.
  awk '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {printf "%d\n", (t-a)/1024}' /proc/meminfo
}

verify_kv_type_in_logs() {
  local expected="$1"
  local found
  found=$($DOCKER logs "$CONTAINER" 2>&1 | grep -E 'type_k|type_v|kv.cache' | tail -3 || true)
  if [[ -n "$found" ]]; then
    log "  logi: $(echo "$found" | head -1)"
  fi
}

get_installed_models() {
  curl -sf "$OLLAMA_URL/api/tags" | jq -r '.models[].name'
}

unload_model() {
  local model="$1"
  curl -s "$OLLAMA_URL/api/generate" \
    -d "{\"model\":\"$model\",\"keep_alive\":0}" >/dev/null || true
  sleep 3
}

# Zwolnij WSZYSTKIE zaladowane modele (do czystego baseline VRAM przed pomiarem).
unload_all() {
  local loaded
  loaded=$(curl -s "$OLLAMA_URL/api/ps" 2>/dev/null | jq -r '.models[]?.name' 2>/dev/null || true)
  if [[ -n "$loaded" ]]; then
    while IFS= read -r m; do
      [[ -z "$m" ]] && continue
      curl -s "$OLLAMA_URL/api/generate" \
        -d "{\"model\":\"$m\",\"keep_alive\":0}" >/dev/null || true
    done <<< "$loaded"
    sleep 5
  fi
}

# Jeden pomiar: baseline + warm-up + dlugi prompt + odczyt VRAM + parsing JSON.
# Ollama alokuje KV cache w momencie load modelu (na podstawie num_ctx), nie
# podczas inference. Dlatego mierzymy: baseline PRZED load -> loaded PO load
# -> full PO realnym prompcie. Czysty KV cache + wagi = loaded - baseline.
measure_one() {
  local model="$1" ctx="$2"

  # Czysty baseline: zwolnij wszystkie modele, poczekaj, odczytaj VRAM
  unload_all
  local vram_baseline
  vram_baseline=$(read_vram_mib)

  # Warm-up - zaladuj model z wlasciwym num_ctx (alokuje pelen KV buffer)
  curl -sf "$OLLAMA_URL/api/generate" -d @- <<EOF >/dev/null
{"model":"$model","prompt":"warmup","options":{"num_ctx":$ctx,"num_predict":8},"stream":false,"keep_alive":"15m"}
EOF
  sleep 2

  local vram_loaded
  vram_loaded=$(read_vram_mib)

  # Dlugi prompt - przyblizenie: ctx * 3.5 znaka
  local chars=$(( ctx * 4 ))
  (( chars > 600000 )) && chars=600000

  local resp
  resp=$(jq -nc --arg model "$model" \
    --rawfile prompt <(head -c "$chars" "$PROMPT_FILE") \
    --argjson ctx "$ctx" --argjson predict "$NUM_PREDICT" \
    '{model:$model, prompt:$prompt, options:{num_ctx:$ctx,num_predict:$predict}, stream:false, keep_alive:"15m"}' \
    | curl -sf "$OLLAMA_URL/api/generate" --max-time 600 -d @-)

  if [[ -z "$resp" ]]; then
    echo "ERR" && return 1
  fi

  local vram_full
  vram_full=$(read_vram_mib)

  local pec ec ped ed td
  pec=$(echo "$resp" | jq -r '.prompt_eval_count // 0')
  ec=$(echo "$resp" | jq -r '.eval_count // 0')
  ped=$(echo "$resp" | jq -r '.prompt_eval_duration // 0')
  ed=$(echo "$resp" | jq -r '.eval_duration // 0')
  td=$(echo "$resp" | jq -r '.total_duration // 0')

  local tps="0"
  [[ "$ed" -gt 0 ]] && tps=$(echo "scale=2; $ec * 1000000000 / $ed" | bc -l)

  local prompt_tps="0"
  [[ "$ped" -gt 0 ]] && prompt_tps=$(echo "scale=2; $pec * 1000000000 / $ped" | bc -l)

  # model_plus_kv = wagi modelu + KV cache dla num_ctx (czysty pomiar)
  local model_plus_kv=$(( vram_loaded - vram_baseline ))
  # inference_delta = dodatkowa alokacja przy realnym prompcie (powinno byc ~0)
  local inference_delta=$(( vram_full - vram_loaded ))

  echo "$model,$ctx,$KV,$pec,$ec,$vram_baseline,$vram_loaded,$vram_full,$model_plus_kv,$inference_delta,$prompt_tps,$tps,$td"
}

# ============ MAIN ============

log "=== KV cache benchmark - Ollama Docker (local) ==="

for tool in curl jq bc nvidia-smi awk; do
  command -v "$tool" >/dev/null || { echo "BLAD: brakuje: $tool" >&2; exit 1; }
done
$DOCKER ps >/dev/null 2>&1 || { echo "BLAD: docker nie dziala (sprawdz uprawnienia / sudo)" >&2; exit 1; }
log "Docker: $DOCKER"

log "Wykrywam kontener Ollama"
CONTAINER=$(detect_container)
[[ -z "$CONTAINER" ]] && { echo "BLAD: nie znaleziono kontenera z obrazem ollama" >&2; exit 1; }
log "Kontener: $CONTAINER"

log "Zapisuje aktualna konfiguracje kontenera"
capture_container_config "$CONTAINER"

generate_prompt

log "Sprawdzam zainstalowane modele"
INSTALLED=$(get_installed_models)
log "Zainstalowane:"
echo "$INSTALLED" | sed 's/^/    /' >&2

MODELS=()
for m in "${TARGET_MODELS[@]}"; do
  if echo "$INSTALLED" | grep -qx "$m"; then
    MODELS+=("$m")
  fi
done

# Heurystyka: dorzuc Bielika lub inne PL modele jesli sa pod inna nazwa
while IFS= read -r m; do
  if [[ "$m" =~ [Bb]ielik|[Pp]LLuM|[Ss]peak[Ll]eash ]]; then
    if [[ ! " ${MODELS[*]} " =~ " $m " ]]; then
      MODELS+=("$m")
      log "  + dodaje $m (heurystyka PL)"
    fi
  fi
done <<< "$INSTALLED"

if [[ ${#MODELS[@]} -eq 0 ]]; then
  echo "BLAD: zaden z TARGET_MODELS nie jest zainstalowany. Dostepne:" >&2
  echo "$INSTALLED" | sed 's/^/  /' >&2
  exit 1
fi
log "Modele do pomiaru (${#MODELS[@]}): ${MODELS[*]}"

# Naglowek CSV
# vram_baseline = pamiec systemowa po unload wszystkich modeli (przed load)
# vram_loaded   = po warm-up (model + pelen KV buffer dla num_ctx)
# vram_full     = po realnym prompcie
# model_plus_kv = vram_loaded - vram_baseline = WAGI + KV cache (kluczowa kolumna)
# inference_delta = vram_full - vram_loaded (powinno byc ~0)
echo "model,num_ctx,kv_cache_type,prompt_eval_count,eval_count,vram_baseline_MiB,vram_loaded_MiB,vram_full_MiB,model_plus_kv_MiB,inference_delta_MiB,prompt_tokens_per_sec,gen_tokens_per_sec,total_duration_ns" > "$OUT_CSV"

TOTAL=$(( ${#MODELS[@]} * ${#CTX_SIZES[@]} * ${#KV_TYPES[@]} ))
I=0
START_TS=$(date +%s)

# Petla zewn: KV (restart raz per KV - oszczedza ~10x czas vs restart per iteracja)
for KV in "${KV_TYPES[@]}"; do
  log ""
  log "=== KV CACHE TYPE: $KV ==="
  if ! recreate_container "$KV"; then
    log "Pomijam caly KV=$KV"
    continue
  fi
  verify_kv_type_in_logs "$KV"

  for MODEL in "${MODELS[@]}"; do
    for CTX in "${CTX_SIZES[@]}"; do
      I=$(( I + 1 ))
      ELAPSED=$(( $(date +%s) - START_TS ))
      log "[$I/$TOTAL] (${ELAPSED}s) model=$MODEL ctx=$CTX kv=$KV"

      if line=$(measure_one "$MODEL" "$CTX"); then
        echo "$line" | tee -a "$OUT_CSV"
      else
        log "  BLAD pomiaru - pomijam"
        echo "$MODEL,$CTX,$KV,ERR,ERR,ERR,ERR,ERR,ERR,ERR,ERR,ERR,ERR" >> "$OUT_CSV"
      fi

      unload_model "$MODEL"
    done
  done
done

log ""
log "=== GOTOWE ==="
log "Wyniki: $OUT_CSV"
log "Czas: $(( $(date +%s) - START_TS ))s"

# cleanup() trap odtworzy oryginalny kontener
