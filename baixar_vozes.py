"""Baixa as vozes pt-BR do Piper (datasets CC0) para a pasta voices/.

Uso:  python baixar_vozes.py            (baixa tudo: Kokoro + faber, cadu e jeff)
      python baixar_vozes.py kokoro     (só o Kokoro: ~350 MB, vozes Dora, Alex e Santa)
      python baixar_vozes.py faber      (só uma voz Piper)
"""

import io
import sys
import tarfile
import urllib.request
from pathlib import Path

PASTA = Path(__file__).parent / "voices"
VOZES = ["kokoro", "faber", "cadu", "jeff"]
KOKORO = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/{arquivo}"
KOKORO_ARQUIVOS = ["kokoro-v1.0.onnx", "voices-v1.0.bin"]
HF = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/{v}/medium/pt_BR-{v}-medium.onnx{ext}"
GITHUB = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-{v}-medium.tar.bz2"


def baixar(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "LeitorPT/1.0"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()


def instalar_kokoro() -> None:
    pasta = PASTA / "kokoro"
    pasta.mkdir(exist_ok=True)
    for arquivo in KOKORO_ARQUIVOS:
        destino = pasta / arquivo
        if destino.exists():
            print(f"  kokoro/{arquivo}: já instalado")
            continue
        print(f"  kokoro/{arquivo}: baixando…")
        parcial = destino.with_suffix(destino.suffix + ".parcial")
        parcial.write_bytes(baixar(KOKORO.format(arquivo=arquivo)))
        parcial.rename(destino)
        print(f"  kokoro/{arquivo}: ok")


def instalar(v: str) -> None:
    if v == "kokoro":
        instalar_kokoro()
        return
    destino = PASTA / f"pt_BR-{v}-medium.onnx"
    if destino.exists() and destino.with_suffix(".onnx.json").exists():
        print(f"  {v}: já instalada")
        return
    try:
        print(f"  {v}: baixando do Hugging Face…")
        modelo = baixar(HF.format(v=v, ext=""))
        config = baixar(HF.format(v=v, ext=".json"))
        destino.write_bytes(modelo)
        Path(str(destino) + ".json").write_bytes(config)
    except Exception as e:  # noqa: BLE001
        print(f"  {v}: Hugging Face indisponível ({e}); tentando GitHub…")
        pacote = baixar(GITHUB.format(v=v))
        with tarfile.open(fileobj=io.BytesIO(pacote), mode="r:bz2") as tar:
            for nome in (f"pt_BR-{v}-medium.onnx", f"pt_BR-{v}-medium.onnx.json"):
                membro = tar.getmember(f"vits-piper-pt_BR-{v}-medium/{nome}")
                (PASTA / nome).write_bytes(tar.extractfile(membro).read())
    print(f"  {v}: ok")


if __name__ == "__main__":
    PASTA.mkdir(exist_ok=True)
    for voz in sys.argv[1:] or VOZES:
        instalar(voz)
