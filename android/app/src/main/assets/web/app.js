"use strict";

/* ------------------------------------------------------------------
 * Leitor de Voz — interface do app Android.
 * A extração do texto, a voz (Kokoro) e o player rodam em código nativo;
 * esta página só mostra o texto e envia comandos pela ponte `Android`.
 * Eventos nativos chegam em app.evento({...}).
 * ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);
const el = {
  preparo: $("preparo"), entrada: $("entrada"), leitura: $("leitura"), player: $("player"),
  formUrl: $("form-url"), url: $("url"), zona: $("zona"), erro: $("erro"),
  carregando: $("carregando"), carregandoTexto: $("carregando-texto"),
  titulo: $("titulo"), meta: $("meta"), texto: $("texto"),
  btnPlay: $("btn-play"), btnAnt: $("btn-ant"), btnProx: $("btn-prox"),
  icPlay: $("ic-play"), icPausa: $("ic-pausa"), icCarrega: $("ic-carrega"),
  posicao: $("posicao"), barra: $("barra"), progresso: $("progresso"),
  velocidade: $("velocidade"), velValor: $("vel-valor"), voz: $("voz"), pausas: $("pausas"),
  btnNovo: $("btn-novo"), avisoLento: $("aviso-lento"),
};

const estado = {
  frases: [],          // [{texto, tipo, span}]
  atual: 0,
  tocando: false,
  iniciado: false,     // já começou a tocar este documento (para retomar em vez de recomeçar)
  concluido: false,
  docId: "",
  vezes: [],           // velocidade de geração medida (× tempo real)
};

const prefs = {
  get(k, padrao) { try { return localStorage.getItem("leitor." + k) ?? padrao; } catch { return padrao; } },
  set(k, v) { try { localStorage.setItem("leitor." + k, v); } catch { /* ignora */ } },
};

function mostrar(elemento, visivel) { elemento.toggleAttribute("hidden", !visivel); }
function mostrarErro(msg) { el.erro.textContent = msg || ""; mostrar(el.erro, !!msg); }
function fmtVel(v) { return Number(v).toFixed(1).replace(".", ",") + "×"; }
function fmtMB(b) { return (b / 1048576).toFixed(0) + " MB"; }

/* ----------------------------- Posições salvas ----------------------------- */

const MAX_POSICOES = 50;
function lerPosicoes() { try { return JSON.parse(prefs.get("posicoes", "{}")) || {}; } catch { return {}; } }
function posicaoSalva(doc) { return lerPosicoes()[doc]?.[0] ?? 0; }
function salvarPosicao(doc, idx) {
  if (!doc) return;
  const todas = lerPosicoes();
  todas[doc] = [idx, Date.now()];
  const ordem = Object.entries(todas).sort((a, b) => b[1][1] - a[1][1]).slice(0, MAX_POSICOES);
  prefs.set("posicoes", JSON.stringify(Object.fromEntries(ordem)));
}

/* ------------------------------- Telas ------------------------------------- */

function tela(nome) {
  mostrar(el.preparo, nome === "preparo");
  mostrar(el.entrada, nome === "entrada");
  mostrar(el.leitura, nome === "leitura");
  mostrar(el.player, nome === "leitura");
  if (nome !== "leitura") document.title = "Leitor de Voz";
}

/* ------------------------------ Modelo de voz ------------------------------- */

let escolhaModelo = "completo";

function infoModelo() { try { return JSON.parse(Android.modelo()); } catch { return { pronto: false, opcoes: [] }; } }

function montarOpcoesModelo(caixa, info, aoEscolher) {
  caixa.replaceChildren();
  for (const op of info.opcoes) {
    const label = document.createElement("label");
    label.className = "opcao-modelo" + (op.id === escolhaModelo ? " ativa" : "");
    const input = document.createElement("input");
    input.type = "radio"; input.name = caixa.id; input.value = op.id; input.checked = op.id === escolhaModelo;
    input.addEventListener("change", () => { escolhaModelo = op.id; montarOpcoesModelo(caixa, info, aoEscolher); aoEscolher?.(op.id); });
    const instalado = info.instalados.includes(op.id);
    const desc = op.id === "completo"
      ? "Melhor qualidade de voz."
      : "Arquivo menor; pode gerar a voz mais rápido em alguns celulares.";
    const div = document.createElement("div");
    div.innerHTML = `<strong>${op.nome}</strong><small>${fmtMB(op.tamanho)} · ${desc}${instalado ? " <b>Instalado.</b>" : ""}</small>`;
    label.append(input, div);
    caixa.append(label);
  }
}

function abrirPreparo() {
  const info = infoModelo();
  escolhaModelo = info.escolhido || "completo";
  montarOpcoesModelo($("opcoes-modelo"), info);
  mostrar($("progresso-download"), info.baixando);
  mostrar($("btn-baixar"), !info.baixando);
  mostrar($("erro-preparo"), false);
  tela("preparo");
}

function iniciarDownload(id) {
  mostrar($("btn-baixar"), false);
  mostrar($("progresso-download"), true);
  mostrar($("erro-preparo"), false);
  $("texto-download").textContent = "Iniciando…";
  Android.baixarModelo(id);
}

/* ------------------------------ Documento ------------------------------------ */

function abrirDocumento(dados) {
  el.texto.replaceChildren();
  estado.frases = [];
  estado.atual = 0; estado.tocando = false; estado.iniciado = false; estado.concluido = false;

  const TAGS = { titulo: "h1", h: "h2", p: "p", item: "p", citacao: "blockquote", nota: "p" };
  let lista = null;
  for (const bloco of dados.blocos) {
    let tag = TAGS[bloco.tipo] || "p";
    if (bloco.tipo === "h") tag = bloco.nivel <= 2 ? "h2" : bloco.nivel === 3 ? "h3" : "h4";
    const elBloco = document.createElement(bloco.tipo === "item" ? "li" : tag);
    elBloco.className = `bloco bloco-${bloco.tipo}`;
    bloco.frases.forEach((texto) => {
      const span = document.createElement("span");
      span.className = "frase";
      span.textContent = texto;
      span.dataset.idx = estado.frases.length;
      elBloco.append(span, " ");
      estado.frases.push({ texto, tipo: bloco.tipo, span });
    });
    if (bloco.tipo === "item") {
      if (!lista) { lista = document.createElement("ul"); lista.className = "lista"; el.texto.append(lista); }
      lista.append(elBloco);
    } else {
      lista = null;
      el.texto.append(elBloco);
    }
  }
  el.titulo.hidden = dados.blocos.some((b) => b.tipo === "titulo");
  el.titulo.textContent = dados.titulo;
  const minutos = Math.max(1, Math.round(dados.palavras / 150));
  el.meta.textContent = `${dados.fonte} · ${dados.palavras.toLocaleString("pt-BR")} palavras · cerca de ${minutos} min de áudio`;
  document.title = dados.titulo;

  estado.docId = dados.fonte;
  const salvo = Math.min(Number(posicaoSalva(estado.docId)) || 0, estado.frases.length - 1);
  mostrar(el.carregando, false);
  mostrarErro("");
  tela("leitura");
  window.scrollTo({ top: 0 });
  destacar(salvo, salvo > 0);
  if (salvo > 0) Android.irPara(salvo);
  atualizarBotao(false);
  aplicarAjustes();
}

function fecharDocumento() {
  Android.fecharDocumento();
  el.texto.replaceChildren();
  estado.frases = []; estado.tocando = false;
  estado.docId = "";
  tela("entrada");
}

/* ------------------------------- Player ------------------------------------- */

function alternar() {
  if (!estado.frases.length) return;
  if (estado.tocando) { Android.pausar(); return; }
  if (estado.concluido) { estado.concluido = false; estado.iniciado = true; Android.tocar(0); return; }
  if (estado.iniciado) Android.retomar();
  else { estado.iniciado = true; Android.tocar(estado.atual); }
}

function irPara(idx) {
  if (!estado.frases.length) return;
  idx = Math.max(0, Math.min(estado.frases.length - 1, idx));
  estado.concluido = false;
  Android.irPara(idx);
}

function destacar(idx, rolar = true) {
  const anterior = estado.frases[estado.atual];
  if (anterior) anterior.span.classList.remove("atual");
  const ini = Math.min(idx, estado.atual), fim = Math.max(idx, estado.atual);
  for (let i = ini; i <= fim; i++) estado.frases[i]?.span.classList.toggle("lida", i < idx);
  estado.atual = idx;
  const f = estado.frases[idx];
  if (!f) return;
  f.span.classList.add("atual");
  f.span.classList.remove("lida");
  if (rolar) {
    const r = f.span.getBoundingClientRect();
    const limite = window.innerHeight - el.player.offsetHeight - 40;
    if (r.top < 80 || r.bottom > limite) f.span.scrollIntoView({ block: "center", behavior: "smooth" });
  }
  const total = estado.frases.length;
  el.posicao.textContent = `Frase ${idx + 1} de ${total}`;
  el.progresso.style.width = `${((idx + 1) / total) * 100}%`;
  salvarPosicao(estado.docId, idx);
}

function atualizarBotao(carregando) {
  mostrar(el.icCarrega, !!carregando);
  mostrar(el.icPlay, !carregando && !estado.tocando);
  mostrar(el.icPausa, !carregando && estado.tocando);
  el.btnPlay.setAttribute("aria-label", estado.tocando ? "Pausar" : "Reproduzir");
}

function aplicarAjustes() {
  prefs.set("voz", el.voz.value);
  prefs.set("velocidade", el.velocidade.value);
  prefs.set("pausas", el.pausas.value);
  el.velValor.textContent = fmtVel(el.velocidade.value);
  Android.ajustar(el.voz.value, Number(el.velocidade.value), Number(el.pausas.value));
}

/* ----------------------------- Eventos nativos ------------------------------ */

window.app = {
  evento(e) {
    switch (e.tipo) {
      case "extraindo":
        mostrarErro("");
        el.carregandoTexto.textContent = "Extraindo o texto…";
        mostrar(el.carregando, true);
        if (el.leitura.hidden && el.preparo.hidden) tela("entrada");
        break;
      case "documento": abrirDocumento(e.dados); break;
      case "erro-extracao":
        mostrar(el.carregando, false);
        if (!el.leitura.hidden) fecharDocumento();
        tela("entrada");
        mostrarErro(e.mensagem);
        break;
      case "frase": if (estado.frases.length) destacar(e.idx); break;
      case "estado":
        estado.tocando = e.tocando;
        if (e.tocando) estado.iniciado = true;
        atualizarBotao(e.tocando && e.carregando);
        if (e.carregando) el.posicao.textContent = "Gerando a voz…";
        else if (estado.frases.length) el.posicao.textContent = `Frase ${estado.atual + 1} de ${estado.frases.length}`;
        break;
      case "fim":
        estado.tocando = false; estado.concluido = true;
        atualizarBotao(false);
        estado.frases.forEach((f) => f.span.classList.add("lida"));
        el.posicao.textContent = "Leitura concluída";
        salvarPosicao(estado.docId, 0);
        break;
      case "erro":
        estado.tocando = false;
        atualizarBotao(false);
        el.posicao.textContent = e.mensagem;
        if (!infoModelo().pronto) abrirPreparo();
        break;
      case "desempenho": {
        estado.vezes.push(e.vezes);
        if (estado.vezes.length > 6) estado.vezes.shift();
        const media = estado.vezes.reduce((a, b) => a + b, 0) / estado.vezes.length;
        $("desempenho").textContent = `Neste aparelho, a voz está sendo gerada ${fmtVel(media).replace("×", "")} vezes mais rápido que a fala.`;
        const info = infoModelo();
        mostrar(el.avisoLento, estado.vezes.length >= 4 && media < 1.08 && info.escolhido === "completo");
        break;
      }
      case "download": {
        const p = e.total ? (e.baixado / e.total) * 100 : 0;
        $("barra-download").style.width = p.toFixed(1) + "%";
        $("texto-download").textContent = `${fmtMB(e.baixado)} de ${fmtMB(e.total)} (${p.toFixed(0)}%) — mantenha o app aberto.`;
        break;
      }
      case "download-fim":
        $("texto-download").textContent = "Pronto!";
        setTimeout(() => {
          estado.vezes = []; mostrar(el.avisoLento, false);
          if (dlg.open) { montarModelosDlg(); statusDlg("Modelo de voz trocado ✓"); }
          if (estado.frases.length) tela("leitura"); else tela("entrada");
        }, 600);
        break;
      case "download-erro":
        mostrar($("progresso-download"), false);
        mostrar($("btn-baixar"), true);
        $("erro-preparo").textContent = e.mensagem + " O download continua de onde parou quando você tentar de novo.";
        mostrar($("erro-preparo"), true);
        if (dlg.open) statusDlg(e.mensagem);
        break;
      case "teste-fim": pararTeste(); break;
      case "teste-erro": pararTeste(); statusDlg("Erro ao gerar o áudio: " + e.mensagem); break;
    }
  },

  /** Botão "voltar" do Android: fecha o painel ou o documento antes de sair do app. */
  voltar() {
    if (dlg.open) { dlg.close(); return true; }
    if (!el.leitura.hidden) { fecharDocumento(); return true; }
    if (!el.preparo.hidden && infoModelo().pronto) { tela("entrada"); return true; }
    return false;
  },

  margens(topo, base) {
    document.documentElement.style.setProperty("--seguro-topo", topo + "px");
    document.documentElement.style.setProperty("--seguro-base", base + "px");
  },
};

/* ------------------------------ Pronúncia ----------------------------------- */

const dlg = $("dlg-pronuncia");
const pron = { palavras: {}, testando: null };
const ICONE_PLAY = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
const ICONE_X = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';

function statusDlg(msg) { $("dlg-status").textContent = msg; }

function carregarPronuncia() {
  try {
    const d = JSON.parse(Android.pronuncia());
    pron.palavras = d.palavras || {};
    $("dlg-arquivo").textContent = `As palavras ficam salvas no celular (${d.arquivo}) e valem para todos os documentos.`;
  } catch { pron.palavras = {}; }
}

function salvarPalavras(mensagem) {
  try {
    const r = JSON.parse(Android.salvarPronuncia(JSON.stringify({ palavras: pron.palavras })));
    if (!r.ok) throw new Error(r.mensagem);
    pron.palavras = r.palavras;
    statusDlg(mensagem);
  } catch (e) {
    statusDlg(`Não foi possível salvar (${e.message}). Tente de novo.`);
  }
}

function pararTeste() {
  Android.pararTeste();
  pron.testando?.classList.remove("tocando");
  pron.testando = null;
}

function ouvirTeste(texto, botao, palavras = pron.palavras) {
  if (!texto.trim()) return;
  const mesmo = pron.testando === botao;
  pararTeste();
  if (mesmo) return;
  pron.testando = botao;
  botao.classList.add("tocando");
  estado.tocando = false; atualizarBotao(false);
  statusDlg("Gerando áudio…");
  Android.testarPronuncia(texto, JSON.stringify(palavras), el.voz.value || "pf_dora", Number(el.velocidade.value));
  setTimeout(() => { if ($("dlg-status").textContent === "Gerando áudio…") statusDlg(""); }, 4000);
}

function montarPalavras() {
  const lista = $("lista-palavras");
  lista.replaceChildren();
  const entradas = Object.entries(pron.palavras).sort(([a], [b]) => a.localeCompare(b, "pt-BR"));
  if (!entradas.length) {
    const li = document.createElement("li");
    li.className = "vazia"; li.textContent = "Nenhuma palavra cadastrada.";
    lista.append(li); return;
  }
  for (const [palavra, grafia] of entradas) {
    const li = document.createElement("li");
    const p = document.createElement("span"); p.textContent = palavra;
    const seta = document.createElement("span"); seta.className = "seta"; seta.textContent = "→";
    const g = document.createElement("span"); g.className = "grafia"; g.textContent = grafia;
    const ouvir = document.createElement("button");
    ouvir.type = "button"; ouvir.className = "btn-ouvir"; ouvir.innerHTML = ICONE_PLAY;
    ouvir.setAttribute("aria-label", `Ouvir “${palavra}”`);
    ouvir.addEventListener("click", () => ouvirTeste(palavra, ouvir));
    const remover = document.createElement("button");
    remover.type = "button"; remover.className = "btn-remover"; remover.innerHTML = ICONE_X;
    remover.setAttribute("aria-label", `Remover “${palavra}”`);
    remover.addEventListener("click", () => { delete pron.palavras[palavra]; montarPalavras(); salvarPalavras(`“${palavra}” removida ✓`); });
    li.append(p, seta, g, ouvir, remover);
    lista.append(li);
  }
}

function adicionarPalavra() {
  const palavra = $("nova-palavra").value.trim().toLowerCase();
  const grafia = $("nova-grafia").value.trim();
  if (!palavra || !grafia) { statusDlg("Preencha a palavra e como ela deve soar."); return; }
  if (/\s/.test(palavra)) { statusDlg("Cadastre uma palavra por vez."); return; }
  pron.palavras[palavra] = grafia;
  $("nova-palavra").value = ""; $("nova-grafia").value = "";
  montarPalavras();
  salvarPalavras(`“${palavra}” salva ✓`);
}

function montarModelosDlg() {
  const info = infoModelo();
  escolhaModelo = info.escolhido;
  montarOpcoesModelo($("modelos-dlg"), info, (id) => {
    if (info.instalados.includes(id) && info.escolhido === id) return;
    const op = info.opcoes.find((o) => o.id === id);
    if (confirm(`Baixar o modelo ${op.nome} (${fmtMB(op.tamanho)})? O outro modelo será apagado depois, para liberar espaço.`)) {
      statusDlg("Baixando o modelo…");
      iniciarDownload(id);
      tela(estado.frases.length ? "leitura" : "entrada");
    } else {
      escolhaModelo = info.escolhido; montarModelosDlg();
    }
  });
}

function abrirPronuncia() {
  carregarPronuncia();
  montarPalavras();
  montarModelosDlg();
  statusDlg("");
  const selecao = (window.getSelection()?.toString() || "").trim();
  $("nova-palavra").value = /^[\p{L}-]{2,40}$/u.test(selecao) ? selecao.toLowerCase() : "";
  $("nova-grafia").value = "";
  if (estado.tocando) Android.pausar();
  dlg.showModal();
}

/* ------------------------------ Interações ---------------------------------- */

el.formUrl.addEventListener("submit", (e) => {
  e.preventDefault();
  const url = el.url.value.trim();
  if (url) Android.abrirEndereco(url);
});
el.zona.addEventListener("click", () => Android.escolherArquivo());

el.texto.addEventListener("click", (e) => {
  const span = e.target.closest(".frase");
  if (!span || window.getSelection()?.toString()) return;
  estado.iniciado = true; estado.concluido = false;
  Android.tocar(Number(span.dataset.idx));
});

el.btnPlay.addEventListener("click", alternar);
el.btnAnt.addEventListener("click", () => irPara(estado.atual - 1));
el.btnProx.addEventListener("click", () => irPara(estado.atual + 1));
el.barra.addEventListener("click", (e) => {
  const r = el.barra.getBoundingClientRect();
  irPara(Math.floor(((e.clientX - r.left) / r.width) * estado.frases.length));
});
el.velocidade.addEventListener("input", () => { el.velValor.textContent = fmtVel(el.velocidade.value); });
el.velocidade.addEventListener("change", aplicarAjustes);
el.voz.addEventListener("change", aplicarAjustes);
el.pausas.addEventListener("change", aplicarAjustes);
el.btnNovo.addEventListener("click", fecharDocumento);
$("btn-ver-modelos").addEventListener("click", abrirPronuncia);

$("btn-baixar").addEventListener("click", () => iniciarDownload(escolhaModelo));
$("btn-cancelar-download").addEventListener("click", () => {
  Android.cancelarDownload();
  mostrar($("progresso-download"), false); mostrar($("btn-baixar"), true);
});

$("btn-pronuncia").addEventListener("click", abrirPronuncia);
$("btn-add-palavra").addEventListener("click", adicionarPalavra);
$("btn-concluir").addEventListener("click", () => dlg.close());
$("btn-ouvir-nova").addEventListener("click", (e) => {
  const palavra = $("nova-palavra").value.trim();
  const grafia = $("nova-grafia").value.trim();
  const palavras = { ...pron.palavras };
  if (palavra && grafia) palavras[palavra.toLowerCase()] = grafia;
  ouvirTeste(palavra || grafia, e.currentTarget, palavras);
});
["nova-palavra", "nova-grafia"].forEach((id) => $(id).addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); adicionarPalavra(); }
}));
dlg.querySelectorAll("[data-fechar]").forEach((b) => b.addEventListener("click", () => dlg.close()));
dlg.addEventListener("close", pararTeste);

/* ------------------------------ Inicialização ------------------------------- */

(function iniciar() {
  for (const v of JSON.parse(Android.vozes())) el.voz.add(new Option(v.nome, v.id));
  el.voz.value = prefs.get("voz", "pf_dora");
  if (!el.voz.value) el.voz.value = "pf_dora";
  el.velocidade.value = prefs.get("velocidade", "1");
  el.pausas.value = prefs.get("pausas", "1");
  el.velValor.textContent = fmtVel(el.velocidade.value);
  const info = infoModelo();
  if (info.pronto) tela("entrada"); else abrirPreparo();
  Android.ajustar(el.voz.value, Number(el.velocidade.value), Number(el.pausas.value));
  Android.pronto();
})();
