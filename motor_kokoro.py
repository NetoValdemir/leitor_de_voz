"""Motor de voz Kokoro-82M (Apache-2.0) para português do Brasil.

Vozes pt-BR: Dora (feminina), Alex e Santa (masculinas).
Roda no processador (CPU), sem precisar de placa de vídeo.

A conversão texto → fonemas reproduz a usada no treino do Kokoro
(misaki.espeak.EspeakG2P, Apache-2.0, https://github.com/hexgrad/misaki):
espeak-ng via `phonemizer`, com `tie="^"` e o mesmo mapeamento de ditongos/africadas.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import tempfile
import threading
from pathlib import Path

import numpy as np

_LOG = logging.getLogger("leitor.kokoro")

PASTA = Path(__file__).parent / "voices" / "kokoro"
MODELOS = ["kokoro-v1.0.onnx", "kokoro-v1.0.int8.onnx"]  # o primeiro encontrado é usado
ARQUIVO_VOZES = "voices-v1.0.bin"
TAXA = 24000

VOZES = {
    "kokoro-dora": {"nome": "Dora (Kokoro)", "estilo": "pf_dora"},
    "kokoro-alex": {"nome": "Alex (Kokoro)", "estilo": "pm_alex"},
    "kokoro-santa": {"nome": "Santa (Kokoro)", "estilo": "pm_santa"},
}

# Mapeamento usado no treino (misaki.espeak.EspeakG2P.E2M)
_E2M = sorted({
    "a^ɪ": "I", "a^ʊ": "W",
    "d^z": "ʣ", "d^ʒ": "ʤ",
    "e^ɪ": "A",
    "o^ʊ": "O", "ə^ʊ": "Q",
    "s^s": "S",
    "t^s": "ʦ", "t^ʃ": "ʧ",
    "ɔ^ɪ": "Y",
}.items())

_trava = threading.Lock()          # espeak-ng tem estado global: uma conversão por vez
# Uma inferência por vez: se o navegador pede 4 frases juntas, elas não disputam
# o processador e a frase que vai tocar primeiro fica pronta antes.
_trava_inferencia = threading.Lock()
_estado: dict = {}


def disponivel() -> bool:
    return (PASTA / ARQUIVO_VOZES).exists() and any((PASTA / m).exists() for m in MODELOS)


def vozes_disponiveis() -> list[dict]:
    if not disponivel():
        return []
    try:
        import espeakng_loader  # noqa: F401
        import kokoro_onnx  # noqa: F401
        import phonemizer  # noqa: F401
    except ImportError:
        _LOG.warning("Kokoro instalado, mas faltam pacotes: pip install -r requirements.txt")
        return []
    return [{"id": vid, "nome": info["nome"]} for vid, info in VOZES.items()]


def _copiar_sem_acentos(origem: Path) -> Path:
    """No Windows, o espeak-ng não abre dados em caminhos com acentos ("PROGRAMAÇÃO")."""
    if str(origem).isascii():
        return origem
    for base in (os.environ.get("LOCALAPPDATA"), os.environ.get("PROGRAMDATA"), tempfile.gettempdir(), str(Path.home())):
        if not base or not base.isascii():
            continue
        destino = Path(base) / "leitor-voz" / "espeak-ng-data-kokoro"
        try:
            if not (destino / ".copiado").exists():
                shutil.copytree(origem, destino, dirs_exist_ok=True)
                (destino / ".copiado").write_text("ok")
            return destino
        except OSError as e:
            _LOG.warning("Não foi possível usar %s: %s", destino, e)
    return origem


def _carregar() -> dict:
    global _estado
    with _trava:
        if _estado:
            return _estado
        import espeakng_loader
        import onnxruntime as ort
        from phonemizer.backend import EspeakBackend
        from phonemizer.backend.espeak.wrapper import EspeakWrapper

        EspeakWrapper.set_library(espeakng_loader.get_library_path())
        EspeakWrapper.set_data_path(str(_copiar_sem_acentos(Path(espeakng_loader.get_data_path()))))
        backend = EspeakBackend(
            language="pt-br", preserve_punctuation=True, with_stress=True,
            tie="^", language_switch="remove-flags",
        )

        modelo = next(PASTA / m for m in MODELOS if (PASTA / m).exists())
        opcoes = ort.SessionOptions()
        opcoes.intra_op_num_threads = max(1, (os.cpu_count() or 2) - 1)
        sessao = ort.InferenceSession(str(modelo), opcoes, providers=["CPUExecutionProvider"])

        meta = sessao.get_modelmeta().custom_metadata_map or {}
        if "vocab" in meta:
            vocab = json.loads(meta["vocab"])
        else:
            from kokoro_onnx.config import DEFAULT_VOCAB  # vocabulário oficial do Kokoro v1.0
            vocab = DEFAULT_VOCAB

        _estado.update(
            backend=backend, sessao=sessao, vocab=vocab,
            estilos=np.load(str(PASTA / ARQUIVO_VOZES)),
            entrada_tokens="input_ids" if "input_ids" in {i.name for i in sessao.get_inputs()} else "tokens",
        )
        _LOG.info("Kokoro carregado (%s)", modelo.name)
        return _estado


def descarregar() -> bool:
    """Libera o modelo da memória (volta a carregar sozinho quando for usado)."""
    global _estado
    with _trava, _trava_inferencia:
        if not _estado:
            return False
        # Um dicionário novo (e não .clear()): uma frase que ainda esteja usando
        # o modelo antigo termina normalmente; depois ele é liberado.
        _estado = {}
        return True


def fonemas(texto: str) -> str:
    """Texto → fonemas no formato do treino do Kokoro (misaki EspeakG2P)."""
    estado = _carregar()
    texto = texto.replace("«", "“").replace("»", "”").replace("(", "«").replace(")", "»")
    with _trava:
        ps = estado["backend"].phonemize([texto])
    if not ps:
        return ""
    ps = ps[0].strip()
    for antigo, novo in _E2M:
        ps = ps.replace(antigo, novo)
    ps = ps.replace("^", "").replace("-", "")
    return ps.replace("«", "(").replace("»", ")")


def _inferir(tokens: list[int], estilo: str, velocidade: float) -> np.ndarray:
    estado = _carregar()
    ref = estado["estilos"][estilo]
    s = ref[min(len(tokens), len(ref) - 1)]
    with _trava_inferencia:
        saida = estado["sessao"].run(None, {
            estado["entrada_tokens"]: np.array([[0, *tokens, 0]], dtype=np.int64),
            "style": np.asarray(s, dtype=np.float32),
            "speed": np.array([velocidade], dtype=np.float32),
        })
    return np.asarray(saida[0], dtype=np.float32).ravel()


def _dividir(ps: str, limite: int = 500) -> list[str]:
    """O modelo aceita até ~510 fonemas; divide em pontuação se passar disso."""
    if len(ps) <= limite:
        return [ps]
    partes, atual = [], ""
    for pedaco in re.split(r"(?<=[,;:.!?])\s+", ps):
        if atual and len(atual) + len(pedaco) + 1 > limite:
            partes.append(atual)
            atual = pedaco
        else:
            atual = f"{atual} {pedaco}".strip()
    if atual:
        partes.append(atual)
    return [p[:limite] for p in partes]


def falar(texto: str, vid: str, velocidade: float = 1.0) -> tuple[np.ndarray, int]:
    """Devolve (áudio int16, taxa de amostragem). A frase é falada de uma vez só:
    o Kokoro já faz pausas naturais na pontuação."""
    estilo = VOZES.get(vid, VOZES["kokoro-dora"])["estilo"]
    estado = _carregar()
    ps = fonemas(texto)
    blocos = []
    for parte in _dividir(ps):
        tokens = [estado["vocab"][c] for c in parte if c in estado["vocab"]]
        if tokens:
            blocos.append(_inferir(tokens, estilo, max(0.5, min(2.0, velocidade))))
    if not blocos:
        return np.zeros(0, dtype=np.int16), TAXA
    audio = np.concatenate(blocos)
    pico = float(np.max(np.abs(audio))) if audio.size else 0.0
    if pico > 1.0:
        audio = audio / pico
    return (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16), TAXA
