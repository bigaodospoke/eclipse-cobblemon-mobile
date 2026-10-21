#!/usr/bin/env bash
# Abre o launcher no emulador, passa pelas telas do Eclipse e salva prints em screenshots/
# (usado pelo workflow "Eclipse Screenshots")
set -u

OUT=screenshots
mkdir -p "$OUT"
APK=$(ls ZalithLauncher/build/outputs/apk/debug/*.apk | head -n 1)
PKG=tech.eclipsecobblemon.launcher.debug
UI=/tmp/ui.xml

log() { echo "[screenshots] $*"; }

dump_ui() {
  for _ in 1 2 3; do
    if adb shell uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1; then
      adb pull /sdcard/ui.xml "$UI" >/dev/null 2>&1 && return 0
    fi
    sleep 2
  done
  echo "<hierarchy/>" > "$UI"
  return 1
}

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  dump_ui && cp "$UI" "$OUT/$1.xml"
  log "print $1"
}

has_text() {
  dump_ui
  grep -q "$1" "$UI"
}

# Toca no centro do primeiro elemento cujo texto ou descrição contém $1
tap_text() {
  dump_ui
  local xy
  xy=$(python3 - "$1" "$UI" <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for node in root.iter("node"):
    label = (node.get("text") or "") + " " + (node.get("content-desc") or "")
    if needle in label:
        nums = re.findall(r"\d+", node.get("bounds", ""))
        if len(nums) == 4:
            x1, y1, x2, y2 = map(int, nums)
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
  if [ -n "$xy" ]; then
    log "toque em '$1' ($xy)"
    adb shell input tap $xy
    return 0
  fi
  log "não achei '$1'"
  return 1
}

# Espera um texto aparecer, tirando prints no caminho. $1 texto, $2 segundos, $3 prefixo
wait_text() {
  local waited=0
  while [ "$waited" -lt "$2" ]; do
    if has_text "$1"; then return 0; fi
    sleep 15
    waited=$((waited + 15))
    adb exec-out screencap -p > "$OUT/$3_$(printf %03d "$waited")s.png"
  done
  return 1
}

log "instalando $APK"
adb install -r -g "$APK"
adb shell cmd locale set-app-locales "$PKG" --locales pt-BR || true
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 1800000 || true

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 6
shot 00_abertura

wait_text "Entre para jogar" 480 01_preparando_app || log "a tela de login não apareceu"
sleep 8 # status do servidor e notícias
shot 02_tela_inicial_login

if tap_text "Seu nick"; then
  sleep 2
  adb shell input text EclipseTeste
  sleep 1
  adb shell input keyevent 4 # fecha o teclado
  sleep 2
  shot 03_nick_digitado
  tap_text "Jogar com nick"
  sleep 5
fi
shot 04_tela_inicial_logado

if tap_text "JOGAR"; then
  sleep 6
  shot 05_jogar
  if has_text "dados móveis"; then
    shot 05b_dados_moveis
    tap_text "Continuar"
    sleep 5
  fi
  # Pedido do sistema para rodar em segundo plano, se aparecer
  tap_text "Permitir" >/dev/null || tap_text "Allow" >/dev/null || true
  sleep 40
  shot 06_baixando
  tap_text "Cancelar" || tap_text "Cancel" || adb shell input keyevent 4
  sleep 4
fi

if tap_text "Trocar conta"; then
  sleep 4
  shot 07_contas
  adb shell input keyevent 4
  sleep 3
fi

if tap_text "Configurações" || tap_text "Settings"; then
  sleep 4
  shot 08_configuracoes
  if tap_text "Sobre" || tap_text "About"; then
    sleep 4
    shot 09_sobre
  fi
fi

adb logcat -d -t 8000 > "$OUT/logcat.txt" 2>/dev/null || true
log "fim"
exit 0
