"use strict";

/* ------------------------------------------------------------------
 * Leitor em voz alta — reprodução contínua com Web Audio.
 * Cada frase é sintetizada no servidor (Piper) e agendada para tocar
 * exatamente quando a anterior termina, sem lacunas. As próximas
 * frases são pré-carregadas enquanto a atual toca.
 * ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);
const el = {
  entrada: $("entrada"), leitura: $("leitura"), player: $("player"),
  formUrl: $("form-url"), url: $("url"), arquivo: $("arquivo"), zona: $("zona"),
  erro: $("erro"), carregando: $("carregando"), titulo: $("titulo"), meta: $("meta"),
  texto: $("texto"), btnNovo: $("btn-novo"), btnPlay: $("btn-play"),
  btnAnt: $("btn-ant"), btnProx: $("btn-prox"), icPlay: $("ic-play"),
  icPausa: $("ic-pausa"), icCarrega: $("ic-carrega"), posicao: $("posicao"),
  barra: $("barra"), progresso: $("progresso"), velocidade: $("velocidade"),
  velValor: $("vel-valor"), voz: $("voz"), pausas: $("pausas"),
};

const PRE_CARREGAR = 4;          // quantas frases à frente sintetizar
const AGENDAR_A_FRENTE = 2;      // quantas frases ficam agendadas no Web Audio
const PAUSA_PARAGRAFO = 0.8;     // segundos ao fim de cada parágrafo (velocidade 1×)
const PAUSA_FRASE = 0.45;        // segundos entre frases (velocidade 1×)

const estado = {
  frases: [],            // [{texto, tipo, fimParagrafo, pausa, span}]
  atual: 0,              // frase em destaque
  tocando: false,
  ctx: null,
  geracao: 0,            // invalida agendamentos antigos ao pular/mudar voz
  proximaAgendar: 0,
  proximoInicio: 0,
  agendadas: [],         // [{idx, fonte, inicio, fim}]
  cache: new Map(),      // chave -> Promise<AudioBuffer>
  docId: "",
  concluido: false,
};

/* ---------------------------- Utilidades --------------------------- */

// Em SVG, a propriedade .hidden não existe: é preciso usar o atributo.
function mostrar(elemento, visivel) { elemento.toggleAttribute("hidden", !visivel); }

const prefs = {
  get(k, padrao) { try { return localStorage.getItem("leitor." + k) ?? padrao; } catch { return padrao; } },
  set(k, v) { try { localStorage.setItem("leitor." + k, v); } catch { /* ignora */ } },
};

function mostrarErro(msg) {
  el.erro.textContent = msg;
  el.erro.hidden = !msg;
}

function fmtVel(v) { return Number(v).toFixed(1).replace(".", ",") + "×"; }

function chave(idx) {
  return `${el.voz.value}|${el.velocidade.value}|${el.pausas.value}|${pron.versao}|${idx}`;
}

/* ------------------------- Carregar documento ---------------------- */

async function carregar(formData) {
  mostrarErro("");
  el.carregando.hidden = false;
  try {
    const resp = await fetch("/api/extrair", { method: "POST", body: formData });
    const dados = await resp.json().catch(() => ({}));
    if (!resp.ok) throw new Error(dados.detail || "Não foi possível ler este conteúdo.");
    abrirDocumento(dados);
  } catch (e) {
    mostrarErro(e.message === "Failed to fetch" ? "Não foi possível falar com o servidor." : e.message);
  } finally {
    el.carregando.hidden = true;
  }
}

// Libera tudo o que pertence ao documento atual: áudio agendado, áudios já
// decodificados, pré-carregamentos pendentes, o texto na página e o contexto de áudio.
function liberarDocumento() {
  parar();
  filaPreCarga++;              // interrompe a fila de pré-carregamento
  estado.cache.clear();        // áudios decodificados (AudioBuffer)
  estado.frases = [];          // referências às frases e aos elementos da página
  estado.agendadas = [];
  estado.atual = 0;
  estado.concluido = false;
  el.texto.replaceChildren();  // remove o texto do documento da página
  if (estado.ctx) {            // fecha o contexto de áudio (é recriado ao tocar de novo)
    estado.ctx.close().catch(() => {});
    estado.ctx = null;
  }
}

/* Posição de leitura por documento: guarda só os 50 mais recentes */
const MAX_POSICOES = 50;
function lerPosicoes() {
  try { return JSON.parse(prefs.get("posicoes", "{}")) || {}; } catch { return {}; }
}
function posicaoSalva(doc) { return lerPosicoes()[doc]?.[0] ?? 0; }
function salvarPosicao(doc, idx) {
  if (!doc) return;
  const todas = lerPosicoes();
  todas[doc] = [idx, Date.now()];
  const ordem = Object.entries(todas).sort((a, b) => b[1][1] - a[1][1]).slice(0, MAX_POSICOES);
  prefs.set("posicoes", JSON.stringify(Object.fromEntries(ordem)));
}

function abrirDocumento(dados) {
  liberarDocumento();

  const TAGS = { titulo: "h1", h: "h2", p: "p", item: "p", citacao: "blockquote", nota: "p" };
  let lista = null;
  for (const bloco of dados.blocos) {
    let tag = TAGS[bloco.tipo] || "p";
    if (bloco.tipo === "h") tag = bloco.nivel <= 2 ? "h2" : bloco.nivel === 3 ? "h3" : "h4";
    const elBloco = document.createElement(bloco.tipo === "item" ? "li" : tag);
    elBloco.className = `bloco bloco-${bloco.tipo}`;
    bloco.frases.forEach((texto, i) => {
      const span = document.createElement("span");
      span.className = "frase";
      span.textContent = texto;
      span.dataset.idx = estado.frases.length;
      elBloco.append(span, " ");
      const fim = i === bloco.frases.length - 1;
      const pausa = bloco.pausas?.[i] ?? (fim ? PAUSA_PARAGRAFO : PAUSA_FRASE);
      estado.frases.push({ texto, tipo: bloco.tipo, fimParagrafo: fim, pausa, span });
    });
    if (bloco.tipo === "item") {
      if (!lista) { lista = document.createElement("ul"); lista.className = "lista"; el.texto.append(lista); }
      lista.append(elBloco);
    } else {
      lista = null;
      el.texto.append(elBloco);
    }
  }
  // Se o título faz parte do texto (e é lido), não repete no cabeçalho
  el.titulo.hidden = dados.blocos.some((b) => b.tipo === "titulo");

  const minutos = Math.max(1, Math.round(dados.palavras / 150));
  el.titulo.textContent = dados.titulo;
  el.meta.textContent = `${dados.fonte} · ${dados.palavras.toLocaleString("pt-BR")} palavras · cerca de ${minutos} min de áudio`;
  document.title = `${dados.titulo} — Leitor`;

  estado.docId = dados.fonte;
  const salvo = Math.min(Number(posicaoSalva(estado.docId)) || 0, estado.frases.length - 1);
  el.entrada.hidden = true;
  el.leitura.hidden = false;
  el.player.hidden = false;
  destacar(salvo, salvo > 0);
  window.scrollTo({ top: 0 });
}

/* ------------------------------ Áudio ------------------------------ */

function contexto() {
  if (!estado.ctx) {
    estado.ctx = new (window.AudioContext || window.webkitAudioContext)();
  }
  return estado.ctx;
}

function obterAudio(idx) {
  const k = chave(idx);
  if (!estado.cache.has(k)) {
    const promessa = fetch("/api/falar", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        texto: estado.frases[idx].texto,
        voz: el.voz.value,
        velocidade: Number(el.velocidade.value),
        tipo: estado.frases[idx].tipo || "p",
        pausas: Number(el.pausas.value),
      }),
    })
      .then(async (r) => {
        if (!r.ok) {
          const info = await r.json().catch(() => ({}));
          throw new Error(info.detail || `HTTP ${r.status}`);
        }
        return r.arrayBuffer();
      })
      .then((buf) => contexto().decodeAudioData(buf));
    promessa.catch(() => estado.cache.delete(k));
    estado.cache.set(k, promessa);
  }
  return estado.cache.get(k);
}

// Pré-carrega as próximas frases UMA POR VEZ e em ordem: o servidor gera uma
// frase de cada vez, e assim a que vai tocar primeiro fica pronta primeiro.
let filaPreCarga = 0;
async function preCarregar(desde, esperarAntes = null) {
  const minhaFila = ++filaPreCarga;
  const geracao = estado.geracao;
  (async () => {
    try { await esperarAntes; } catch { return; }
    for (let i = desde; i < Math.min(desde + PRE_CARREGAR, estado.frases.length); i++) {
      if (minhaFila !== filaPreCarga || geracao !== estado.geracao) return; // outra fila assumiu
      try { await obterAudio(i); } catch { return; }
    }
  })();
  // libera memória de frases já bem para trás
  for (const k of estado.cache.keys()) {
    const idx = Number(k.split("|").pop());
    if (idx < estado.atual - 3 || !k.startsWith(`${el.voz.value}|${el.velocidade.value}|${el.pausas.value}|${pron.versao}|`)) estado.cache.delete(k);
  }
}

async function bombear() {
  const geracao = estado.geracao;
  const ctx = contexto();
  while (
    estado.tocando && geracao === estado.geracao &&
    estado.proximaAgendar < estado.frases.length &&
    estado.agendadas.length < AGENDAR_A_FRENTE
  ) {
    const idx = estado.proximaAgendar++;
    if (estado.agendadas.length === 0) carregandoIcone(true);
    let buffer;
    try {
      const pedido = obterAudio(idx);   // a frase que vai tocar é pedida primeiro…
      preCarregar(idx + 1, pedido);     // …e as seguintes entram na fila depois dela
      buffer = await pedido;
    } catch (e) {
      if (geracao !== estado.geracao) return;
      mostrarFalha(e);
      return;
    }
    if (geracao !== estado.geracao || !estado.tocando) return;
    carregandoIcone(false);

    const fonte = ctx.createBufferSource();
    fonte.buffer = buffer;
    fonte.connect(ctx.destination);
    const inicio = Math.max(estado.proximoInicio, ctx.currentTime + 0.03);
    fonte.start(inicio);
    const fim = inicio + buffer.duration;
    const item = { idx, fonte, inicio, fim };
    estado.agendadas.push(item);
    // pausas encurtam com a velocidade e seguem a preferência (curtas/normais/longas)
    const pausaBase = estado.frases[idx].pausa ?? (estado.frases[idx].fimParagrafo ? PAUSA_PARAGRAFO : PAUSA_FRASE);
    estado.proximoInicio = fim + (pausaBase * Number(el.pausas.value)) / Number(el.velocidade.value);

    fonte.onended = () => {
      if (geracao !== estado.geracao) return;
      estado.agendadas = estado.agendadas.filter((a) => a !== item);
      if (idx === estado.frases.length - 1) { terminou(); return; }
      bombear();
    };
  }
}

function mostrarFalha(e) {
  estado.tocando = false;
  atualizarBotao();
  carregandoIcone(false);
  el.posicao.textContent = "Erro ao gerar o áudio.";
  el.posicao.title = e?.message || "";
  console.error("Erro ao gerar o áudio:", e);
  if (e?.message) el.posicao.textContent += " " + e.message.slice(0, 140);
}

function cancelarAgendadas() {
  estado.geracao++;
  for (const a of estado.agendadas) {
    a.fonte.onended = null;
    try { a.fonte.stop(); } catch { /* já parou */ }
  }
  estado.agendadas = [];
}

async function tocar(desde = estado.atual) {
  const ctx = contexto();
  estado.concluido = false;
  cancelarAgendadas();
  estado.tocando = true;
  estado.proximaAgendar = desde;
  estado.proximoInicio = 0;
  destacar(desde);
  if (ctx.state === "suspended") await ctx.resume();
  atualizarBotao();
  bombear();
  loopDestaque();
}

function pausar() {
  if (!estado.ctx) return;
  estado.tocando = false;
  estado.ctx.suspend();
  atualizarBotao();
}

async function retomar() {
  if (estado.agendadas.length === 0) return tocar(estado.atual);
  estado.tocando = true;
  await estado.ctx.resume();
  atualizarBotao();
  bombear();
  loopDestaque();
}

function parar() {
  cancelarAgendadas();
  estado.tocando = false;
  if (estado.ctx && estado.ctx.state === "suspended") estado.ctx.resume();
  carregandoIcone(false);
  atualizarBotao();
}

function terminou() {
  estado.tocando = false;
  estado.concluido = true;
  estado.agendadas = [];
  atualizarBotao();
  destacar(estado.frases.length - 1);
  estado.frases.forEach((f) => f.span.classList.add("lida"));
  el.posicao.textContent = "Leitura concluída";
  salvarPosicao(estado.docId, 0);
}

function alternar() {
  if (estado.tocando) pausar();
  else if (estado.agendadas.length && estado.ctx?.state === "suspended") retomar();
  else tocar(estado.concluido ? 0 : estado.atual);
}

function irPara(idx) {
  idx = Math.max(0, Math.min(estado.frases.length - 1, idx));
  if (estado.tocando) tocar(idx);
  else { cancelarAgendadas(); estado.concluido = false; destacar(idx, true); }
}

/* ------------------------- Destaque e interface --------------------- */

function loopDestaque() {
  if (!estado.tocando) return;
  const t = estado.ctx.currentTime;
  const ativa = estado.agendadas.find((a) => t >= a.inicio && t < a.fim + 0.5) || null;
  if (ativa && ativa.idx !== estado.atual) destacar(ativa.idx, true);
  requestAnimationFrame(loopDestaque);
}

function destacar(idx, rolar = true) {
  const anterior = estado.frases[estado.atual];
  if (anterior) anterior.span.classList.remove("atual");
  // marca lidas / não lidas
  const ini = Math.min(idx, estado.atual), fim = Math.max(idx, estado.atual);
  for (let i = ini; i <= fim; i++) estado.frases[i]?.span.classList.toggle("lida", i < idx);
  estado.atual = idx;
  const f = estado.frases[idx];
  if (!f) return;
  f.span.classList.add("atual");
  f.span.classList.remove("lida");
  if (rolar) {
    const r = f.span.getBoundingClientRect();
    const limiteInferior = window.innerHeight - el.player.offsetHeight - 40;
    if (r.top < 70 || r.bottom > limiteInferior) {
      f.span.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }
  const total = estado.frases.length;
  el.posicao.textContent = `Frase ${idx + 1} de ${total}`;
  el.progresso.style.width = `${((idx + 1) / total) * 100}%`;
  salvarPosicao(estado.docId, idx);
  if ("mediaSession" in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: el.titulo.textContent, artist: f.texto.slice(0, 80) });
  }
}

function atualizarBotao() {
  mostrar(el.icPlay, !estado.tocando);
  mostrar(el.icPausa, estado.tocando);
  el.btnPlay.setAttribute("aria-label", estado.tocando ? "Pausar" : "Reproduzir");
  if (!estado.tocando) carregandoIcone(false);
}

function carregandoIcone(ativo) {
  mostrar(el.icCarrega, ativo);
  if (ativo) { mostrar(el.icPlay, false); mostrar(el.icPausa, false); }
  else atualizarBotaoSemSpinner();
}
function atualizarBotaoSemSpinner() {
  mostrar(el.icPlay, !estado.tocando);
  mostrar(el.icPausa, estado.tocando);
}

function aplicarMudancaDeVoz() {
  prefs.set("voz", el.voz.value);
  prefs.set("velocidade", el.velocidade.value);
  prefs.set("pausas", el.pausas.value);
  el.velValor.textContent = fmtVel(el.velocidade.value);
  if (estado.tocando) tocar(estado.atual);
}

/* ------------------------------ Eventos ----------------------------- */

el.formUrl.addEventListener("submit", (e) => {
  e.preventDefault();
  const fd = new FormData();
  fd.append("url", el.url.value.trim());
  carregar(fd);
});

function enviarArquivo(arquivo) {
  if (!arquivo) return;
  const fd = new FormData();
  fd.append("arquivo", arquivo);
  carregar(fd);
}

el.arquivo.addEventListener("change", () => { enviarArquivo(el.arquivo.files[0]); el.arquivo.value = ""; });
el.zona.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); el.arquivo.click(); } });
["dragenter", "dragover"].forEach((ev) => el.zona.addEventListener(ev, (e) => { e.preventDefault(); el.zona.classList.add("arrastando"); }));
["dragleave", "drop"].forEach((ev) => el.zona.addEventListener(ev, (e) => { e.preventDefault(); el.zona.classList.remove("arrastando"); }));
el.zona.addEventListener("drop", (e) => enviarArquivo(e.dataTransfer.files[0]));
document.addEventListener("dragover", (e) => e.preventDefault());
document.addEventListener("drop", (e) => {
  e.preventDefault();
  if (!el.entrada.hidden && e.dataTransfer.files[0]) enviarArquivo(e.dataTransfer.files[0]);
});

el.texto.addEventListener("click", (e) => {
  const span = e.target.closest(".frase");
  if (!span) return;
  const idx = Number(span.dataset.idx);
  if (estado.tocando) tocar(idx); else { cancelarAgendadas(); destacar(idx, false); tocar(idx); }
});

el.btnPlay.addEventListener("click", alternar);
el.btnAnt.addEventListener("click", () => irPara(estado.atual - 1));
el.btnProx.addEventListener("click", () => irPara(estado.atual + 1));
el.barra.addEventListener("click", (e) => {
  const r = el.barra.getBoundingClientRect();
  irPara(Math.floor(((e.clientX - r.left) / r.width) * estado.frases.length));
});
el.velocidade.addEventListener("input", () => { el.velValor.textContent = fmtVel(el.velocidade.value); });
el.velocidade.addEventListener("change", aplicarMudancaDeVoz);
el.voz.addEventListener("change", aplicarMudancaDeVoz);
el.pausas.addEventListener("change", aplicarMudancaDeVoz);

el.btnNovo.addEventListener("click", () => {
  liberarDocumento();
  estado.docId = "";
  el.leitura.hidden = true;
  el.player.hidden = true;
  el.entrada.hidden = false;
  document.title = "Leitor em Voz Alta";
  el.url.focus();
});

document.addEventListener("keydown", (e) => {
  if (el.player.hidden || e.target.matches("input, select, textarea") || dlg.open) return;
  if (e.code === "Space") { e.preventDefault(); alternar(); }
  else if (e.key === "ArrowRight") { e.preventDefault(); irPara(estado.atual + 1); }
  else if (e.key === "ArrowLeft") { e.preventDefault(); irPara(estado.atual - 1); }
});

if ("mediaSession" in navigator) {
  navigator.mediaSession.setActionHandler("play", () => !estado.tocando && alternar());
  navigator.mediaSession.setActionHandler("pause", () => estado.tocando && alternar());
  navigator.mediaSession.setActionHandler("previoustrack", () => irPara(estado.atual - 1));
  navigator.mediaSession.setActionHandler("nexttrack", () => irPara(estado.atual + 1));
}

/* ------------------------------ Pronúncia --------------------------- */

const dlg = $("dlg-pronuncia");
const pron = { versao: 0, regras: {}, config: null, rascunho: null, fonteTeste: null, botaoTeste: null, retomar: false };

// Interrompe a leitura (guardando a posição) para os testes não tocarem por cima dela
function interromperLeitura() {
  if (estado.tocando || estado.agendadas.length) {
    pron.retomar = pron.retomar || estado.tocando;
    cancelarAgendadas();
    estado.tocando = false;
    atualizarBotao();
  }
}
const NOMES_SOM = {
  lh: ["Som do “lh”", "trabalho, milhões, filho"],
  nh: ["Som do “nh”", "senhor, dinheiro, amanhã"],
};
const ICONE_PLAY = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
const ICONE_X = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';

// Cópia do dicionário no navegador: se o arquivo do servidor sumir (reinstalação,
// pasta substituída…), ele é restaurado a partir daqui.
const CHAVE_COPIA = "pronuncia-copia";
function guardarCopia(config) { prefs.set(CHAVE_COPIA, JSON.stringify(config)); }
function lerCopia() { try { return JSON.parse(prefs.get(CHAVE_COPIA, "null")); } catch { return null; } }

async function carregarPronuncia() {
  try {
    const dados = await (await fetch("/api/pronuncia", { cache: "no-store" })).json();
    pron.versao = dados.versao;
    pron.regras = dados.regras;
    pron.config = dados.config;
    pron.arquivo = dados.arquivo;
    const copia = lerCopia();
    if (!dados.existia && copia && Object.keys(copia.palavras || {}).length) {
      await gravarPronuncia(copia);  // o servidor não tinha o arquivo: restaura a cópia
      console.info("Dicionário de pronúncia restaurado a partir da cópia do navegador.");
    } else {
      guardarCopia(dados.config);
    }
  } catch { /* o resto do app funciona sem isso */ }
}

async function gravarPronuncia(config) {
  const resp = await fetch("/api/pronuncia", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  });
  if (!resp.ok) throw new Error((await resp.json().catch(() => ({}))).detail || `HTTP ${resp.status}`);
  const dados = await resp.json();
  pron.config = dados.config;
  pron.versao = dados.versao;
  pron.arquivo = dados.arquivo;
  estado.cache.clear();
  guardarCopia(dados.config);
  return dados;
}

// Toda alteração é salva na hora, em ordem (uma gravação por vez)
let filaDeGravacao = Promise.resolve();
function salvarAgora(mensagem = "Salvo ✓") {
  const config = JSON.parse(JSON.stringify(pron.rascunho));
  pron.pendente = true;
  statusDlg("Salvando…");
  filaDeGravacao = filaDeGravacao
    .then(() => gravarPronuncia(config))
    .then(() => { pron.pendente = false; statusDlg(mensagem); })
    .catch((e) => {
      const motivo = e.message.replace(/^Não foi possível salvar:\s*/, "");
      statusDlg(`Não foi possível salvar (${motivo}). Tente de novo.`);
    });
  return filaDeGravacao;
}

function statusDlg(msg) { $("dlg-status").textContent = msg; }

function pararTeste() {
  if (pron.fonteTeste) { pron.fonteTeste.onended = null; try { pron.fonteTeste.stop(); } catch { /* já parou */ } }
  pron.fonteTeste = null;
  pron.botaoTeste?.classList.remove("tocando");
  pron.botaoTeste = null;
}

async function ouvirTeste(texto, botao, ajustes = {}) {
  if (!texto.trim()) return;
  const mesmoBotao = pron.botaoTeste === botao;
  pararTeste();
  if (mesmoBotao) return; // segundo clique = parar
  interromperLeitura();
  pron.botaoTeste = botao;
  botao.classList.add("tocando");
  statusDlg("Gerando áudio…");
  try {
    const r = pron.rascunho;
    const resp = await fetch("/api/pronuncia/testar", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        texto, voz: el.voz.value, velocidade: Number(el.velocidade.value),
        lh: r.lh, nh: r.nh, palavras: r.palavras, ...ajustes,
      }),
    });
    if (!resp.ok) throw new Error((await resp.json().catch(() => ({}))).detail || `HTTP ${resp.status}`);
    const ctx = contexto();
    if (ctx.state === "suspended") await ctx.resume();
    const buffer = await ctx.decodeAudioData(await resp.arrayBuffer());
    if (pron.botaoTeste !== botao) return;
    const fonte = ctx.createBufferSource();
    fonte.buffer = buffer;
    fonte.connect(ctx.destination);
    fonte.onended = () => { if (pron.fonteTeste === fonte) pararTeste(); };
    fonte.start();
    pron.fonteTeste = fonte;
    statusDlg("");
  } catch (e) {
    pararTeste();
    statusDlg("Erro ao gerar o áudio: " + e.message);
  }
}

function montarRegras() {
  const caixa = $("regras");
  caixa.replaceChildren();
  for (const [som, regra] of Object.entries(pron.regras)) {
    const div = document.createElement("div");
    div.className = "regra";
    const [nome, exemplos] = NOMES_SOM[som] || [som, ""];
    div.innerHTML = `<div class="regra-titulo">${nome} <small>— ${exemplos}</small></div>`;
    const linha = document.createElement("div");
    linha.className = "regra-linha";
    const opcoes = document.createElement("div");
    opcoes.className = "opcoes";
    opcoes.setAttribute("role", "radiogroup");
    for (const op of regra.opcoes) {
      const label = document.createElement("label");
      const input = document.createElement("input");
      input.type = "radio";
      input.name = "regra-" + som;
      input.value = op.id;
      input.checked = pron.rascunho[som] === op.id;
      input.addEventListener("change", () => {
        pron.rascunho[som] = op.id;
        salvarAgora(`Som do “${som}” salvo ✓`);
        ouvirTeste(regra.exemplo, botao);
      });
      const span = document.createElement("span");
      span.textContent = op.rotulo;
      label.append(input, span);
      opcoes.append(label);
    }
    const botao = document.createElement("button");
    botao.type = "button";
    botao.className = "btn-ouvir btn-ouvir-rotulo";
    botao.innerHTML = ICONE_PLAY + "Ouvir exemplo";
    botao.title = regra.exemplo;
    botao.addEventListener("click", () => ouvirTeste(regra.exemplo, botao));
    linha.append(opcoes, botao);
    div.append(linha);
    caixa.append(div);
  }
}

function montarPalavras() {
  const lista = $("lista-palavras");
  lista.replaceChildren();
  const entradas = Object.entries(pron.rascunho.palavras).sort(([a], [b]) => a.localeCompare(b, "pt-BR"));
  if (!entradas.length) {
    const li = document.createElement("li");
    li.className = "vazia";
    li.textContent = "Nenhuma palavra cadastrada.";
    lista.append(li);
    return;
  }
  for (const [palavra, grafia] of entradas) {
    const li = document.createElement("li");
    const p = document.createElement("span"); p.textContent = palavra;
    const seta = document.createElement("span"); seta.className = "seta"; seta.textContent = "→";
    const g = document.createElement("span"); g.className = "grafia"; g.textContent = grafia;
    const ouvir = document.createElement("button");
    ouvir.type = "button"; ouvir.className = "btn-ouvir"; ouvir.title = `Ouvir “${palavra}”`;
    ouvir.setAttribute("aria-label", ouvir.title);
    ouvir.innerHTML = ICONE_PLAY;
    ouvir.addEventListener("click", () => ouvirTeste(palavra, ouvir));
    const remover = document.createElement("button");
    remover.type = "button"; remover.className = "btn-remover"; remover.title = `Remover “${palavra}”`;
    remover.setAttribute("aria-label", remover.title);
    remover.innerHTML = ICONE_X;
    remover.addEventListener("click", () => {
      delete pron.rascunho.palavras[palavra];
      montarPalavras();
      salvarAgora(`“${palavra}” removida ✓`);
    });
    li.append(p, seta, g, ouvir, remover);
    lista.append(li);
  }
}

async function abrirPronuncia() {
  const selecao = (window.getSelection()?.toString() || "").trim();
  if (!pron.config) await carregarPronuncia();
  if (!pron.config) { mostrarErro("Não foi possível carregar as configurações de pronúncia."); return; }
  pron.rascunho = JSON.parse(JSON.stringify(pron.config));
  pron.pendente = false;
  montarRegras();
  montarPalavras();
  statusDlg("");
  $("dlg-arquivo").textContent = pron.arquivo ? `As alterações são salvas automaticamente em ${pron.arquivo}` : "";
  $("nova-palavra").value = /^[\p{L}-]{2,40}$/u.test(selecao) ? selecao.toLowerCase() : "";
  $("nova-grafia").value = "";
  pron.retomar = false;
  interromperLeitura();
  dlg.showModal();
  ($("nova-palavra").value ? $("nova-grafia") : $("nova-palavra")).focus();
}

function adicionarPalavra() {
  const palavra = $("nova-palavra").value.trim().toLowerCase();
  const grafia = $("nova-grafia").value.trim();
  if (!palavra || !grafia) { statusDlg("Preencha a palavra e como ela deve soar."); return; }
  if (/\s/.test(palavra)) { statusDlg("Cadastre uma palavra por vez."); return; }
  pron.rascunho.palavras[palavra] = grafia;
  $("nova-palavra").value = "";
  $("nova-grafia").value = "";
  montarPalavras();
  salvarAgora(`“${palavra}” salva ✓`);
  $("nova-palavra").focus();
}

async function concluirPronuncia() {
  // Tudo já foi salvo a cada alteração; aqui só garantimos que a última gravação terminou.
  if (pron.pendente) { await salvarAgora(); }
  if (pron.pendente) return;   // falhou: mantém o painel aberto, com a mensagem de erro
  dlg.close();                 // o evento "close" retoma a leitura, já com a nova pronúncia
}

$("btn-pronuncia").addEventListener("click", abrirPronuncia);
$("btn-add-palavra").addEventListener("click", adicionarPalavra);
$("btn-salvar-pron").addEventListener("click", concluirPronuncia);
$("btn-ouvir-nova").addEventListener("click", (e) => {
  const palavra = $("nova-palavra").value.trim();
  const grafia = $("nova-grafia").value.trim();
  const palavras = { ...pron.rascunho.palavras };
  if (palavra && grafia) palavras[palavra.toLowerCase()] = grafia;
  ouvirTeste(palavra || grafia, e.currentTarget, { palavras });
});
["nova-palavra", "nova-grafia"].forEach((id) => $(id).addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); adicionarPalavra(); }
}));
dlg.querySelectorAll("[data-fechar]").forEach((b) => b.addEventListener("click", () => dlg.close()));
dlg.addEventListener("close", () => {
  pararTeste();
  const retomar = pron.retomar && estado.frases.length;
  pron.retomar = false;
  // espera a última gravação terminar, para retomar já com a pronúncia nova
  filaDeGravacao.finally(() => { if (retomar) tocar(estado.atual); });
});
dlg.addEventListener("click", (e) => { if (e.target === dlg) dlg.close(); }); // clique fora fecha

/* ----------------------------- Inicialização ------------------------ */

(async function iniciar() {
  try {  // migra o formato antigo de posições ("leitor.pos:<doc>", sem limite)
    for (const k of Object.keys(localStorage)) if (k.startsWith("leitor.pos:")) localStorage.removeItem(k);
  } catch { /* armazenamento indisponível */ }
  carregarPronuncia();
  el.velocidade.value = prefs.get("velocidade", "1");
  el.pausas.value = prefs.get("pausas", "1");
  el.velValor.textContent = fmtVel(el.velocidade.value);
  try {
    const { vozes, padrao } = await (await fetch("/api/vozes")).json();
    const escolhida = prefs.get("voz", padrao);
    for (const v of vozes) el.voz.add(new Option(v.nome, v.id, false, v.id === escolhida));
    if (!vozes.length) mostrarErro("Nenhuma voz instalada. Rode: python baixar_vozes.py");
  } catch {
    mostrarErro("Não foi possível falar com o servidor.");
  }
  const params = new URLSearchParams(location.search);
  if (params.get("url")) { el.url.value = params.get("url"); el.formUrl.requestSubmit(); }
})();
