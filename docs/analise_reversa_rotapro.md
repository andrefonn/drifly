# Relatório de Engenharia Reversa: Rota Pro (`app.rotapro`)

**Alvo:** `app.rotapro` (v1.1.70, build 83)  
**Dispositivo Fonte:** Motorola Moto G30 (`ZF523JBHZW` / `caprip`)  
**Data da Análise:** 04/11/2026  
**Metodologia:** Extração via ADB (`base.apk` + `split_config.arm64_v8a.apk`), inspeção estática REA Artifact Graph, análise de DEX, descompactação de assets Flutter e binários nativos AOT (`libapp.so`).

---

## 1. Visão Geral da Arquitetura

O **Rota Pro** é um utilitário projetado para motoristas e entregadores de aplicativo (Uber, 99, 99Food, iFood, Keeta, InDrive e Bolt). Seu objetivo principal é capturar, calcular a rentabilidade (R$/km, R$/hora) e sobrepor um painel flutuante (overlay) em tempo real sobre as telas de ofertas de corrida dos aplicativos parceiros.

### Pilha de Tecnologia:
1. **Frontend / Core UI:** **Flutter** (Dart AOT compilado em `libapp.so` e `libflutter.so`).
2. **Camada Nativa Android (`classes.dex`):**
   - `app.rotapro.RotaAccessibilityService`: Serviço de acessibilidade (`AccessibilityService`) responsável por inspecionar a árvore de visualização (UI Node Hierarchy) de outros aplicativos em tempo de execução.
   - Gerenciamento de Janelas Flutuantes: `android.permission.SYSTEM_ALERT_WINDOW`.
   - OCR e Visão Computacional: **Google ML Kit Text Recognition** (`libmlkit_google_ocr_pipeline.so` + assets em `mlkit-google-ocr-models/`).
3. **Backend / Infraestrutura:**
   - **Supabase BaaS:** `https://jyjrfyrxvfjuirblkjfn.supabase.co`
     - Autenticação: Supabase Auth (`/auth/v1`)
     - Configurações remotas e feature flags: Tabela `app_config` (`eq.app_update`, `eq.oferta_teste_v2`, `eq.payment_url`, `eq.review_prompt`, `eq.oferta_pos_teste`)
     - Sincronização em tempo real: Supabase Realtime (`/realtime/v1`)
     - Edge Functions: Supabase Functions (`/functions/v1`)
     - Storage / Backups: `/storage/v1` (incluindo rotinas como `rotapro-financas-backup.json`)
   - **Firebase Analytics / Crashlytics & Google Play Services**.

---

## 2. Mecanismo de Funcionamento e Captura (Core Engine)

O aplicativo opera através de dois métodos combinados para ler os dados da tela do motorista:

### A. Inspeção de Árvore de Nós de Acessibilidade (A11y)
O serviço `RotaAccessibilityService` escuta eventos do sistema quando os apps suportados recebem uma nova oferta.

Os IDs de view e âncoras mapeadas internamente no arquivo de configuração `receita_android.json` (versão `2026-10-23.181207`) incluem:
- **Uber (`com.ubercab.driver`):**
  - Botões / Áreas de toque: `com.ubercab.driver:id/primary_touch_area`
  - Marcadores de mapa: `com.ubercab.driver:id/map_marker_anchor`
  - Lista de ofertas (Radar de Viagens): `com.ubercab.driver:id/driver_offers_job_board_recycler_view`
  - Identificação de passageiro: `:id/name`, `ub__chat_header_title`
- **99 Motorista (`com.app99.driver`):**
  - Containers de ofertas: `com.app99.driver:id/broad_order_container`, `com.app99.driver:id/flutter_deal_gesture_container`
  - Tela de detalhes: `TripPickerDetailActivity`
  - Roteamento e mapa: `com.app99.driver:id/maprouter_fullnav_navcard_layout`, `com.app99.driver:id/order_hall_map_fragment`
- **Bolt Driver (`ee.mtakso.driver`):**
  - Botão de ação: `ee.mtakso.driver:id/button`
  - Rota e endereço: `ee.mtakso.driver:id/routeTitle`, `ee.mtakso.driver:id/routeSubtitle`
  - Passageiro e valores: `ee.mtakso.driver:id/riderInfoText`, `ee.mtakso.driver:id/tripInfo`
- **iFood Entregador (`br.com.ifood.driver.app`):**
  - Contêiner de oferta: `br.com.ifood.driver.app:id/routeOfferFragmentContainer`
- **Keeta / Meituan Courier (`com.sankuai.sailor.courier`):**
  - Elementos de preço, distância e botão: `tv_price`, `tv_distance`, `tv_address`, `tv_action_button`, `iv_overlay`, `tv_add_on_price`
- **InDrive (`sinet.startup.inDriver`):**
  - Aceite de preço: `sinet.startup.inDriver:id/order_button_accept_price_stage_0`
  - Informações de usuário: `user_info`, `driver_common_textview_name`

### B. Motor de Extração via Expressões Regulares (`receita_android.json`)
A extração de valores financeiros e métricas de viagem é feita por regex declarativo embutido (com suporte a atualização dinâmica via backend):
- **Preço/Tarifa:**
  - Extração de valores em Real (`R$ X,XX`) e Euro (`€ X,XX` para Bolt)
  - Identificação de bonificações/adicionais (`+\s*R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2})`)
- **Quilometragem e Tempo:**
  - Padrões de distância: `(\d{1,3}(?:[.,]\d{1,2})?)\s*km`
  - Padrões de tempo e paradas: `(\d{1,3})\s*min`, `(\d{1,2})\s*parada(s)`
- **Categorias identificadas:**
  - Uber: `uberx`, `comfort`, `black`, `moto`, `flash`, `juntos/share`, `prioridade`, `pet`
  - 99: `99moto`, `99negocia`, `perfil premium`, `perfil essencial`, `entrega food`
  - Bolt: `economy`, `comfort`, `premium`, `electric`, `green`, `xl`, `van`

---

## 3. Comunicação Interprocessos (IPC) e Comunicação com UI Flutter

No binário nativo e `classes.dex`:
- A camada Java (`RotaAccessibilityService`) emite broadcasts internos no dispositivo para sincronizar dados:
  - `app.rotapro.ALERTA`
  - `app.rotapro.VIAGEM`
  - `app.rotapro.RIDE_SAVED`
  - `app.rotapro.PERFIS`
  - `app.rotapro.FINANCAS`
- O Flutter consome esses eventos através do MethodChannel `com.rotapro.app/channel`.
- Quando uma corrida é validada contra os critérios definidos pelo usuário (ex: valor mínimo por KM, distância máxima até embarque), o serviço desenha o Overlay na tela (`overlayShowPrice`, `overlayShowTotalDistance`).

---

## 4. Endpoints e Serviços Remotos Mapeados

| Serviço / Domínio | Finalidade |
| :--- | :--- |
| `jyjrfyrxvfjuirblkjfn.supabase.co` | API principal (REST, Realtime, Auth, Storage, Edge Functions) |
| `rotapro.app/pt/#preco` | Landing page de planos e assinaturas |
| `rotapro.app/ajuda/` & `rotapro.app/continuar/` | Fluxos de onboarding e suporte ao usuário |
| `wa.me/5531987590900` | Suporte via WhatsApp direto |
| `google.android.gms / ML Kit` | Modelos de detecção ótica de caracteres local |
