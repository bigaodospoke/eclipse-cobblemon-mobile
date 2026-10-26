#!/usr/bin/env node
/*
 * Converte um layout de controles do PojavLauncher (formato v7: mControlDataList,
 * mDrawerDataList, mJoystickDataList) para o formato de controles deste launcher
 * (layers/styles, editorVersion 12).
 *
 * Uso:
 *   node ferramentas/converter-controles-pojav.mjs controles/Controles_Eclipse.json \
 *        ZalithLauncher/src/main/assets/eclipse_layout.json --versao 1
 *
 * --versao: aumente a cada layout novo; o app instala e seleciona o layout de novo
 * quando esse número cresce (os jogadores que trocaram de layout recebem a versão nova uma vez).
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const [entrada, saida] = args.filter((a, i) => !a.startsWith("--") && !(args[i - 1] || "").startsWith("--"));
const versao = Number(args[args.indexOf("--versao") + 1] || 1);
if (!entrada || !saida) {
  console.error("Uso: node converter-controles-pojav.mjs <pojav.json> <saida.json> [--versao N]");
  process.exit(1);
}

/* Tela de referência para calcular as posições (celular comum deitado, ~420 dpi).
 * Os tamanhos ficam em dp, como no Pojav; só os deslocamentos em dp das posições dependem disso. */
const TELA_L = 2400;
const TELA_A = 1080;
const DENSIDADE = 2.625;

/* ---------- nomes das teclas (GLFW) aceitos pelo launcher ---------- */
const keycodes = path.join(raiz, "InputMap/src/main/java/com/movtery/inputmap/keycodes");
const codigoParaNome = new Map();
for (const [, nome, codigo] of fs.readFileSync(path.join(keycodes, "LwjglGlfwKeycode.java"), "utf8")
  .matchAll(/\b(GLFW_KEY_[A-Z0-9_]+)\s*=\s*(\d+)/g)) {
  if (!codigoParaNome.has(Number(codigo))) codigoParaNome.set(Number(codigo), nome);
}
const nomesAceitos = new Set(
  [...fs.readFileSync(path.join(keycodes, "ControlEventKeycode.java"), "utf8")
    .matchAll(/String\s+(GLFW_KEY_[A-Z0-9_]+)\s*=/g)].map((m) => m[1])
);

/* Teclas especiais do Pojav (códigos negativos) */
const ESPECIAIS = {
  "-1": { type: "launcher_event", key: "launcher.event.switch_ime" },      // teclado
  "-2": "TOGGLE_CONTROLES",                                                 // esconder controles (GUI)
  "-3": { type: "launcher_event", key: "GLFW_MOUSE_BUTTON_LEFT" },
  "-4": { type: "launcher_event", key: "GLFW_MOUSE_BUTTON_RIGHT" },
  "-5": "MOUSE_VIRTUAL",                                                    // o launcher troca o mouse sozinho
  "-6": { type: "launcher_event", key: "GLFW_MOUSE_BUTTON_MIDDLE" },
  "-7": { type: "launcher_event", key: "launcher.event.scroll_up.single" },
  "-8": { type: "launcher_event", key: "launcher.event.scroll_down.single" },
  "-9": { type: "launcher_event", key: "launcher.event.switch_menu" },
};

const avisos = [];
const id = (semente, tamanho) => crypto.createHash("sha1").update(semente).digest("hex").slice(0, tamanho);
const texto = (t) => ({ default: t, matchQueue: [] });
const semSinal = (cor) => (Number(cor) >>> 0);
const limitar = (v, min, max) => Math.min(max, Math.max(min, v));

/* ---------- posição: avalia as expressões ${...} do Pojav ---------- */
function avaliar(expr, vars) {
  const js = String(expr).replace(/\$\{(\w+)\}/g, (_, k) => {
    if (!(k in vars)) throw new Error(`variável desconhecida \${${k}} em ${expr}`);
    return `(${vars[k]})`;
  });
  if (/[^0-9+\-*/(). pxe\s]/.test(js.replace(/px\(/g, ""))) throw new Error(`expressão inesperada: ${expr}`);
  return Function("px", `"use strict"; return (${js});`)((dp) => dp * DENSIDADE);
}

function posicao(botao) {
  const larg = botao.width * DENSIDADE;
  const alt = botao.height * DENSIDADE;
  const vars = {
    screen_width: TELA_L, screen_height: TELA_A, width: larg, height: alt,
    margin: 2 * DENSIDADE, preferred_scale: 100,
    top: 0, left: 0, right: TELA_L - larg, bottom: TELA_A - alt,
  };
  const x = avaliar(botao.dynamicX, vars);
  const y = avaliar(botao.dynamicY, vars);
  return {
    x: Math.round(limitar(x / (TELA_L - larg), 0, 1) * 10000),
    y: Math.round(limitar(y / (TELA_A - alt), 0, 1) * 10000),
  };
}

function tamanho(botao) {
  const pct = (dp) => Math.round(limitar((dp * DENSIDADE) / TELA_A * 10000, 100, 10000));
  return {
    type: "dp",
    widthDp: Math.max(5, Math.round(botao.width * 100) / 100),
    heightDp: Math.max(5, Math.round(botao.height * 100) / 100),
    widthPercentage: pct(botao.width),
    heightPercentage: pct(botao.height),
    widthReference: "screen_height",
    heightReference: "screen_height",
  };
}

const visibilidade = (b) =>
  b.displayInGame && b.displayInMenu ? "always" : b.displayInGame ? "in_game" : b.displayInMenu ? "in_menu" : "always";

/* ---------- estilos: um por combinação de cor/borda/cantos/opacidade ---------- */
const estilos = new Map();
function estilo(b) {
  const chave = [b.opacity, b.bgColor, b.strokeWidth, b.strokeColor, b.cornerRadius].join("|");
  if (!estilos.has(chave)) {
    // Pojav: borda em % de metade do maior lado; cantos em % (100 = redondo). Aqui: borda em dp; cantos 50% = redondo
    const borda = b.strokeWidth > 0 ? Math.max(1, Math.round(Math.max(b.width, b.height) / 2 * b.strokeWidth / 100)) : 0;
    const canto = limitar(b.cornerRadius / 2, 0, 50);
    const cantos = { topStart: canto, topEnd: canto, bottomEnd: canto, bottomStart: canto };
    const config = {
      alpha: limitar(b.opacity, 0, 1),
      pressedAlpha: limitar(b.opacity + 0.2, 0, 1),
      backgroundColor: semSinal(b.bgColor),
      pressedBackgroundColor: 0xb3888888,
      contentColor: 0xffffffff,
      pressedContentColor: 0xffffffff,
      borderWidth: limitar(borda, 0, 50),
      pressedBorderWidth: limitar(borda, 0, 50),
      borderColor: semSinal(b.strokeColor),
      pressedBorderColor: semSinal(b.strokeColor),
      borderRadius: cantos,
      pressedBorderRadius: cantos,
    };
    estilos.set(chave, {
      name: `eclipse-${estilos.size + 1}`,
      uuid: id(`estilo:${chave}`, 12),
      animateSwap: false,
      commonStyle: true,
      lightStyle: config,
      darkStyle: config,
    });
  }
  return estilos.get(chave).uuid;
}

/* ---------- botões ---------- */
const pojav = JSON.parse(fs.readFileSync(entrada, "utf8"));
if (!Array.isArray(pojav.mControlDataList)) throw new Error("não parece um layout do PojavLauncher (falta mControlDataList)");

const botoesGui = [];
function converterBotao(b, semente, eventosExtras = []) {
  const eventos = [];
  let ehGui = false;
  for (const codigo of b.keycodes || []) {
    if (!codigo) continue;
    const especial = ESPECIAIS[String(codigo)];
    if (especial === "TOGGLE_CONTROLES") { ehGui = true; continue; }
    if (especial === "MOUSE_VIRTUAL") { avisos.push(`"${b.name}": mouse virtual não existe aqui (o launcher troca sozinho) — botão removido`); return null; }
    if (especial) { eventos.push(especial); continue; }
    const nome = codigoParaNome.get(codigo);
    if (!nome || !nomesAceitos.has(nome)) { avisos.push(`"${b.name}": tecla ${codigo} não suportada — ignorada`); continue; }
    eventos.push({ type: "key", key: nome });
  }
  eventos.push(...eventosExtras);
  if (!eventos.length && !ehGui) { avisos.push(`"${b.name}": sem nenhuma tecla — botão removido`); return null; }
  const botao = {
    text: texto(b.name.trim()),
    uuid: id(`botao:${semente}`, 18),
    position: posicao(b),
    buttonSize: tamanho(b),
    buttonStyle: estilo(b),
    textAlignment: "Center",
    visibilityType: visibilidade(b),
    clickEvents: eventos,
    isSwipple: !!b.isSwipeable,
    isPenetrable: !!b.passThruEnabled,
    isToggleable: !!b.isToggle,
  };
  if (ehGui) botoesGui.push(botao);
  return botao;
}

const camadaPrincipal = { name: "principal", uuid: id("camada:principal", 12), hide: false, visibilityType: "always", normalButtons: [] };
const camadaGui = { name: "gui", uuid: id("camada:gui", 12), hide: false, visibilityType: "always", normalButtons: [] };
const camadasGavetas = [];

pojav.mControlDataList.forEach((b, i) => {
  const botao = converterBotao(b, `${i}:${b.name}`);
  if (!botao) return;
  (botoesGui.includes(botao) ? camadaGui : camadaPrincipal).normalButtons.push(botao);
});

// Gavetas do Pojav: o botão da gaveta abre/fecha uma camada escondida com os botões de dentro
(pojav.mDrawerDataList || []).forEach((gaveta, i) => {
  const nome = gaveta.properties.name.trim() || `gaveta ${i + 1}`;
  const camada = { name: nome, uuid: id(`camada:gaveta:${i}:${nome}`, 12), hide: true, visibilityType: "always", normalButtons: [] };
  gaveta.buttonProperties.forEach((b, j) => {
    const botao = converterBotao(b, `gaveta:${i}:${j}:${b.name}`);
    if (botao) camada.normalButtons.push(botao);
  });
  const abrir = converterBotao(gaveta.properties, `gaveta:${i}:${nome}`, [{ type: "switch_layer", key: camada.uuid }]);
  if (abrir) camadaPrincipal.normalButtons.push(abrir);
  camadasGavetas.push(camada);
});

// Joystick
const camadaMovimento = { name: "movimento", uuid: id("camada:movimento", 12), hide: false, visibilityType: "in_game", normalButtons: [], joystickButtons: [] };
(pojav.mJoystickDataList || []).forEach((j, i) => {
  camadaMovimento.joystickButtons.push({
    uuid: id(`joystick:${i}`, 18),
    position: posicao(j),
    sizeType: "dp",
    sizeDp: Math.round(Math.max(j.width, j.height) * 100) / 100,
    visibilityType: visibilidade(j),
    canLock: !!j.forwardLock,
  });
});

// Botão "GUI" esconde/mostra os outros controles (como o TOGGLECTRL do Pojav)
const alternaveis = [camadaPrincipal, camadaMovimento].filter((c) => c.normalButtons.length || c.joystickButtons?.length);
for (const botao of botoesGui) {
  botao.clickEvents = [...botao.clickEvents, ...alternaveis.map((c) => ({ type: "switch_layer", key: c.uuid }))];
}

const camadas = [camadaGui, camadaPrincipal, ...camadasGavetas, camadaMovimento]
  .filter((c) => c.normalButtons.length || c.joystickButtons?.length)
  .map((c) => (c.joystickButtons && !c.joystickButtons.length ? (({ joystickButtons, ...r }) => r)(c) : c));

const layout = {
  info: {
    name: texto("Eclipse Cobblemon"),
    author: texto("Eclipse Cobblemon"),
    description: texto("Controles oficiais do servidor Eclipse Cobblemon"),
    versionCode: versao,
    versionName: `${versao}.0`,
  },
  layers: camadas,
  styles: [...estilos.values()],
  joystickStyles: [],
  editorVersion: 12,
};

fs.mkdirSync(path.dirname(saida), { recursive: true });
fs.writeFileSync(saida, JSON.stringify(layout, null, 2) + "\n");

const total = camadas.reduce((n, c) => n + c.normalButtons.length + (c.joystickButtons?.length || 0), 0);
console.log(`Layout gerado: ${saida}`);
console.log(`  versão ${versao}, ${camadas.length} camadas, ${total} controles, ${estilos.size} estilos`);
for (const c of camadas) {
  console.log(`  - ${c.name}${c.hide ? " (escondida, abre pela gaveta)" : ""}: ${c.normalButtons.map((b) => b.text.default).join(", ")}${c.joystickButtons?.length ? " + joystick" : ""}`);
}
for (const a of avisos) console.log(`  ! ${a}`);
