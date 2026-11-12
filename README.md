# Leitor em Voz Alta

Ouça qualquer **PDF**, **documento do Word (DOC/DOCX)** ou **página web** em **português do Brasil**: cole a URL ou anexe o arquivo e aperte play.

- Dois motores de voz livres, ambos rodando no processador (não precisam de placa de vídeo):
  - **Kokoro-82M** (Apache-2.0): vozes *Dora* (feminina), *Alex* e *Santa*. Mais naturais; geram o áudio cerca de 2× mais rápido que o tempo real numa CPU de 4 núcleos. É o padrão quando instalado.
  - **Piper**: vozes *Faber*, *Cadu* e *Jeff*, treinadas em datasets de **licença CC0** (domínio público). Muito rápidas (~15× o tempo real) e leves.
- Toda a síntese roda **no seu computador**: não depende de serviços pagos nem de APIs externas.
- **Leitura fluida:** o áudio de cada frase é gerado bem mais rápido que o tempo real, as próximas frases são pré-carregadas e o Web Audio encadeia uma na outra sem lacunas.
- A frase atual fica destacada e acompanha a rolagem. Clique em qualquer frase para pular até ela.
- Velocidade ajustável de 0,6× a 2× (o ajuste é feito na síntese, então a voz não fica com timbre de "esquilo").
- O app lembra onde você parou em cada documento.
- Atalhos: `espaço` reproduz ou pausa; `←` e `→` vão para a frase anterior ou a próxima. Também funciona com os controles de mídia do sistema e do celular.
- **Extração que entende a estrutura do documento**, para pausas e entonação naturais:
  - **PDF** (via PyMuPDF): analisa tamanho de fonte, negrito e posição de cada linha para reconhecer **título, subtítulos (em níveis), parágrafos, listas, sumário e notas**; refaz parágrafos que atravessam colunas e páginas; segue a ordem correta em **duas colunas**; desfaz hifenização; remove cabeçalhos, rodapés, números de página e índices de nota (¹); transforma fichas/tabelas em "Rótulo: valor"; corrige texto com letras espaçadas ("R E L A T Ó R I O").
  - **Páginas web:** usa o `trafilatura` para pegar só o conteúdo principal (sem menus/anúncios), preservando títulos, listas, citações e tabelas.
  - **Word DOCX:** usa os estilos do documento (Título, Título 1/2/3…, listas, citações), o nível de tópico e a numeração; parágrafos curtos em negrito ou com fonte maior viram títulos; tabelas viram "Rótulo: valor"; cabeçalhos, rodapés e notas não são lidos.
  - **Word DOC (97-2003):** se o **LibreOffice** estiver instalado, o arquivo é convertido para DOCX e lido com a mesma qualidade. Sem ele, o texto é lido direto do formato binário (especificação [MS-DOC]), aproveitando os estilos de título e lista, a numeração e as tabelas; só títulos feitos apenas com negrito (sem estilo) não são reconhecidos.
  - **TXT:** separa títulos, parágrafos e listas.
- **Pausas na pontuação sem quebrar a entonação:** cada frase é falada de uma vez só (entonação contínua e volume uniforme). Depois, usando o alinhamento fonema → áudio do Piper (pacote `onnx`), o app insere silêncios nos pontos exatos de vírgulas, ponto e vírgula, dois-pontos, travessões, parênteses e reticências. O seletor **Pausas** (curtas, normais, longas) ajusta todas, e elas acompanham a velocidade.
- **Leitura natural:** pausas diferentes para fim de frase (ponto, interrogação, reticências), título, seção, parágrafo e item de lista; títulos e itens sem pontuação ganham entonação de fim de frase; trechos em CAIXA ALTA são lidos normalmente; numeração de seção ("2.1") ganha uma pausa.

- **Ajuste de pronúncia** (botão *Pronúncia* no topo):
  - *Sons em todo o texto:* alternativas para o "lh" (trabalho, milhões) e o "nh", aplicadas direto nos fonemas. Os modelos, ajustados a partir de uma voz inglesa, às vezes encurtam esses sons. O padrão do "lh" é **Reforçado**.
  - *Palavras específicas:* um dicionário em que você escreve a palavra como ela deve soar (ex.: molho → môlho). Dá para ouvir antes de salvar. Já vem com correções para "molho" e "homem", que o espeak-ng pronuncia errado.
  - **Tudo é salvo na hora** (ao adicionar, remover ou escolher uma opção) e vale para todos os documentos, inclusive depois de reiniciar o app.
  - O arquivo `pronuncias.json` fica na pasta de dados do usuário, **fora da pasta do app** (não é apagado em atualizações nem fica no OneDrive): Windows `%APPDATA%\LeitorVoz`, macOS `~/Library/Application Support/LeitorVoz`, Linux `~/.config/leitor-voz`. A variável `LEITOR_DADOS` permite escolher outra pasta. A gravação é atômica, com cópia de segurança (`pronuncias.bak.json`), e o navegador guarda uma cópia que restaura o dicionário se o arquivo sumir.
  - Selecionar uma palavra no texto antes de abrir o painel já a preenche.

- **Memória sob controle:** só a voz em uso fica carregada (trocar de voz descarrega a anterior); o cache de áudio é limitado a 40 MB e é esvaziado ao abrir outro documento; no navegador, "Abrir outro texto" libera o texto, os áudios e o contexto de áudio.

## Como rodar

Requer **Python 64 bits, versões 3.11 a 3.14** (o `onnxruntime`, usado pelo Piper, ainda não tem pacote para versões mais novas nem para Python 32 bits). Recomendado: Python 3.13.

```bash
git clone https://github.com/NetoValdemir/leitor_de_voz.git
cd leitor_de_voz
python -m venv .venv && source .venv/bin/activate   # no Windows: .venv\Scripts\activate
pip install -r requirements.txt
python baixar_vozes.py      # só na primeira vez: Kokoro (~350 MB) + Piper (~190 MB); "python baixar_vozes.py kokoro" baixa só o Kokoro
python app.py
```

Abra **http://127.0.0.1:8000**.

Para acessar de outros aparelhos da rede (o celular, por exemplo), use `HOST=0.0.0.0 python app.py`. Para mudar a porta, use `PORT=9000`.

Também dá para abrir uma URL diretamente: `http://127.0.0.1:8000/?url=https://site.com/artigo`.

## Estrutura

| Arquivo | Função |
|---|---|
| `app.py` | Servidor FastAPI: `/api/extrair` (URL ou arquivo → frases) e `/api/falar` (frase → WAV) |
| `extracao.py` | Download, extração de PDF/HTML/TXT, limpeza e divisão em frases (com tratamento de abreviações como "Sr.", "Dr.", "art.") |
| `voz.py` | Síntese com o Piper, normalização do texto e cache de áudio |
| `static/` | Interface web (HTML, CSS e JS puros, sem dependências) |
| `baixar_vozes.py` | Baixa as vozes do Hugging Face, com o GitHub como alternativa |

## Limitações

- PDFs digitalizados (só imagem) não têm texto selecionável e precisariam de OCR.
- Páginas que só montam o conteúdo via JavaScript ou que exigem login podem não ser extraídas.

## Licenças

- **Vozes:** os datasets *faber*, *cadu* e *jeff* ([OHF-Voice/voice-datasets](https://github.com/OHF-Voice/voice-datasets)) são **CC0**. Os modelos foram ajustados (*fine-tuning*) a partir da voz inglesa *lessac* do Piper. Veja `voices/MODEL_CARD.txt`.
- **Kokoro-82M** (modelo e vozes): Apache-2.0. **kokoro-onnx**: MIT. Conversão em fonemas baseada na **misaki** (Apache-2.0), com espeak-ng via **phonemizer** (GPL-3.0).
- **Piper TTS** (`piper-tts`): GPL-3.0.
- **PyMuPDF** (AGPL-3.0), **pypdf** (BSD, usado como alternativa), **python-docx** (MIT), **olefile** (BSD), **trafilatura** (Apache-2.0), **FastAPI** (MIT).
