"""Leitor em voz alta de PDFs e páginas web — servidor FastAPI."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import logging
import threading
from contextlib import asynccontextmanager

import extracao
import voz

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
_LOG = logging.getLogger("leitor")

@asynccontextmanager
async def ciclo_de_vida(_app):
    voz.carregar_pronuncia()  # lê (e migra, se preciso) o dicionário do usuário já ao iniciar
    # Carrega a voz padrão em segundo plano: a primeira frase sai sem espera.
    threading.Thread(target=lambda: voz.sintetizar("Pronto.", voz.voz_padrao(), pronuncia={}), daemon=True).start()
    yield


app = FastAPI(title="Leitor em Voz Alta", lifespan=ciclo_de_vida)
ESTATICOS = Path(__file__).parent / "static"


class PedidoFala(BaseModel):
    texto: str = Field(..., max_length=2000)
    voz: str = voz.VOZ_PADRAO
    velocidade: float = 1.0
    tipo: str = "p"
    pausas: float = 1.0


@app.get("/api/vozes")
def listar_vozes():
    return {"vozes": voz.vozes_disponiveis(), "padrao": voz.voz_padrao()}


@app.post("/api/extrair")
def extrair(url: str | None = Form(None), arquivo: UploadFile | None = File(None)):
    try:
        if arquivo is not None and arquivo.filename:
            conteudo = arquivo.file.read(extracao.TAMANHO_MAXIMO + 1)
            if len(conteudo) > extracao.TAMANHO_MAXIMO:
                raise extracao.ErroExtracao("O arquivo é grande demais (limite de 60 MB).")
            titulo, blocos = extracao.extrair(conteudo, arquivo.content_type or "", arquivo.filename)
            fonte = arquivo.filename
        elif url and url.strip():
            conteudo, tipo, final = extracao.baixar_url(url)
            titulo, blocos = extracao.extrair(conteudo, tipo, final)
            fonte = final
        else:
            raise extracao.ErroExtracao("Informe uma URL ou anexe um arquivo.")
    except extracao.ErroExtracao as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    dados = extracao.estruturar(titulo, blocos)
    voz.limpar_cache()  # documento novo: o áudio do anterior não será mais usado
    if not dados["total_frases"]:
        raise HTTPException(status_code=422, detail="Nenhum texto encontrado para ler.")
    dados["fonte"] = fonte
    return dados


@app.post("/api/falar")
def falar(pedido: PedidoFala):
    try:
        audio = voz.sintetizar(pedido.texto, pedido.voz, pedido.velocidade, pedido.tipo, pedido.pausas)
    except Exception as e:  # noqa: BLE001
        _LOG.exception("Falha ao gerar áudio para: %r", pedido.texto[:80])
        raise HTTPException(status_code=500, detail=f"{e.__class__.__name__}: {e}") from e
    return Response(content=audio, media_type="audio/wav", headers={"Cache-Control": "private, max-age=3600"})


class PedidoTestePronuncia(BaseModel):
    texto: str = Field(..., max_length=500)
    voz: str = voz.VOZ_PADRAO
    velocidade: float = 1.0
    lh: str = "padrao"
    nh: str = "padrao"
    palavras: dict[str, str] = {}


class Pronuncia(BaseModel):
    lh: str = "padrao"
    nh: str = "padrao"
    palavras: dict[str, str] = {}


@app.get("/api/pronuncia")
def obter_pronuncia():
    regras = {
        som: {
            "opcoes": [{"id": k, "rotulo": v["rotulo"]} for k, v in r["opcoes"].items()],
            "exemplo": r["exemplo"],
        }
        for som, r in voz.REGRAS_FONEMAS.items()
    }
    config = voz.carregar_pronuncia()
    return {
        "config": config, "regras": regras, "versao": voz.versao_pronuncia,
        "arquivo": str(voz.ARQUIVO_PRONUNCIA), "existia": voz.arquivo_existia,
    }


@app.put("/api/pronuncia")
def salvar_pronuncia(config: Pronuncia):
    try:
        salvo = voz.salvar_pronuncia(config.model_dump())
    except OSError as e:
        raise HTTPException(status_code=500, detail=f"Não foi possível salvar: {e}") from e
    return {"config": salvo, "versao": voz.versao_pronuncia, "arquivo": str(voz.ARQUIVO_PRONUNCIA)}


@app.post("/api/pronuncia/testar")
def testar_pronuncia(pedido: PedidoTestePronuncia):
    try:
        audio = voz.sintetizar(
            pedido.texto, pedido.voz, pedido.velocidade,
            pronuncia={"lh": pedido.lh, "nh": pedido.nh, "palavras": pedido.palavras},
        )
    except Exception as e:  # noqa: BLE001
        _LOG.exception("Falha no teste de pronúncia")
        raise HTTPException(status_code=500, detail=f"{e.__class__.__name__}: {e}") from e
    return Response(content=audio, media_type="audio/wav", headers={"Cache-Control": "no-store"})


@app.get("/")
def inicio():
    return FileResponse(ESTATICOS / "index.html")


app.mount("/", StaticFiles(directory=ESTATICOS), name="estaticos")


if __name__ == "__main__":
    import os

    import uvicorn

    uvicorn.run(app, host=os.environ.get("HOST", "127.0.0.1"), port=int(os.environ.get("PORT", "8000")))
