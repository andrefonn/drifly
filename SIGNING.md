# Assinatura do app (release)

A keystore e as senhas NAO ficam no Git (repositorio publico). Ficam no Google Drive privado
(conta a.l.s.fontes123@gmail.com), pasta **DriFly-Signing (PRIVADO)** - ID `1EUdPMFtu20Iaa2iY5MRi4tWS6cHczUE2`:

- `drifly-release.jks` (ID `18KTEC0Loe_Gw8qSfiC6be8id3TgMpvqv`) -> `app/drifly-release.jks`
- `signing.env` (ID `1RcSjtJaByanj2jeIfq7TyyW4E_G6UvBT`) -> `signing.env` na raiz do repo

Ambos estao no `.gitignore`. O `app/build.gradle.kts` le `DRIFLY_KEYSTORE_PASSWORD`,
`DRIFLY_KEY_ALIAS` e `DRIFLY_KEY_PASSWORD` das variaveis de ambiente ou do `signing.env`
e FALHA se faltarem (sem senha padrao no codigo).

## Agente sem a keystore (ex.: Antigravity Nuvem)
```
gdrive readonly download 18KTEC0Loe_Gw8qSfiC6be8id3TgMpvqv --out app/drifly-release.jks
gdrive readonly download 1RcSjtJaByanj2jeIfq7TyyW4E_G6UvBT --out signing.env
./gradlew assembleRelease
```
(`gdrive` = skill Google Drive do Antigravity, logada na conta acima.)
Nunca commitar `signing.env` nem o `.jks`. Nunca compartilhar a pasta por link publico.
