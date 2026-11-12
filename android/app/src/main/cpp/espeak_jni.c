/*
 * Ponte JNI mínima para o espeak-ng: só a conversão texto → fonemas (IPA).
 *
 * Reproduz exatamente o que a biblioteca Python `phonemizer` faz
 * (EspeakWrapper.text_to_phonemes com tie=True), que é a conversão usada no
 * treino do Kokoro (misaki.espeak.EspeakG2P):
 *   espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, dados, 0)
 *   espeak_SetVoiceByName("roa/pt-BR")
 *   repetir espeak_TextToPhonemes(&p, UTF8, IPA | TIE | '͡'<<8) até o fim,
 *   juntando os pedaços com um espaço.
 *
 * As três funções são declaradas aqui (mesmas assinaturas de speak_lib.h,
 * espeak-ng 1.52) para não depender do cabeçalho.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern int espeak_Initialize(int output, int buflength, const char *path, int options);
extern int espeak_SetVoiceByName(const char *name);
extern const char *espeak_TextToPhonemes(const void **textptr, int textmode, int phonememode);

#define AUDIO_OUTPUT_SYNCHRONOUS 2
#define ESPEAK_CHARS_UTF8 1
#define ESPEAK_PHONEMES_IPA 0x02
#define ESPEAK_PHONEMES_TIE 0x80
#define TIE_CHAR 0x0361 /* COMBINING DOUBLE INVERTED BREVE "͡" */

static jbyteArray bytes_java(JNIEnv *env, const char *s, size_t n) {
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) n);
    if (arr != NULL && n > 0) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) n, (const jbyte *) s);
    return arr;
}

static char *texto_c(JNIEnv *env, jbyteArray texto) {
    jsize n = (*env)->GetArrayLength(env, texto);
    char *buf = (char *) malloc((size_t) n + 1);
    if (buf == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, texto, 0, n, (jbyte *) buf);
    buf[n] = '\0';
    return buf;
}

/* Retorna a taxa de amostragem (>0) em caso de sucesso, ou um código negativo. */
JNIEXPORT jint JNICALL
Java_br_leitordevoz_voz_EspeakJni_iniciar(JNIEnv *env, jclass cls, jbyteArray pasta, jbyteArray voz) {
    (void) cls;
    char *caminho = texto_c(env, pasta);
    char *nome_voz = texto_c(env, voz);
    if (caminho == NULL || nome_voz == NULL) { free(caminho); free(nome_voz); return -3; }
    int taxa = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, caminho, 0);
    int r = taxa <= 0 ? -1 : (espeak_SetVoiceByName(nome_voz) != 0 ? -2 : taxa);
    free(caminho);
    free(nome_voz);
    return r;
}

JNIEXPORT jbyteArray JNICALL
Java_br_leitordevoz_voz_EspeakJni_fonemas(JNIEnv *env, jclass cls, jbyteArray texto) {
    (void) cls;
    char *entrada = texto_c(env, texto);
    if (entrada == NULL) return NULL;

    size_t cap = strlen(entrada) * 4 + 64, len = 0;
    char *saida = (char *) malloc(cap);
    if (saida == NULL) { free(entrada); return NULL; }

    const void *p = entrada;
    const int modo = ESPEAK_PHONEMES_IPA | ESPEAK_PHONEMES_TIE | (TIE_CHAR << 8);
    while (p != NULL) {
        const char *ph = espeak_TextToPhonemes(&p, ESPEAK_CHARS_UTF8, modo);
        if (ph == NULL || *ph == '\0') continue;   /* como o `if phonemes:` do phonemizer */
        size_t n = strlen(ph);
        if (len + n + 2 > cap) {
            cap = (len + n + 2) * 2;
            char *novo = (char *) realloc(saida, cap);
            if (novo == NULL) { free(saida); free(entrada); return NULL; }
            saida = novo;
        }
        if (len > 0) saida[len++] = ' ';
        memcpy(saida + len, ph, n);
        len += n;
    }
    jbyteArray r = bytes_java(env, saida, len);
    free(saida);
    free(entrada);
    return r;
}
