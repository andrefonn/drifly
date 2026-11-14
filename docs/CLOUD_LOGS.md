# Logs na Nuvem (Antigravity Nuvem + Google Drive)

Quando você está na rua sem cabo USB/ADB, o **Antigravity Nuvem** não consegue rodar `adb` no seu celular. Para que ele consiga ver os logs e erros do DriFly de qualquer lugar, o app agora possui **2 modos** em **Configurações (⚙️) → Diagnóstico & Logs na Nuvem**:

---

## Modo 1: Salvar no Drive (Zero Configuração)

1. Abra o DriFly → toque em **Configurações (⚙️)** → **Diagnóstico & Logs na Nuvem**.
2. Toque em **Salvar no Drive**.
3. Escolha o app **Google Drive** e salve o arquivo `drifly_diag_....txt` (recomendado: pasta `DriFly-Logs (PRIVADO)`).
4. No **Antigravity Nuvem**, basta pedir: *"Leia meu log mais recente do DriFly no Google Drive"*.

---

## Modo 2: Envio com 1 Toque + Auto-Crash (Google Apps Script)

Se você quiser enviar logs com **1 único toque** (e enviar automaticamente quando o app abrir após um erro fatal/crash):

### Passo 1 — Criar o Web App no Google Apps Script (leva ~2 min)

1. Acesse [script.google.com](https://script.google.com) logado na sua conta Google (`a.l.s.fontes123@gmail.com`).
2. Clique em **Novo projeto** e cole o código abaixo:

```javascript
// Token secreto opcional (coloque o mesmo no app DriFly)
const SECRET_TOKEN = "TROQUE_POR_UM_TOKEN_SECRETO";
const FOLDER_NAME = "DriFly-Logs (PRIVADO)";

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    if (SECRET_TOKEN && data.token !== SECRET_TOKEN) {
      return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "Token inválido" }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    const folder = getOrCreateFolder_(FOLDER_NAME);
    const content = [
      "Reason: " + (data.reason || "manual"),
      "Timestamp: " + (data.timestamp || ""),
      "Device: " + (data.device || ""),
      "AppVersion: " + (data.appVersion || ""),
      "",
      data.report || ""
    ].join("\n");

    // Sempre mantém um arquivo fixo atualizado com o último log
    upsertFile_(folder, "drifly_diag_latest.txt", content);

    return ContentService.createTextOutput(JSON.stringify({ ok: true }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function getOrCreateFolder_(name) {
  const it = DriveApp.getFoldersByName(name);
  return it.hasNext() ? it.next() : DriveApp.createFolder(name);
}

function upsertFile_(folder, filename, content) {
  const it = folder.getFilesByName(filename);
  if (it.hasNext()) {
    const file = it.next();
    file.setContent(content);
    return file;
  }
  return folder.createFile(filename, content, MimeType.PLAIN_TEXT);
}
```

3. Clique em **Implantar → Nova implantação → Tipo: App da Web**:
   - **Executar como**: *Eu* (`a.l.s.fontes123@gmail.com`)
   - **Quem pode acessar**: *Qualquer pessoa* (protegido pelo `SECRET_TOKEN`)
4. Autorize o acesso ao Google Drive e copie a **URL do App da Web** (`https://script.google.com/macros/s/.../exec`).

### Passo 2 — Configurar no DriFly

1. Abra o DriFly → **Configurações (⚙️)** → **Diagnóstico & Logs na Nuvem** → **Configurar Webhook (Drive)**.
2. Cole a URL do Web App e o mesmo **Token secreto**.
3. Deixe marcado **Enviar automaticamente ao abrir após crash**.
4. Pronto! Agora basta tocar em **Enviar Nuvem** sempre que quiser mandar os logs na hora.

---

## Como o Antigravity Nuvem lê o log

Na nuvem, o agente usa o plugin `gdrive` já conectado na sua conta:

```bash
gdrive search "drifly_diag"
gdrive readonly download <FILE_ID> --out /tmp/drifly_diag.txt
```

> ⚠️ **Privacidade**: Nunca envie `drifly_diag_*.txt` para o repositório público do GitHub, pois o log pode conter endereços de corridas, valores e dados do aparelho. Mantenha sempre no seu Google Drive privado.
