#!/usr/bin/env bash
# Abre o launcher no emulador, passa pelas telas do Eclipse, toca em JOGAR e deixa o jogo abrir.
# Salva prints e logs em screenshots/ (usado pelo workflow "Eclipse Screenshots").
set -u

OUT=screenshots
mkdir -p "$OUT"
APK=$(ls ZalithLauncher/build/outputs/apk/debug/*.apk | head -n 1)
PKG=tech.eclipsecobblemon.launcher.debug
DATA=/sdcard/Android/data/$PKG/files
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

# Toca no centro do elemento com esse texto (primeiro igual; se não houver, o primeiro que contém)
tap_text() {
  dump_ui
  local xy
  xy=$(python3 - "$1" "$UI" <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle, path = sys.argv[1], sys.argv[2]
try:
    nodes = list(ET.parse(path).getroot().iter("node"))
except Exception:
    sys.exit(0)
def labels(node):
    return [node.get("text") or "", node.get("content-desc") or ""]
exact = [n for n in nodes if needle in labels(n)]
partial = [n for n in nodes if any(needle in l for l in labels(n))]
for node in exact + partial:
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

pull_logs() {
  mkdir -p "$OUT/logs"
  adb pull "$DATA/logs" "$OUT/logs/launcher" >/dev/null 2>&1 || true
  adb pull "$DATA/.minecraft/versions/Eclipse Cobblemon/logs" "$OUT/logs/jogo" >/dev/null 2>&1 || true
  adb pull "$DATA/.minecraft/versions/Eclipse Cobblemon/crash-reports" "$OUT/logs/crash-reports" >/dev/null 2>&1 || true
  adb logcat -d -t 20000 > "$OUT/logs/logcat.txt" 2>/dev/null || true
}

log "instalando $APK"
adb install -r -g "$APK"
adb shell cmd locale set-app-locales "$PKG" --locales pt-BR || true
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 1800000 || true
# Sem o aviso "Viewing full screen" do Android por cima do app
adb shell settings put secure immersive_mode_confirmations confirmed || true

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 6
shot 00_abertura

# Primeira abertura: avisos do sistema e a tela que descompacta o Java (botão Instalar)
installed=0
for i in $(seq 1 40); do
  if has_text "Entre para jogar"; then break; fi
  tap_text "Got it" >/dev/null || tap_text "Entendi" >/dev/null || true
  if [ "$installed" -eq 0 ] && { has_text '"Instalar"' || has_text '"Install"'; }; then
    shot 01_primeira_abertura
    tap_text "Instalar" || tap_text "Install"
    installed=1
  fi
  sleep 12
  adb exec-out screencap -p > "$OUT/01_preparando_app_$(printf %02d "$i").png"
done
has_text "Entre para jogar" || log "a tela de login não apareceu"
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

if tap_text "Trocar conta"; then
  sleep 4
  shot 05_contas
  adb shell input keyevent 4
  sleep 3
fi

if tap_text "Configurações" || tap_text "Settings"; then
  sleep 4
  shot 06_configuracoes
  if tap_text "Sobre" || tap_text "About"; then
    sleep 4
    shot 07_sobre
  fi
  adb shell input keyevent 4
  sleep 2
  adb shell input keyevent 4
  sleep 3
fi

has_text "Entre direto em\|JOGAR" || adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 3
if tap_text "JOGAR"; then
  sleep 6
  shot 08_preparando_jogo
  if has_text "dados móveis"; then
    shot 08b_dados_moveis
    tap_text "Continuar"
  fi
  # Instala Minecraft + Fabric, baixa o modpack e abre o jogo: prints a cada 30 s
  for i in $(seq 1 40); do
    sleep 30
    tap_text "Permitir" >/dev/null 2>&1 || true
    adb exec-out screencap -p > "$OUT/09_jogo_$(printf %02d "$i").png"
    if adb shell pidof "$PKG:game" >/dev/null 2>&1; then log "processo do jogo rodando ($i)"; fi
  done
fi

pull_logs
log "fim"
exit 0
