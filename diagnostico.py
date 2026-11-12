"""Diagnóstico do Leitor: testa cada etapa da síntese de voz e mostra o erro exato.

Uso (dentro da pasta leitor, com o .venv ativo):  python diagnostico.py
"""

import platform
import sys
import traceback
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))


def etapa(nome, func):
    print(f"\n=== {nome}")
    try:
        r = func()
        print("OK" + (f": {r}" if r is not None else ""))
        return r
    except BaseException:  # noqa: BLE001
        traceback.print_exc(file=sys.stdout)
        return None


print("Python:", sys.version)
print("Sistema:", platform.platform(), platform.machine())
print("Pasta do app:", AQUI, "| só ASCII:", str(AQUI).isascii())
print("Versão do app (voz.py com correção de acentos):", "_pasta_espeak" in (AQUI / "voz.py").read_text(encoding="utf-8"))

etapa("Importar onnxruntime", lambda: __import__("onnxruntime").__version__)
etapa("Importar piper", lambda: __import__("piper").__file__)


def info_espeak():
    from piper.phonemize_espeak import ESPEAK_DATA_DIR
    return f"{ESPEAK_DATA_DIR} | existe: {ESPEAK_DATA_DIR.exists()} | só ASCII: {str(ESPEAK_DATA_DIR).isascii()}"


etapa("Dados do espeak-ng (original)", info_espeak)

voz = etapa("Importar voz.py do app", lambda: __import__("voz"))
pasta = etapa("Pasta do espeak-ng usada pelo app", lambda: voz._pasta_espeak()) if voz else None


def listar_vozes():
    linhas = []
    for f in sorted((AQUI / "voices").glob("*.onnx*")):
        linhas.append(f"{f.name} ({f.stat().st_size / 1e6:.1f} MB)")
    return "\n  " + "\n  ".join(linhas) if linhas else "NENHUM arquivo de voz"


etapa("Arquivos de voz", listar_vozes)


def fonemas():
    from piper.phonemize_espeak import EspeakPhonemizer
    return EspeakPhonemizer(pasta).phonemize("pt-br", "Olá, mundo.")


if pasta:
    etapa("Converter texto em fonemas (espeak-ng)", fonemas)

if voz:
    for vid, info in voz.VOZES.items():
        if (AQUI / "voices" / info["arquivo"]).exists():
            etapa(f"Carregar voz '{vid}'", lambda vid=vid: type(voz._voz(vid)).__name__)
            etapa(f"Sintetizar com '{vid}'", lambda vid=vid: f"{len(voz.sintetizar('Olá, este é um teste.', vid))} bytes de áudio")
            break

print("\nFim do diagnóstico. Copie TODO o texto acima e envie.")
