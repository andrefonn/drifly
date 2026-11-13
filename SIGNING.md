# Assinatura do app (release)

A keystore e as senhas NAO ficam no Git (repositorio publico). Ficam no Google Drive privado
(conta a.l.s.fontes123@gmail.com), pasta **DriFly-Signing (PRIVADO)** - ID `1EUdPMFtu20Iaa2iY5MRi4tWS6cHczUE2`:

- `drifly-release.jks` -> copiar para `app/drifly-release.jks` (ignorado pelo .gitignore)
- `signing.env` -> variaveis `DRIFLY_KEYSTORE_PASSWORD`, `DRIFLY_KEY_ALIAS`, `DRIFLY_KEY_PASSWORD`

## Agente sem a keystore (ex.: Antigravity Nuvem)
```
gdrive readonly download 18KTEC0Loe_Gw8qSfiC6be8id3TgMpvqv --out app/drifly-release.jks
gdrive readonly download 1RcSjtJaByanj2jeIfq7TyyW4E_G6UvBT --out signing.env
# exportar as variaveis de signing.env e rodar: ./gradlew assembleRelease
```
Nunca commitar `signing.env` nem o `.jks`. Nunca compartilhar a pasta por link publico.
