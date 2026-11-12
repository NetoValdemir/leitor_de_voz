"""Leitura de documentos do Word: DOCX (2007+) e DOC (97-2003).

DOCX: lido com python-docx, preservando a estrutura pelos estilos (Título,
Título 1/2/3…, listas, citações), pelo nível de tópico e pela formatação
(parágrafos curtos em negrito ou com fonte maior viram títulos).

DOC: se o LibreOffice estiver instalado, o arquivo é convertido para DOCX
(e lido com estrutura); senão, o texto é extraído direto do formato binário
(tabela de peças da especificação [MS-DOC]) e a estrutura é deduzida.
Cabeçalhos, rodapés, notas, campos e imagens não são lidos.
"""

from __future__ import annotations

import io
import os
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from collections import Counter
from pathlib import Path

import extracao as ex


def eh_docx(conteudo: bytes) -> bool:
    if conteudo[:2] != b"PK":
        return False
    try:
        with zipfile.ZipFile(io.BytesIO(conteudo)) as z:
            return "word/document.xml" in z.namelist()
    except zipfile.BadZipFile:
        return False


def eh_doc(conteudo: bytes) -> bool:
    return conteudo[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"  # contêiner OLE (Office 97-2003)


# ==========================================================================
# DOCX
# ==========================================================================

_ESTILO_TITULO = re.compile(r"^(heading|t[íi]tulo|überschrift|titre)\s*(\d)$", re.I)
_ESTILO_LISTA = re.compile(r"list|lista|marcador|bullet|numera", re.I)
_ESTILO_CITACAO = re.compile(r"quote|cita", re.I)
_ESTILO_IGNORAR = re.compile(r"^(toc|sum[áa]rio)\s*\d*$|caption|legenda|footnote|nota de rodap", re.I)


def extrair_docx(conteudo: bytes, nome: str = "") -> tuple[str, list[dict]]:
    try:
        from docx import Document
        from docx.oxml.ns import qn
        from docx.table import Table
        from docx.text.paragraph import Paragraph
    except ImportError as e:
        raise ex.ErroExtracao("Para ler arquivos do Word, instale: pip install python-docx") from e

    try:
        doc = Document(io.BytesIO(conteudo))
    except Exception as e:  # noqa: BLE001
        raise ex.ErroExtracao("Não foi possível abrir o documento do Word (arquivo corrompido ou protegido).") from e

    # Percorre o corpo na ordem real (parágrafos e tabelas intercalados)
    itens: list = []
    for filho in doc.element.body.iterchildren():
        if filho.tag == qn("w:p"):
            itens.append(Paragraph(filho, doc))
        elif filho.tag == qn("w:tbl"):
            itens.append(Table(filho, doc))

    # Tamanho de fonte do corpo do texto (para reconhecer títulos "manuais")
    tamanhos = Counter()
    for item in itens:
        if isinstance(item, Paragraph):
            for r in item.runs:
                if r.font.size and r.text.strip():
                    tamanhos[r.font.size.pt] += len(r.text)
    corpo = tamanhos.most_common(1)[0][0] if tamanhos else None

    blocos: list[dict] = []
    for item in itens:
        if isinstance(item, Table):
            blocos.extend(_tabela(item))
            continue
        b = _paragrafo(item, corpo, qn)
        if b:
            blocos.append(b)

    blocos = [b for b in blocos if re.search(r"\w", b["texto"])]
    if sum(len(b["texto"]) for b in blocos) < 5:
        raise ex.ErroExtracao("O documento do Word não tem texto para ler.")

    titulo = (doc.core_properties.title or "").strip()
    if any(b["tipo"] == "titulo" for b in blocos):
        titulo = next(b["texto"] for b in blocos if b["tipo"] == "titulo")
    elif titulo:
        blocos = ex._marcar_titulo(titulo, blocos)
    else:
        primeiro_h = next((b for b in blocos[:5] if b["tipo"] == "h"), None)
        if primeiro_h:
            primeiro_h["tipo"], primeiro_h["nivel"] = "titulo", 1
            titulo = primeiro_h["texto"]
        else:
            titulo = ex._titulo_de_nome(nome)
    return titulo, blocos


def _nivel_de_topico(paragrafo, qn) -> int | None:
    """Nível de tópico (w:outlineLvl) do parágrafo ou do seu estilo: 0 = Título 1."""
    for origem in (paragrafo._p.pPr, getattr(paragrafo.style, "element", None)):
        if origem is None:
            continue
        ppr = origem if origem.tag == qn("w:pPr") else origem.find(qn("w:pPr"))
        if ppr is None:
            continue
        nivel = ppr.find(qn("w:outlineLvl"))
        if nivel is not None:
            valor = int(nivel.get(qn("w:val"), "9"))
            if valor < 9:
                return valor
    return None


def _tem_numeracao(paragrafo, qn) -> bool:
    ppr = paragrafo._p.pPr
    if ppr is not None and ppr.find(qn("w:numPr")) is not None:
        return True
    estilo = getattr(paragrafo.style, "element", None)
    if estilo is not None:
        sppr = estilo.find(qn("w:pPr"))
        return sppr is not None and sppr.find(qn("w:numPr")) is not None
    return False


def _paragrafo(p, corpo: float | None, qn) -> dict | None:
    texto = ex.limpar_espacos(p.text.replace("\t", " "))
    if not texto:
        return None
    estilo = (p.style.name if p.style is not None else "") or ""

    if _ESTILO_IGNORAR.search(estilo):
        return None
    if re.fullmatch(r"title|t[íi]tulo", estilo, re.I):
        return ex.bloco("titulo", texto, 1)
    if re.fullmatch(r"subtitle|subt[íi]tulo", estilo, re.I):
        return ex.bloco("p", texto)
    m = _ESTILO_TITULO.match(estilo)
    if m:
        return ex.bloco("h", texto, min(int(m.group(2)) + 1, 4))
    nivel = _nivel_de_topico(p, qn)
    if nivel is not None and len(texto) < 200:
        return ex.bloco("h", texto, min(nivel + 2, 4))
    if _ESTILO_CITACAO.search(estilo):
        return ex.bloco("citacao", texto)
    if _ESTILO_LISTA.search(estilo) or _tem_numeracao(p, qn):
        return ex.bloco("item", ex.MARCADOR_SIMBOLO.sub("", texto))

    # Títulos "manuais": parágrafo curto, sem ponto final, todo em negrito ou com fonte maior
    corridos = [r for r in p.runs if r.text.strip()]
    if corridos and len(texto) < 120 and len(texto.split()) <= 14 and not re.search(r"[.;,]$", texto):
        negrito = all(r.bold or (r.bold is None and p.style is not None and p.style.font.bold) for r in corridos)
        maior = corpo is not None and all(r.font.size and r.font.size.pt >= corpo * 1.15 for r in corridos)
        if negrito or maior:
            return ex.bloco("h", texto, 3 if maior else 4)

    if ex.MARCADOR_LISTA.match(texto):
        return ex.bloco("item", ex.MARCADOR_SIMBOLO.sub("", texto))
    return ex.bloco("p", texto)


def _tabela(tabela) -> list[dict]:
    """Cada linha da tabela vira um item ("Rótulo: valor" quando são 2 colunas)."""
    blocos = []
    for linha in tabela.rows:
        celulas, vistas = [], set()
        for c in linha.cells:
            if id(c._tc) in vistas:  # células mescladas aparecem repetidas
                continue
            vistas.add(id(c._tc))
            t = ex.limpar_espacos(c.text)
            if t:
                celulas.append(t)
        if celulas:
            blocos.append(ex.bloco("item", ex._unir_celulas(celulas)))
    return blocos


# ==========================================================================
# DOC (Word 97-2003)
# ==========================================================================

def _soffice() -> str | None:
    caminho = shutil.which("soffice") or shutil.which("libreoffice")
    if caminho:
        return caminho
    for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), "/Applications/LibreOffice.app/Contents/MacOS"):
        if not base:
            continue
        for cand in (Path(base) / "LibreOffice" / "program" / "soffice.exe", Path(base) / "soffice"):
            if cand.exists():
                return str(cand)
    return None


def extrair_doc(conteudo: bytes, nome: str = "") -> tuple[str, list[dict]]:
    soffice = _soffice()
    if soffice:
        try:
            convertido = _converter_com_libreoffice(soffice, conteudo)
            if convertido:
                titulo, blocos = extrair_docx(convertido, nome)
                if titulo == "Documento":
                    titulo = ex._titulo_de_nome(nome)
                return titulo, blocos
        except ex.ErroExtracao:
            pass
        except Exception:  # noqa: BLE001 — cai para a leitura direta do binário
            pass
    return _extrair_doc_binario(conteudo, nome)


def _converter_com_libreoffice(soffice: str, conteudo: bytes) -> bytes | None:
    with tempfile.TemporaryDirectory(prefix="leitor-doc-") as pasta:
        origem = Path(pasta) / "documento.doc"
        origem.write_bytes(conteudo)
        perfil = Path(pasta) / "perfil"  # perfil isolado: funciona mesmo com o LibreOffice aberto
        subprocess.run(
            [soffice, f"-env:UserInstallation={perfil.as_uri()}", "--headless", "--norestore",
             "--convert-to", "docx", "--outdir", pasta, str(origem)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90, check=False,
        )
        destino = Path(pasta) / "documento.docx"
        return destino.read_bytes() if destino.exists() else None


def _extrair_doc_binario(conteudo: bytes, nome: str) -> tuple[str, list[dict]]:
    try:
        import olefile
    except ImportError as e:
        raise ex.ErroExtracao("Para ler arquivos .doc, instale: pip install olefile (ou instale o LibreOffice)") from e

    try:
        ole = olefile.OleFileIO(io.BytesIO(conteudo))
        if not ole.exists("WordDocument"):
            raise ex.ErroExtracao("Este arquivo não é um documento do Word.")
        wd = ole.openstream("WordDocument").read()
        if struct.unpack_from("<H", wd, 0)[0] != 0xA5EC:
            raise ex.ErroExtracao("Formato de documento do Word não reconhecido.")
        flags = struct.unpack_from("<H", wd, 0x0A)[0]
        if flags & 0x0100:
            raise ex.ErroExtracao("Este documento do Word é protegido por senha.")
        tabela = ole.openstream("1Table" if flags & 0x0200 else "0Table").read()
        titulo_meta = ""
        try:
            meta = ole.get_metadata()
            if meta.title:
                titulo_meta = meta.title.decode("cp1252", errors="ignore").strip()
        except Exception:  # noqa: BLE001
            pass
    except ex.ErroExtracao:
        raise
    except Exception as e:  # noqa: BLE001
        raise ex.ErroExtracao("Não foi possível abrir o documento .doc (corrompido ou protegido).") from e

    paragrafos = _paragrafos_binarios(wd, tabela)
    try:
        estilos = _folha_de_estilos(wd, tabela)
        propriedades = _propriedades_de_paragrafo(wd, tabela)
    except Exception:  # noqa: BLE001 — sem estilos, a estrutura é deduzida pelo texto
        estilos, propriedades = {}, []

    blocos: list[dict] = []
    if propriedades:
        limpar = _LimpadorDeCampos()
        celulas: list[str] = []      # células da linha de tabela em montagem
        celula: list[str] = []       # parágrafos da célula atual

        def fechar_linha():
            if celula:
                celulas.append(" ".join(celula))
                celula.clear()
            linha = [c for c in celulas if c]
            if linha:
                blocos.append(ex.bloco("item", ex._unir_celulas(linha)))
            celulas.clear()

        for texto_bruto, fc in paragrafos:
            fim_de_celula = texto_bruto.endswith("\x07")
            texto = ex.limpar_espacos(_limpar_marcas(limpar(texto_bruto.rstrip("\x07"))))
            istd, lista, nivel, em_tabela, fim_de_linha = _buscar_propriedades(propriedades, fc)
            if fim_de_linha:                       # marca de fim de linha da tabela
                fechar_linha()
                continue
            if em_tabela or fim_de_celula:
                if texto:
                    celula.append(texto)
                if fim_de_celula:
                    celulas.append(" ".join(celula))
                    celula.clear()
                continue
            if celulas or celula:                  # saiu da tabela sem marca de fim
                fechar_linha()
            if not texto:
                continue
            sti, nome_estilo = estilos.get(istd, (0, ""))
            blocos.append(_classificar(texto, nome_estilo, sti, lista, nivel))
        fechar_linha()
        titulo = ""
    else:
        limpar = _LimpadorDeCampos()
        textos = [ex.limpar_espacos(_limpar_marcas(limpar(t).replace("\x07", ""))) for t, _ in paragrafos]
        titulo, blocos = ex.extrair_txt("\n\n".join(t for t in textos if t), nome)

    blocos = [b for b in blocos if re.search(r"\w", b["texto"])]
    if sum(len(b["texto"]) for b in blocos) < 5:
        raise ex.ErroExtracao("O documento do Word não tem texto para ler.")
    if any(b["tipo"] == "titulo" for b in blocos):
        titulo = next(b["texto"] for b in blocos if b["tipo"] == "titulo")
    elif titulo_meta:
        blocos = ex._marcar_titulo(titulo_meta, blocos)
        titulo = titulo_meta
    elif not titulo:
        titulo = ex._titulo_de_nome(nome)
    return titulo, blocos


def _classificar(texto: str, nome_estilo: str, sti: int, lista: bool, nivel: int | None) -> dict:
    """Mesma lógica do DOCX, a partir do estilo do parágrafo no .doc."""
    if sti == 62 or re.fullmatch(r"title|t[íi]tulo", nome_estilo, re.I):
        return ex.bloco("titulo", texto, 1)
    if 1 <= sti <= 9:
        return ex.bloco("h", texto, min(sti + 1, 4))
    m = _ESTILO_TITULO.match(nome_estilo)
    if m:
        return ex.bloco("h", texto, min(int(m.group(2)) + 1, 4))
    if nivel is not None and len(texto) < 200:
        return ex.bloco("h", texto, min(nivel + 2, 4))
    if _ESTILO_CITACAO.search(nome_estilo):
        return ex.bloco("citacao", texto)
    if lista or _ESTILO_LISTA.search(nome_estilo) or ex.MARCADOR_LISTA.match(texto):
        return ex.bloco("item", ex.MARCADOR_SIMBOLO.sub("", texto))
    return ex.bloco("p", texto)


def _pecas(wd: bytes, tabela: bytes):
    """Peças de texto do corpo principal: (texto, fc inicial, bytes por caractere) — [MS-DOC] 2.4.1."""
    ccp_texto = struct.unpack_from("<I", wd, 0x4C)[0]           # FibRgLw97.ccpText
    fc_clx, lcb_clx = struct.unpack_from("<II", wd, 0x1A2)      # FibRgFcLcb97.fcClx / lcbClx
    clx = tabela[fc_clx:fc_clx + lcb_clx]
    i = 0
    while i < len(clx) and clx[i] == 0x01:                       # Prc (formatação): pular
        i += 3 + struct.unpack_from("<h", clx, i + 1)[0]
    if i >= len(clx) or clx[i] != 0x02:
        raise ex.ErroExtracao("Estrutura interna do .doc não reconhecida.")
    lcb = struct.unpack_from("<I", clx, i + 1)[0]
    plc = clx[i + 5:i + 5 + lcb]
    n = (lcb - 4) // 12
    cps = struct.unpack_from(f"<{n + 1}I", plc, 0)
    total = 0
    for k in range(n):
        if total >= ccp_texto:
            break
        qtd = min(cps[k + 1] - cps[k], ccp_texto - total)
        fc_bruto = struct.unpack_from("<I", plc, (n + 1) * 4 + k * 8 + 2)[0]
        if fc_bruto & 0x40000000:                                # 8 bits (cp1252)
            fc = (fc_bruto & 0x3FFFFFFF) // 2
            yield wd[fc:fc + qtd].decode("cp1252", errors="replace"), fc, 1
        else:                                                    # UTF-16
            fc = fc_bruto & 0x3FFFFFFF
            yield wd[fc:fc + 2 * qtd].decode("utf-16-le", errors="replace"), fc, 2
        total += qtd


def _paragrafos_binarios(wd: bytes, tabela: bytes) -> list[tuple[str, int]]:
    """Lista de (texto do parágrafo, posição da marca de parágrafo no arquivo)."""
    paragrafos, atual = [], []
    for texto, fc, largura in _pecas(wd, tabela):
        inicio = 0
        for j, c in enumerate(texto):
            if c in "\r\x07":  # fim de parágrafo, ou de célula/linha de tabela
                atual.append(texto[inicio:j])
                paragrafos.append(("".join(atual) + ("\x07" if c == "\x07" else ""), fc + j * largura))
                atual, inicio = [], j + 1
        atual.append(texto[inicio:])
    if "".join(atual).strip():
        paragrafos.append(("".join(atual), -1))
    return paragrafos


def _texto_pela_tabela_de_pecas(wd: bytes, tabela: bytes) -> str:
    return "".join(t for t, _fc, _l in _pecas(wd, tabela))


def _folha_de_estilos(wd: bytes, tabela: bytes) -> dict[int, tuple[int, str]]:
    """istd → (sti, nome do estilo) a partir da STSH — [MS-DOC] 2.9.271."""
    fc, lcb = struct.unpack_from("<II", wd, 0xA2)               # fcStshf / lcbStshf
    stsh = tabela[fc:fc + lcb]
    cb_stshi = struct.unpack_from("<H", stsh, 0)[0]
    cstd, cb_base = struct.unpack_from("<HH", stsh, 2)
    pos = 2 + cb_stshi
    estilos = {}
    for istd in range(cstd):
        if pos + 2 > len(stsh):
            break
        cb_std = struct.unpack_from("<H", stsh, pos)[0]
        std = stsh[pos + 2:pos + 2 + cb_std]
        pos += 2 + cb_std
        if cb_std == 0 or len(std) < cb_base + 2:
            continue
        sti = struct.unpack_from("<H", std, 0)[0] & 0x0FFF
        cch = struct.unpack_from("<H", std, cb_base)[0]
        nome = std[cb_base + 2:cb_base + 2 + 2 * cch].decode("utf-16-le", errors="ignore")
        estilos[istd] = (sti, nome.split(",")[0].strip())
    return estilos


def _propriedades_de_paragrafo(wd: bytes, tabela: bytes) -> list[tuple[int, int, int, bool, int | None]]:
    """(fc início, fc fim, istd, tem numeração de lista, nível de tópico) de cada
    parágrafo, lidos das FKPs de PAPX — [MS-DOC] 2.9.175 e 2.9.209."""
    fc, lcb = struct.unpack_from("<II", wd, 0x102)              # fcPlcfBtePapx / lcbPlcfBtePapx
    plc = tabela[fc:fc + lcb]
    n = (lcb - 4) // 8
    pns = struct.unpack_from(f"<{n}I", plc, (n + 1) * 4)
    saida = []
    for pn in pns:
        fkp = wd[(pn & 0x3FFFFF) * 512:(pn & 0x3FFFFF) * 512 + 512]
        if len(fkp) < 512:
            continue
        crun = fkp[511]
        rgfc = struct.unpack_from(f"<{crun + 1}I", fkp, 0)
        for i in range(crun):
            b_offset = fkp[(crun + 1) * 4 + i * 13] * 2
            istd, lista, nivel, em_tabela, fim_de_linha = 0, False, None, False, False
            if b_offset:
                cb = fkp[b_offset]
                if cb:
                    tamanho, ini = 2 * cb - 1, b_offset + 1
                else:
                    tamanho, ini = 2 * fkp[b_offset + 1], b_offset + 2
                grp = fkp[ini:ini + tamanho]
                if len(grp) >= 2:
                    istd = struct.unpack_from("<H", grp, 0)[0]
                    lista, nivel, em_tabela, fim_de_linha = _ler_sprms(grp[2:])
            saida.append((rgfc[i], rgfc[i + 1], istd, lista, nivel, em_tabela, fim_de_linha))
    saida.sort()
    return saida


def _ler_sprms(grp: bytes) -> tuple[bool, int | None, bool, bool]:
    """Procura numeração (sprmPIlfo), nível de tópico (sprmPOutLvl), parágrafo
    dentro de tabela (sprmPFInTable) e fim de linha de tabela (sprmPFTtp)."""
    lista, nivel, i = False, None, 0
    em_tabela = fim_de_linha = False
    while i + 2 <= len(grp):
        sprm = struct.unpack_from("<H", grp, i)[0]
        i += 2
        spra = sprm >> 13
        if spra in (0, 1):
            tam = 1
        elif spra in (2, 4, 5):
            tam = 2
        elif spra == 3:
            tam = 4
        elif spra == 7:
            tam = 3
        else:  # 6: tamanho variável
            if i >= len(grp):
                break
            tam = (struct.unpack_from("<H", grp, i)[0] + 1) if sprm == 0xC615 and grp[i] == 255 else grp[i] + 1
        valor = grp[i:i + tam]
        if sprm == 0x460B and len(valor) >= 2 and struct.unpack_from("<h", valor, 0)[0] != 0:
            lista = True
        elif sprm == 0x2640 and valor and valor[0] < 9:
            nivel = valor[0]
        elif sprm == 0x2416 and valor:
            em_tabela = bool(valor[0])
        elif sprm == 0x2417 and valor:
            fim_de_linha = bool(valor[0])
        i += tam
    return lista, nivel, em_tabela, fim_de_linha


def _buscar_propriedades(props: list, fc: int) -> tuple[int, bool, int | None, bool, bool]:
    import bisect

    if fc < 0 or not props:
        return 0, False, None, False, False
    k = bisect.bisect_right(props, (fc, float("inf"))) - 1
    if k >= 0 and props[k][0] <= fc < props[k][1]:
        return props[k][2:7]
    return 0, False, None, False, False


class _LimpadorDeCampos:
    """Remove instruções de campo (HYPERLINK, PAGE, TOC…), mantendo o resultado
    visível. Guarda o estado entre parágrafos: um campo pode atravessar vários."""

    def __init__(self):
        self.pilha: list[bool] = []  # True enquanto estamos na instrução do campo

    def __call__(self, texto: str) -> str:
        saida = []
        for c in texto:
            if c == "\x13":
                self.pilha.append(True)
            elif c == "\x14":
                if self.pilha:
                    self.pilha[-1] = False
            elif c == "\x15":
                if self.pilha:
                    self.pilha.pop()
            elif self.pilha and self.pilha[-1]:
                continue
            else:
                saida.append(c)
        return "".join(saida)


def _limpar_marcas(t: str) -> str:
    t = t.replace("\x0b", " ").replace("\x0c", " ")             # quebra de linha / de página
    t = t.replace("\x1e", "-").replace("\x1f", "")              # hífen inseparável / opcional
    return re.sub(r"[\x00-\x06\x08\x0e-\x12\x16-\x1d]", "", t)  # objetos, figuras, notas


def _limpar_codigos_do_word(texto: str) -> str:
    return _limpar_marcas(_LimpadorDeCampos()(texto)).replace("\x07\x07", "\r").replace("\x07", "; ")
