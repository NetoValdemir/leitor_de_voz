"""Gera a chave de assinatura do APK — rode UMA VEZ no seu computador.

A chave fica só com você e nos segredos do repositório no GitHub (criptografados);
ela nunca vai para o código. Com ela, cada APK novo instala por cima do anterior,
mantendo a voz baixada e o dicionário de pronúncia.

Uso:
    pip install cryptography
    python gerar_chave.py

Depois, no GitHub: repositório → Settings → Secrets and variables → Actions →
"New repository secret", e crie os dois segredos que o script mostrar.
Guarde também o arquivo leitor-assinatura.p12 num lugar seguro (é a sua chave).
"""

import base64
import datetime
import secrets

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

senha = secrets.token_hex(16)
chave = rsa.generate_private_key(public_exponent=65537, key_size=2048)
nome = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Leitor de Voz"), x509.NameAttribute(NameOID.COUNTRY_NAME, "BR")])
agora = datetime.datetime.now(datetime.timezone.utc)
certificado = (
    x509.CertificateBuilder()
    .subject_name(nome).issuer_name(nome)
    .public_key(chave.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(agora - datetime.timedelta(days=1))
    .not_valid_after(agora + datetime.timedelta(days=365 * 50))
    .sign(chave, hashes.SHA256())
)
p12 = pkcs12.serialize_key_and_certificates(
    b"leitor", chave, certificado, None,
    serialization.BestAvailableEncryption(senha.encode()),
)
with open("leitor-assinatura.p12", "wb") as f:
    f.write(p12)

print("Chave criada: leitor-assinatura.p12 (guarde este arquivo em lugar seguro)\n")
print("Crie estes dois segredos no GitHub (Settings → Secrets and variables → Actions):\n")
print("Nome:  ANDROID_KEYSTORE_SENHA")
print(f"Valor: {senha}\n")
print("Nome:  ANDROID_KEYSTORE_BASE64")
print("Valor: (o texto abaixo, inteiro, numa linha só)")
print(base64.b64encode(p12).decode())
