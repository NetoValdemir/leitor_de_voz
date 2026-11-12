"""Extração estruturada de texto de PDFs, páginas web e TXT.

O objetivo é reconstruir a estrutura real do documento — título, subtítulos,
parágrafos, itens de lista, notas — para que a leitura em voz alta tenha as
pausas e entonações certas, em vez de um texto corrido.

Cada documento vira uma lista de blocos: {"tipo", "texto", "nivel"}, onde
tipo ∈ titulo | h | p | item | citacao | nota.
"""

from __future__ import annotations

import io
import re
import statistics
from collections import Counter
from dataclasses import dataclass, field

import httpx
import trafilatura

try:
    import pymupdf  # PyMuPDF: dá acesso a fontes, tamanhos e posições
except ImportError:  # pragma: no cover
    pymupdf = None

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0 Safari/537.36 LeitorPT/1.0"
)
TAMANHO_MAXIMO = 60 * 1024 * 1024  # 60 MB


class ErroExtracao(Exception):
    pass


def bloco(tipo: str, texto: str, nivel: int = 0) -> dict:
    return {"tipo": tipo, "texto": texto, "nivel": nivel}


# ==========================================================================
# Obtenção do conteúdo
# ==========================================================================

def baixar_url(url: str) -> tuple[bytes, str, str]:
    """Baixa a URL e devolve (conteúdo, content-type, url final)."""
    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        try:
            return baixar_url("https://" + url)
        except ErroExtracao:
            return baixar_url("http://" + url)
    try:
        with httpx.Client(
            follow_redirects=True,
            timeout=httpx.Timeout(30.0),
            headers={"User-Agent": USER_AGENT, "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.5"},
        ) as cliente:
            with cliente.stream("GET", url) as resp:
                resp.raise_for_status()
                partes, total = [], 0
                for parte in resp.iter_bytes():
                    total += len(parte)
                    if total > TAMANHO_MAXIMO:
                        raise ErroExtracao("O arquivo é grande demais (limite de 60 MB).")
                    partes.append(parte)
                return b"".join(partes), resp.headers.get("content-type", ""), str(resp.url)
    except httpx.HTTPStatusError as e:
        raise ErroExtracao(f"O site respondeu com erro {e.response.status_code}.") from e
    except httpx.HTTPError as e:
        raise ErroExtracao(f"Não foi possível acessar a URL ({e.__class__.__name__}).") from e


def extrair(conteudo: bytes, content_type: str = "", nome: str = "") -> tuple[str, list[dict]]:
    """Detecta o tipo e devolve (título, blocos)."""
    ct = content_type.lower()
    nome_l = nome.lower()
    import extracao_word  # importado aqui para evitar importação circular

    if extracao_word.eh_docx(conteudo):
        return extracao_word.extrair_docx(conteudo, nome)
    if extracao_word.eh_doc(conteudo):
        return extracao_word.extrair_doc(conteudo, nome)
    if nome_l.endswith((".doc", ".docx")) or "msword" in ct or "wordprocessingml" in ct:
        raise ErroExtracao("O arquivo tem extensão do Word, mas o conteúdo não é um documento do Word válido.")
    if conteudo[:5] == b"%PDF-" or "pdf" in ct or nome_l.endswith(".pdf"):
        return extrair_pdf(conteudo, nome)
    if nome_l.endswith(".txt") or ct.startswith("text/plain"):
        return extrair_txt(_decodificar(conteudo), nome)
    return extrair_html(_decodificar(conteudo), nome)


def _decodificar(conteudo: bytes) -> str:
    for cod in ("utf-8", "cp1252", "latin-1"):
        try:
            return conteudo.decode(cod)
        except UnicodeDecodeError:
            continue
    return conteudo.decode("utf-8", errors="replace")


def _titulo_de_nome(nome: str) -> str:
    base = re.sub(r"\.[a-z0-9]{2,4}$", "", nome.rsplit("/", 1)[-1], flags=re.I)
    return re.sub(r"[_\-]+", " ", base).strip() or "Documento"


def limpar_espacos(texto: str) -> str:
    texto = texto.replace("­", "").replace("ﬁ", "fi").replace("ﬂ", "fl")
    texto = texto.replace("ﬀ", "ff").replace("ﬃ", "ffi").replace("ﬄ", "ffl")
    texto = re.sub(r"[‐‑‒⁃−]", "-", texto)  # hífens especiais
    texto = re.sub(r"[-�​-‍﻿]", "", texto)  # uso privado / invisíveis
    return re.sub(r"\s+", " ", texto).strip()


MARCADOR_LISTA = re.compile(
    r"^(?:[•●▪◦‣∙○■□➢➤►▶✓✔\-–—*]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])\s+"
)
MARCADOR_SIMBOLO = re.compile(r"^[•●▪◦‣∙○■□➢➤►▶✓✔*]\s*")


def _juntar(a: str, b: str) -> str:
    """Junta duas linhas, desfazendo a hifenização de fim de linha."""
    if not a:
        return b
    if re.search(r"\w[-‐‑­]$", a) and b[:1].islower():
        return a[:-1] + b
    return a + " " + b


# ==========================================================================
# HTML (estrutura vinda do trafilatura em XML)
# ==========================================================================

def extrair_html(html: str, url: str = "") -> tuple[str, list[dict]]:
    from lxml import etree

    xml = trafilatura.extract(
        html,
        url=url or None,
        output_format="xml",
        include_comments=False,
        include_tables=True,
        include_formatting=False,
        favor_recall=True,
    )
    meta = trafilatura.extract_metadata(html, default_url=url or None)
    titulo = (meta.title if meta and meta.title else None) or _titulo_de_nome(url)
    if not xml:
        raise ErroExtracao(
            "Não encontrei texto legível nesta página. Ela pode depender de JavaScript "
            "ou exigir login."
        )

    raiz = etree.fromstring(xml.encode("utf-8"), etree.XMLParser(recover=True))
    main = raiz.find("main")
    if main is None:
        main = raiz
    blocos: list[dict] = []

    def texto_de(el) -> str:
        return limpar_espacos(" ".join(el.itertext()))

    def visitar(el) -> None:
        tag = el.tag if isinstance(el.tag, str) else ""
        if tag == "head":
            rend = el.get("rend") or "h2"
            nivel = int(rend[1]) if re.fullmatch(r"h[1-6]", rend) else 2
            t = texto_de(el)
            if t:
                blocos.append(bloco("h", t, nivel))
        elif tag == "p":
            t = texto_de(el)
            if t:
                blocos.append(bloco("p", t))
        elif tag == "list":
            for item in el.findall("item"):
                t = texto_de(item)
                if t:
                    blocos.append(bloco("item", MARCADOR_SIMBOLO.sub("", t)))
        elif tag == "quote":
            t = texto_de(el)
            if t:
                blocos.append(bloco("citacao", t))
        elif tag == "table":
            for linha in el.iter("row"):
                celulas = [texto_de(c) for c in linha.findall("cell")]
                celulas = [c for c in celulas if c]
                if celulas:
                    blocos.append(bloco("item", _unir_celulas(celulas)))
        elif tag in ("code", "graphic", "ref", "comments"):
            return
        else:
            if el.text and el.text.strip() and tag not in ("main", "doc", "div"):
                blocos.append(bloco("p", texto_de(el)))
                return
            for filho in el:
                visitar(filho)

    for filho in main:
        visitar(filho)

    if sum(len(b["texto"]) for b in blocos) < 40:
        raise ErroExtracao(
            "Não encontrei texto legível nesta página. Ela pode depender de JavaScript "
            "ou exigir login."
        )
    return titulo, _marcar_titulo(titulo, blocos)


def _unir_celulas(celulas: list[str]) -> str:
    if len(celulas) == 2 and len(celulas[0].split()) <= 4 and not re.search(r"[.:;!?]$", celulas[0]):
        return f"{celulas[0]}: {celulas[1]}"
    return "; ".join(celulas)


def _marcar_titulo(titulo: str, blocos: list[dict]) -> list[dict]:
    """Garante que o documento comece pelo título (que também é lido)."""
    norm = lambda s: re.sub(r"\W+", " ", s).strip().lower()  # noqa: E731
    for b in blocos[:3]:
        if b["tipo"] == "h" and norm(b["texto"]) and (
            norm(b["texto"]) == norm(titulo) or norm(b["texto"]) in norm(titulo)
        ):
            b["tipo"], b["nivel"] = "titulo", 1
            return blocos
    return [bloco("titulo", titulo, 1)] + blocos


# ==========================================================================
# TXT
# ==========================================================================

def extrair_txt(texto: str, nome: str = "") -> tuple[str, list[dict]]:
    texto = texto.replace("\r\n", "\n").replace("\r", "\n")
    blocos: list[dict] = []
    for trecho in re.split(r"\n\s*\n", texto):
        linhas = [l.strip() for l in trecho.split("\n") if l.strip()]
        if not linhas:
            continue
        # Linha única, curta e sem pontuação final → título
        if len(linhas) == 1 and len(linhas[0]) < 90 and not re.search(r"[.;,!?]$", linhas[0]):
            blocos.append(bloco("h", limpar_espacos(linhas[0]), 2))
            continue
        atual = ""
        for l in linhas:
            if MARCADOR_LISTA.match(l):
                if atual:
                    blocos.append(bloco("p", limpar_espacos(atual)))
                blocos.append(bloco("item", limpar_espacos(MARCADOR_SIMBOLO.sub("", l))))
                atual = ""
            elif blocos and blocos[-1]["tipo"] == "item" and not atual and l[:1].islower():
                blocos[-1]["texto"] = _juntar(blocos[-1]["texto"], l)
            else:
                atual = _juntar(atual, l)
        if atual:
            blocos.append(bloco("p", limpar_espacos(atual)))
    titulo = _titulo_de_nome(nome)
    if blocos and blocos[0]["tipo"] == "h":
        blocos[0]["tipo"], blocos[0]["nivel"] = "titulo", 1
        titulo = blocos[0]["texto"]
    return titulo, blocos


# ==========================================================================
# PDF — análise de layout com PyMuPDF
# ==========================================================================

@dataclass
class Linha:
    """Uma linha visual (pode juntar vários trechos na mesma altura)."""
    pagina: int
    x0: float
    y0: float
    x1: float
    y1: float
    tamanho: float          # tamanho de fonte dominante
    negrito: float          # fração de caracteres em negrito
    segmentos: list = field(default_factory=list)  # [(x0, x1, texto)]
    texto: str = ""
    tipo: str = "p"
    nivel: int = 0


def extrair_pdf(conteudo: bytes, nome: str = "") -> tuple[str, list[dict]]:
    if pymupdf is None:
        return _extrair_pdf_simples(conteudo, nome)
    try:
        doc = pymupdf.open(stream=conteudo, filetype="pdf")
        if doc.needs_pass and not doc.authenticate(""):
            raise ErroExtracao("Este PDF é protegido por senha.")
    except ErroExtracao:
        raise
    except Exception as e:  # noqa: BLE001
        raise ErroExtracao("Não foi possível abrir o PDF (arquivo corrompido ou protegido).") from e

    linhas: list[Linha] = []
    alturas: dict[int, float] = {}
    for num, pagina in enumerate(doc):
        alturas[num] = pagina.rect.height
        linhas.extend(_linhas_da_pagina(pagina, num))

    meta_titulo = (doc.metadata or {}).get("title", "") or ""
    n_paginas = doc.page_count
    doc.close()

    linhas = _remover_cabecalhos_e_rodapes(linhas, alturas, n_paginas)
    linhas = [l for l in linhas if re.search(r"\w", l.texto)]
    if sum(len(l.texto) for l in linhas) < 20:
        raise ErroExtracao(
            "Este PDF não possui texto selecionável (provavelmente é uma imagem "
            "digitalizada). Seria necessário OCR para lê-lo."
        )

    corpo = _tamanho_do_corpo(linhas)
    _classificar(linhas, corpo)
    blocos = _montar_blocos(linhas, corpo)
    titulo = _escolher_titulo(blocos, linhas, corpo, meta_titulo, nome)
    return titulo, blocos


# ---------------------------- leitura das linhas --------------------------

def _linhas_da_pagina(pagina, num: int) -> list[Linha]:
    dados = pagina.get_text("rawdict", flags=pymupdf.TEXT_PRESERVE_WHITESPACE | pymupdf.TEXT_MEDIABOX_CLIP)
    resultado: list[Linha] = []
    for b in dados["blocks"]:
        if b.get("type") != 0:
            continue
        linhas_bloco: list[Linha] = []
        for l in b["lines"]:
            dx, dy = l.get("dir", (1, 0))
            if abs(dy) > 0.2:  # texto vertical/rotacionado (marcas d'água, margens)
                continue
            spans = [s for s in l["spans"] if s["chars"]]
            if not spans:
                continue
            # tamanho dominante da linha (ponderado por caracteres)
            cont = Counter()
            for s in spans:
                cont[round(s["size"] * 2) / 2] += sum(1 for c in s["chars"] if not c["c"].isspace())
            dominante = cont.most_common(1)[0][0] if cont else spans[0]["size"]

            chars = []
            for s in spans:
                pequeno = s["size"] < dominante * 0.8
                texto_span = "".join(c["c"] for c in s["chars"]).strip()
                # índice de nota de rodapé sobrescrito (ex.: "manutenção¹")
                if pequeno and re.fullmatch(r"[\d*†‡,]{1,4}", texto_span) and len(spans) > 1:
                    continue
                negrito = bool(s["flags"] & 16) or bool(re.search(r"bold|black|heavy|semibold", s["font"], re.I))
                for c in s["chars"]:
                    chars.append((c["c"], c["bbox"][0], c["bbox"][2], s["size"], negrito))
            if not chars:
                continue
            texto = _texto_dos_chars(chars)
            if not texto.strip():
                continue
            visiveis = [c for c in chars if not c[0].isspace()] or chars
            negr = sum(1 for c in visiveis if c[4]) / len(visiveis)
            x0, y0, x1, y1 = l["bbox"]
            linhas_bloco.append(Linha(num, x0, y0, x1, y1, dominante, negr, [(x0, x1, texto)]))

        resultado.extend(_unir_mesma_altura(linhas_bloco))
    return resultado


def _texto_dos_chars(chars: list[tuple]) -> str:
    """Monta o texto, corrigindo texto com letras espaçadas ("R E L A T Ó R I O")."""
    texto = "".join(c[0] for c in chars)
    tokens = texto.split()
    if len(tokens) >= 4 and sum(1 for t in tokens if len(t) == 1) / len(tokens) > 0.7:
        espacos = [c[2] - c[1] for c in chars if c[0] == " "]
        largura_comum = statistics.median(espacos) if espacos else 0
        saida, anterior_fim = [], None
        for c, x0, x1, _tam, _n in chars:
            if c == " ":
                folga = x0 - anterior_fim if anterior_fim is not None else 0
                if folga > 0.3 or (x1 - x0) > largura_comum * 1.25:
                    saida.append(" ")
            else:
                saida.append(c)
            anterior_fim = x1
        texto = re.sub(r" +", " ", "".join(saida))
    return texto


def _unir_mesma_altura(linhas: list[Linha]) -> list[Linha]:
    """O PyMuPDF separa trechos da mesma linha visual (rótulo e valor, item e
    página do sumário). Junta-os numa linha só, guardando os segmentos."""
    unidas: list[Linha] = []
    for l in linhas:
        alvo = None
        for u in unidas[-3:]:
            sobrepoe = min(u.y1, l.y1) - max(u.y0, l.y0)
            if sobrepoe > 0.5 * min(u.y1 - u.y0, l.y1 - l.y0) and (l.x0 >= u.x1 - 1 or l.x1 <= u.x0 + 1):
                alvo = u
                break
        if alvo is None:
            unidas.append(l)
            continue
        alvo.segmentos = sorted(alvo.segmentos + l.segmentos)
        alvo.x0, alvo.x1 = min(alvo.x0, l.x0), max(alvo.x1, l.x1)
        alvo.y0, alvo.y1 = min(alvo.y0, l.y0), max(alvo.y1, l.y1)
        alvo.tamanho = max(alvo.tamanho, l.tamanho)
        alvo.negrito = (alvo.negrito + l.negrito) / 2
    for u in unidas:
        u.texto = _texto_da_linha(u)
    return unidas


def _texto_da_linha(l: Linha) -> str:
    segs = [(a, b, limpar_espacos(t)) for a, b, t in l.segmentos]
    segs = [s for s in segs if s[2]]
    if not segs:
        return ""
    # Pontilhado de sumário: "Introdução ........ 3"
    segs = [(a, b, re.sub(r"\s*[.·…_]{3,}[\s.·…_]*(\d+)?$", "", t)) for a, b, t in segs]
    segs = [s for s in segs if s[2]]
    if len(segs) >= 2 and re.fullmatch(r"\d{1,4}|[ivxlcdm]{1,6}", segs[-1][2], re.I):
        if segs[-1][0] - segs[-2][1] > 40:  # número de página afastado à direita
            segs = segs[:-1]
            l.x1 = segs[-1][1]
    if len(segs) == 1:
        return segs[0][2]
    partes = [segs[0][2]]
    for (a0, a1, _ta), (b0, _b1, tb) in zip(segs, segs[1:]):
        folga = b0 - a1
        if folga > l.tamanho * 1.5:
            partes.append("\x00")  # separador de coluna/tabela
        partes.append(tb)
    texto = " ".join(partes)
    colunas = [c.strip() for c in texto.split("\x00")]
    if len(colunas) == 1:
        return colunas[0]
    return _unir_celulas(colunas)


def _remover_cabecalhos_e_rodapes(linhas: list[Linha], alturas: dict, n_paginas: int) -> list[Linha]:
    def zona(l: Linha) -> bool:
        h = alturas[l.pagina]
        return l.y1 < h * 0.08 or l.y0 > h * 0.92

    def norm(t: str) -> str:
        return re.sub(r"\d+", "#", t.lower()).strip()

    repetidas = set()
    if n_paginas >= 2:
        contagem = Counter()
        for pag in range(n_paginas):
            contagem.update({norm(l.texto) for l in linhas if l.pagina == pag and zona(l)})
        minimo = 2 if n_paginas <= 4 else max(3, int(n_paginas * 0.3))
        repetidas = {t for t, n in contagem.items() if n >= minimo}

    numero_pagina = re.compile(r"(p[áa]g(ina)?\.?\s*)?[-–—]?\s*\d{1,4}\s*[-–—]?(\s*(de|/|of)\s*\d{1,4})?", re.I)
    return [
        l for l in linhas
        if not (zona(l) and (norm(l.texto) in repetidas or numero_pagina.fullmatch(l.texto.strip())))
    ]


def _tamanho_do_corpo(linhas: list[Linha]) -> float:
    cont = Counter()
    for l in linhas:
        cont[l.tamanho] += len(l.texto)
    return cont.most_common(1)[0][0]


# ------------------------------ classificação -----------------------------

def _classificar(linhas: list[Linha], corpo: float) -> None:
    tamanhos_titulo = sorted(
        {l.tamanho for l in linhas if l.tamanho >= corpo * 1.12 and len(l.texto) < 200}, reverse=True
    )
    for l in linhas:
        t = l.texto
        if l.tamanho >= corpo * 1.12 and len(t) < 200:
            l.tipo = "h"
            l.nivel = min(2 + tamanhos_titulo.index(l.tamanho), 4) if l.tamanho in tamanhos_titulo else 3
        elif (
            l.negrito > 0.85 and l.tamanho >= corpo * 0.95 and len(t) < 120
            and not re.search(r"[.;,]$", t) and len(t.split()) <= 14
        ):
            l.tipo, l.nivel = "h", 4
        elif l.tamanho < corpo * 0.85:
            l.tipo = "nota"
        else:
            l.tipo = "p"


def _bordas(valores: list[float], minimo: int = 3) -> list[float]:
    """Encontra margens recorrentes (ex.: borda direita de cada coluna)."""
    cont = Counter(round(v / 3) * 3 for v in valores)
    return sorted(v for v, n in cont.items() if n >= minimo)


def _montar_blocos(linhas: list[Linha], corpo: float) -> list[dict]:
    texto_corrido = [l for l in linhas if l.tipo in ("p", "nota")] or linhas
    # Margens calculadas por página (o layout pode mudar: 1 coluna, 2 colunas…)
    bordas_dir_pag: dict[int, list[float]] = {}
    bordas_esq_pag: dict[int, list[float]] = {}
    for pag in {l.pagina for l in linhas}:
        da_pagina = [l for l in texto_corrido if l.pagina == pag] or [l for l in linhas if l.pagina == pag]
        minimo = max(3, len(da_pagina) // 10)
        direita = set(_bordas([l.x1 for l in da_pagina], minimo)) | {round(max(l.x1 for l in da_pagina) / 3) * 3}
        bordas_dir_pag[pag] = sorted(direita)
        # margens esquerdas: só linhas "cheias" (que vão até a borda direita), para
        # que itens de lista recuados não sejam confundidos com uma coluna
        cheias = [l for l in da_pagina if any(abs(l.x1 - b) <= 4 for b in direita)]
        esquerdas = _bordas([l.x0 for l in cheias], 1) or [min(l.x0 for l in da_pagina)]
        # descarta "margens" que são só recuo de primeira linha (logo à direita de outra)
        bordas_esq_pag[pag] = [
            b for b in esquerdas if not any(0 < b - o <= 3 * corpo for o in esquerdas)
        ]

    entrelinhas = [
        b.y0 - a.y0 for a, b in zip(linhas, linhas[1:])
        if a.pagina == b.pagina and a.tipo == b.tipo == "p" and 0 < b.y0 - a.y0 < corpo * 3
    ]
    entrelinha = statistics.median(entrelinhas) if entrelinhas else corpo * 1.4

    def borda_direita(l: Linha) -> float:
        cand = [b for b in bordas_dir_pag[l.pagina] if b >= l.x1 - 4]
        return min(cand) if cand else l.x1

    def margem_esquerda(l: Linha) -> float:
        cand = [b for b in bordas_esq_pag[l.pagina] if b <= l.x0 + 4]
        return max(cand) if cand else l.x0

    def terminou_cedo(l: Linha) -> bool:
        direita = borda_direita(l)
        largura = max(direita - margem_esquerda(l), 1)
        return direita - l.x1 > max(3 * l.tamanho, 0.15 * largura) and not re.search(r"[-‐­]$", l.texto)

    blocos: list[dict] = []
    atual: dict | None = None
    anterior: Linha | None = None

    for l in linhas:
        recuo = l.x0 - margem_esquerda(l)
        texto = l.texto
        marcador = bool(MARCADOR_LISTA.match(texto))
        tipo = l.tipo
        if tipo == "p" and (marcador or recuo >= 2 * l.tamanho):
            tipo = "item" if marcador or (anterior is not None and terminou_cedo(anterior)) or _proxima_alinhada(l, linhas) else "p"

        novo = True
        if atual is not None and anterior is not None:
            mesma_coluna = anterior.pagina == l.pagina and l.y0 > anterior.y0
            salto = l.y0 - anterior.y0
            if atual["tipo"] == "h" and tipo == "h":
                # títulos em várias linhas
                novo = not (
                    mesma_coluna and abs(l.tamanho - anterior.tamanho) < 0.6
                    and salto < l.tamanho * 2.2 and not re.search(r"[.!?]$", atual["texto"])
                )
            elif tipo in ("h",) or atual["tipo"] in ("h", "titulo"):
                novo = True
            elif marcador:
                novo = True
            elif abs(l.tamanho - anterior.tamanho) > 1.0 and tipo != atual["tipo"]:
                novo = True
            elif terminou_cedo(anterior):
                novo = True
            elif mesma_coluna and salto > entrelinha * 1.45:
                novo = True  # espaço extra entre parágrafos
            elif mesma_coluna and l.x0 - anterior.x0 > 0.8 * l.tamanho and not MARCADOR_LISTA.match(atual["texto"]) and atual["tipo"] != "item":
                novo = True  # recuo de primeira linha
            elif atual["tipo"] == "item" and tipo == "item" and abs(l.x0 - anterior.x0) < 2 and terminou_cedo(anterior):
                novo = True
            else:
                novo = False
            # Continuação entre colunas/páginas: frase inacabada + minúscula
            if novo and tipo == atual["tipo"] == "p" and not mesma_coluna and l.texto[:1].islower() \
                    and not re.search(r"[.!?:…]$", atual["texto"]):
                novo = False

        if novo:
            if atual:
                blocos.append(atual)
            atual = {"tipo": tipo, "texto": texto, "nivel": l.nivel, "_tam": l.tamanho}
        else:
            atual["texto"] = _juntar(atual["texto"], texto)
            if atual["tipo"] == "item" and tipo == "p":
                pass  # continuação de item com recuo pendente
        anterior = l
    if atual:
        blocos.append(atual)

    resultado = []
    em_sumario = False
    for b in blocos:
        t = limpar_espacos(b["texto"])
        # Entradas de sumário viram itens (pausas curtas, sem cara de parágrafo)
        if b["tipo"] == "h":
            em_sumario = bool(re.fullmatch(r"(sum[áa]rio|[íi]ndice( geral)?|conte[úu]do|contents)", t, re.I))
        elif em_sumario and b["tipo"] == "p" and len(t) < 120 and not re.search(r"[.!?]$", t):
            b["tipo"] = "item"
        elif em_sumario:
            em_sumario = False
        if b["tipo"] == "item":
            t = MARCADOR_SIMBOLO.sub("", t)
        if not re.search(r"\w", t):
            continue
        resultado.append({"tipo": b["tipo"], "texto": t, "nivel": b["nivel"], "_tam": b["_tam"]})
    return resultado


def _proxima_alinhada(l: Linha, linhas: list[Linha]) -> bool:
    """Linha recuada seguida de outra no mesmo recuo → provável lista."""
    i = linhas.index(l)
    if i + 1 < len(linhas):
        p = linhas[i + 1]
        return p.pagina == l.pagina and abs(p.x0 - l.x0) < 2 and p.tipo == "p"
    return False


def _escolher_titulo(blocos: list[dict], linhas: list[Linha], corpo: float, meta: str, nome: str) -> str:
    # O maior texto do início do documento costuma ser o título
    inicio = blocos[:12]
    maior = max(inicio, key=lambda b: b["_tam"], default=None)
    tamanho_maior = maior["_tam"] if maior else 0
    for b in blocos:
        b.pop("_tam", None)
    if maior is not None and tamanho_maior >= corpo * 1.4 and len(maior["texto"]) < 160:
        maior["tipo"], maior["nivel"] = "titulo", 1
        return maior["texto"]
    meta = meta.strip()
    if meta and len(meta) > 3 and not re.search(r"\.(docx?|pdf|odt|tex|indd)$|^untitled|^microsoft", meta, re.I):
        blocos.insert(0, bloco("titulo", meta, 1))
        return meta
    return _titulo_de_nome(nome) or "Documento PDF"


# ---------------------- alternativa sem PyMuPDF (pypdf) --------------------

def _extrair_pdf_simples(conteudo: bytes, nome: str) -> tuple[str, list[dict]]:
    from pypdf import PdfReader

    try:
        leitor = PdfReader(io.BytesIO(conteudo))
        if leitor.is_encrypted:
            leitor.decrypt("")
        texto = "\n\n".join((p.extract_text() or "") for p in leitor.pages)
    except Exception as e:  # noqa: BLE001
        raise ErroExtracao("Não foi possível abrir o PDF (arquivo corrompido ou protegido).") from e
    titulo, blocos = extrair_txt(texto, nome)
    if sum(len(b["texto"]) for b in blocos) < 20:
        raise ErroExtracao("Este PDF não possui texto selecionável. Seria necessário OCR para lê-lo.")
    return titulo, blocos


# ==========================================================================
# Divisão em frases
# ==========================================================================

ABREVIACOES = {
    "sr", "sra", "srta", "dr", "dra", "prof", "profa", "exmo", "exma", "ilmo", "ilma",
    "art", "arts", "inc", "cap", "pág", "pag", "págs", "p", "pp", "fl", "fls", "vol",
    "n", "nº", "no", "ed", "etc", "ex", "obs", "av", "r", "tel", "cf", "op", "cit",
    "jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez",
    "sto", "sta", "gen", "cel", "ten", "sgt", "min", "máx", "mín", "aprox", "séc",
    "ltda", "cia", "mr", "mrs", "vs", "fig", "tab", "eq", "id", "ibid", "s.a", "a.c", "d.c",
    "dept", "depto", "univ", "coord", "org", "orgs", "trad", "rev", "reimpr", "aux",
}

_FIM = re.compile(r"([.!?…]+[\"'”»)\]]*)\s+(?=[\"'“«(\[]?[A-ZÁÉÍÓÚÂÊÔÃÕÀÇ0-9¿¡—–-])")
MAX_FRASE = 280


def dividir_frases(paragrafo: str) -> list[str]:
    frases: list[str] = []
    inicio = 0
    for m in _FIM.finditer(paragrafo):
        trecho = paragrafo[inicio:m.end(1)]
        ultima = re.search(r"(\S+)\.$", trecho)
        if m.group(1).startswith(".") and ultima:
            palavra = ultima.group(1).lower().lstrip("(\"'“«")
            if palavra in ABREVIACOES or len(palavra) == 1 or re.fullmatch(r"(\w\.)+\w", palavra):
                continue
            if re.fullmatch(r"\d+(\.\d+)*", palavra) and trecho.strip() == ultima.group(0):
                continue  # "2.1." sozinho = numeração de seção
        frases.append(trecho.strip())
        inicio = m.end()
    resto = paragrafo[inicio:].strip()
    if resto:
        frases.append(resto)

    resultado: list[str] = []
    for f in frases:
        resultado.extend(_quebrar_longa(f))
    return _juntar_curtas([f for f in resultado if f])


def _juntar_curtas(frases: list[str]) -> list[str]:
    """Fragmentos muito curtos ("Sim.", "Fig. 2") soam picotados sozinhos."""
    saida: list[str] = []
    for f in frases:
        if saida and len(f) < 12 and len(saida[-1]) + len(f) < MAX_FRASE:
            saida[-1] = saida[-1] + " " + f
        else:
            saida.append(f)
    return saida


def _quebrar_longa(frase: str) -> list[str]:
    """Frases muito longas atrasam o início do áudio; quebra em pausas naturais."""
    if len(frase) <= MAX_FRASE:
        return [frase]
    meio = len(frase) // 2
    melhores = [m.end() for m in re.finditer(r"[;:,—–]\s", frase)]
    if not melhores:
        melhores = [m.end() for m in re.finditer(r"\s(?=(e|ou|mas|porém|que|quando|onde|pois)\s)", frase)]
    if not melhores:
        melhores = [m.end() for m in re.finditer(r"\s", frase)]
    if not melhores:
        return [frase]
    corte = min(melhores, key=lambda p: abs(p - meio))
    return _quebrar_longa(frase[:corte].strip()) + _quebrar_longa(frase[corte:].strip())


# ==========================================================================
# Estrutura final enviada ao navegador
# ==========================================================================

# Pausa (s, na velocidade 1×) depois do fim de cada bloco
PAUSAS = {"titulo": 1.1, "h": 0.95, "p": 0.8, "item": 0.45, "citacao": 0.8, "nota": 0.5}


def pausa_fim_de_frase(frase: str) -> float:
    """Pausa depois de uma frase, conforme o sinal que a encerra."""
    f = frase.rstrip("\"'”»)] ")
    if f.endswith(("…", "...")):
        return 0.6
    if f.endswith(("?", "!")):
        return 0.55
    if f.endswith("."):
        return 0.45
    if f.endswith((":", ";")):
        return 0.4
    return 0.35


def estruturar(titulo: str, blocos: list[dict]) -> dict:
    saida = []
    for b in blocos:
        if b["tipo"] in ("titulo", "h"):
            frases = [b["texto"]] if len(b["texto"]) <= MAX_FRASE else dividir_frases(b["texto"])
        else:
            frases = dividir_frases(b["texto"])
        if frases:
            pausas = [pausa_fim_de_frase(f) for f in frases]
            pausas[-1] = PAUSAS.get(b["tipo"], 0.8)
            saida.append({"tipo": b["tipo"], "nivel": b.get("nivel", 0), "frases": frases, "pausas": pausas})
    # pausa maior antes de títulos e ao sair de uma lista
    for atual, proximo in zip(saida, saida[1:]):
        if proximo["tipo"] in ("h", "titulo"):
            atual["pausas"][-1] = max(atual["pausas"][-1], 1.0)
        elif atual["tipo"] == "item" and proximo["tipo"] != "item":
            atual["pausas"][-1] = 0.7
    total = sum(len(b["frases"]) for b in saida)
    palavras = sum(len(f.split()) for b in saida for f in b["frases"])
    return {"titulo": titulo, "blocos": saida, "total_frases": total, "palavras": palavras}
