# DriFly: Redesign do Overlay, Histórico Unificado de Corridas e Modo Casa

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o redesign aprovado do Overlay com 3 métricas herói e veredito binário, transformar o extrato por período em um card de "Última Corrida" com histórico unificado e adicionar o recurso "Modo Casa" com georreferenciamento de destino.

**Architecture:**
- **Overlay Visual**: Port direto do HTML aprovado para `overlay_bubble.xml` e `BubbleOverlayService.kt`, com veredito `Aceitar ✅`/`Recusar ❌`, 3 Hero Pillars (R$/km, R$/h, Nota) e Street View colapsável.
- **Card Última Corrida & Histórico**: Substitui os 4 botões soltos na `MainActivity.kt` por um card proeminente que lê a corrida mais recente no `DatabaseHelper.kt` e abre um modal de histórico com chips de período (`Hoje`, `Esta Semana`, `Este Mês`, `Ano`) e menu de ações em cada corrida.
- **Modo Casa**: Ícone de casa na barra superior do app, persistência de endereço/coordenadas de casa em `PreferencesManager.kt` e cálculo geométrico de aproximação (`distanceTo(Home)`) para exibir a badge `🏠 Rumo a Casa` no overlay.

**Tech Stack:** Kotlin, Android Jetpack, XML Layouts (ConstraintLayout/LinearLayout), Android WindowManager, FusedLocationProviderClient, Google Geocoder, JUnit4.

**Spec:** Baseado no layout testado e aprovado em `design-preview/index.html` e nas diretrizes fornecidas pelo usuário.

## Global Constraints
- Preservar a identidade visual dark glassmorphism e cores (#31D477, #FF8A24, #FF454D, #161924).
- Não utilizar botões de simulação/teste na interface visível do usuário.
- Manter compatibilidade com Android 8.0 até 14 (API 26 a 34).
- Executar e validar suíte de testes unitários antes de qualquer deploy via ADB.

---

### Task 1: Portar o Redesign do Overlay para o Android

**Files:**
- Modify: `app/src/main/res/layout/overlay_bubble.xml`
- Modify: `app/src/main/java/com/drifly/app/BubbleOverlayService.kt`
- Modify: `app/src/main/java/com/drifly/app/RideOffer.kt`

**Interfaces:**
- Consumes: `RideOffer(platform, price, distanceKm, durationMinutes, ratePerKm, ratePerHour, estimatedFuelCost, netProfit, passengerRating, isLowRating, destinationAddress, isHeadingHome, homeDistanceDeltaKm)`
- Produces: Visualização no `WindowManager` com veredito instantâneo (`Aceitar ✅` / `Recusar ❌` ou veredito direcional do Modo Casa), grid de 3 números grandes com barrinhas de status e card de `LUCRO REAL`.

- [ ] **Step 1: Atualizar o modelo de dados `RideOffer.kt`**
  - Adicionar campos `val isHeadingHome: Boolean = false` e `val homeDistanceDeltaKm: Double = 0.0` (valor negativo quando aproxima, positivo quando afasta).

- [ ] **Step 2: Reescrever `overlay_bubble.xml` com a nova hierarquia visual**
  - Linha de topo: `tvVerdictBadge` (`Aceitar ✅` / `Recusar ❌` ou Direcional do Modo Casa) e `btnCloseBubble`.
  - Linha de metadados: `tvPlatformBadge`, `tvMetaGross`, `tvMetaDistance`, `tvMetaDuration`.
  - Grid dos 3 Heróis:
    - Coluna 1: `tvRateKm` com mini-label `R$ / KM` e barrinha `viewBarRateKm`.
    - Coluna 2: `tvRateHour` com mini-label `R$ / HORA` e barrinha `viewBarRateHour`.
    - Coluna 3: `tvRating` com mini-label `★ NOTA` e barrinha `viewBarRating`.
  - Card de Valor Real:
    - `tvNetProfitTag` ("LUCRO REAL") e `tvNetProfit` em destaque.
    - `tvFuelCost` ("- R$ X,XX").
  - Badge compacta do Modo Casa: `layoutModoCasaBadge` com ícone de casa e distância com sinal: `🏠 -6.5 km` ou `🏠 +6.5 km` (sem texto longo).
  - Destino & Street View colapsável: `btnToggleStreetView` e `frameStreetViewContainer`.

- [ ] **Step 3: Atualizar lógica de binding em `BubbleOverlayService.kt`**
  - Se Modo Casa **desativado**:
    - Calcular veredito padrão: `isProfitable = (ratePerKm >= prefs.minRatePerKm && ratePerHour >= 30.0 && !isLowRating)`.
    - Veredito exibe `Aceitar ✅` ou `Recusar ❌`.
  - Se Modo Casa **ativado**:
    - Badge exibe estritamente `🏠 -X.X km` (aproximando) ou `🏠 +X.X km` (afastando).
    - Veredito direcional (sem a palavra "Aceitar"):
      - Aproximando: `delta <= -8.0 km` -> `Perfeita Direção ✅`, `-8.0 km < delta <= -4.0 km` -> `Excelente Direção ✅`, `-4.0 km < delta < 0 km` -> `Boa Direção ✅`.
      - Afastando: `0 km < delta <= 4.0 km` -> `Ruim Direção ❌`, `4.0 km < delta <= 9.0 km` -> `Péssima Direção ❌`, `delta > 9.0 km` -> `Evitar Direção ❌`.
  - Definir cores das barrinhas de status dos 3 heróis (`#31D477`, `#FBBF24`, `#FF454D`).
  - Sincronizar binding tanto no fluxo com Service ativo quanto no `showDirectOverlay`.

---

### Task 2: Implementar Card de "Última Corrida" e Modal Unificado de Histórico

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/drifly/app/MainActivity.kt`
- Modify: `app/src/main/java/com/drifly/app/DatabaseHelper.kt`
- Create: `app/src/main/res/layout/card_latest_ride.xml` (ou embutido em `activity_main.xml`)
- Modify: `app/src/main/res/layout/dialog_report.xml`

**Interfaces:**
- Consumes: `db.getLatestRide()`, `db.getRidesBetween()`, `db.getExpensesBetween()`
- Produces: Card de última corrida no Dashboard com navegação fluida para histórico completo sem deixar botões marcados indevidamente.

- [ ] **Step 1: Adicionar método `getLatestRide(): RideRecord?` em `DatabaseHelper.kt`**
  - Executar query no SQLite ordenando por `id DESC LIMIT 1`.

- [ ] **Step 2: Substituir a barra de botões soltos em `activity_main.xml` pelo Card de Última Corrida**
  - Remover os botões estáticos `btnReportDaily`, `btnReportWeekly`, `btnReportMonthly`, `btnReportYearly`.
  - Inserir container `cardLatestRide`:
    - Cabeçalho: Badge de status, plataforma e tempo relativo ("Há X min").
    - Corpo: Preço Bruto, Lucro Real, KM, Duração e Gasto de Combustível.
    - Rodapé: Botão de ação estilizado `btnOpenFullHistory` ("📊 Ver Histórico Completo →").

- [ ] **Step 3: Refatorar o Modal de Histórico (`dialog_report.xml`)**
  - Implementar linha superior de Chips de Filtro: `[Hoje]`, `[Esta Semana]`, `[Este Mês]`, `[Ano]`.
  - Exibir métricas agregadas do período (Lucro Líquido, Bruto, Despesas, KM, Média R$/km).
  - Listar corridas e despesas em lista limpa.
  - Ao clicar em uma corrida da lista, exibir opções:
    - Confirmar / Alterar Valor Final
    - Cancelada com Taxa
    - Cancelada sem Taxa
    - Excluir do Extrato.
  - Garantir fechamento limpo sem efeito colateral de seleção residual na tela principal.

- [ ] **Step 4: Integrar no `MainActivity.kt`**
  - Atualizar `updateLatestRideCard()` em `onResume()` e após cada recebimento de broadcast.

---

### Task 3: Implementar a Funcionalidade "Modo Casa"

**Files:**
- Create: `app/src/main/res/drawable/ic_home_mode.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/drifly/app/PreferencesManager.kt`
- Create: `app/src/main/java/com/drifly/app/ModoCasaHelper.kt`
- Modify: `app/src/main/java/com/drifly/app/RotaAccessibilityService.kt`
- Modify: `app/src/main/java/com/drifly/app/MainActivity.kt`

**Interfaces:**
- Consumes: `prefs.isModoCasaActive`, `prefs.homeLatitude`, `prefs.homeLongitude`, `prefs.homeAddress`
- Produces: `ModoCasaHelper.evaluateRideTowardsHome(context, destinationAddress, currentLoc): HomeDirectionResult`

- [ ] **Step 1: Atualizar `PreferencesManager.kt` para o Modo Casa**
  - Adicionar propriedades:
    - `isModoCasaActive: Boolean`
    - `homeAddress: String`
    - `homeLatitude: Float`
    - `homeLongitude: Float`

- [ ] **Step 2: Criar o ícone e botão do Modo Casa no Topo da `MainActivity`**
  - Criar vetor `ic_home_mode.xml`.
  - Adicionar `ImageButton btnToggleHomeMode` ao lado de `btnOpenSettings` no topo.
  - Implementar feedback visual: verde brilhante com contorno quando ativo; cinza glass quando inativo.
  - Ao clicar: se não houver endereço de casa cadastrado, abre diálogo para configurar; se houver, alterna o estado com banner toast.

- [ ] **Step 3: Adicionar Configuração de Endereço de Casa na Aba 2 de Configurações**
  - Card dedicado `cardConfigModoCasa`:
    - Campo de texto `etHomeAddress` com botão "Salvar Endereço".
    - Botão "📍 Usar Localização GPS Atual como Casa".
    - Geocodificar e persistir endereço + lat/lng.

- [ ] **Step 4: Criar `ModoCasaHelper.kt`**
  - Calcular distância do motorista até a casa: `distDriverToHome`.
  - Geocodificar o destino da corrida: `destLatLng`.
  - Calcular distância do destino até a casa: `distDestToHome`.
  - Calcular delta assinado: `deltaKm = distDestToHome - distDriverToHome`:
    - Se `deltaKm < 0`: aproxima de casa (ex: `-6.5 km`). Retorna `isHeadingHome = true`, `deltaKm`.
    - Se `deltaKm >= 0`: afasta de casa (ex: `+7.0 km`). Retorna `isHeadingHome = false`, `deltaKm`.

- [ ] **Step 5: Integrar no `RotaAccessibilityService.kt` e `RideSimulator.kt`**
  - Quando o Modo Casa estiver ativo e houver endereço de destino, calcular delta e injetar em `RideOffer(isHeadingHome, homeDistanceDeltaKm)`.

---

### Task 4: Testes Unitários e Validação Completa via ADB

**Files:**
- Modify: `app/src/test/java/com/drifly/app/ParsingEngineTest.kt`
- Create: `app/src/test/java/com/drifly/app/ModoCasaTest.kt`

- [ ] **Step 1: Criar testes unitários para a lógica do Modo Casa (`ModoCasaTest.kt`)**
  - Validar cálculo de aproximação quando o destino reduz a distância até a casa (`deltaKm < 0`).
  - Validar cálculo de afastamento quando o destino aumenta a distância até a casa (`deltaKm > 0`).
  - Validar faixas de veredito: `Perfeita Direção ✅`, `Excelente Direção ✅`, `Boa Direção ✅` vs `Ruim Direção ❌`, `Péssima Direção ❌`, `Evitar Direção ❌`.

- [ ] **Step 2: Executar `./gradlew.bat test` e garantir 100% de sucesso**

- [ ] **Step 3: Gerar APK e instalar via ADB no Motorola Moto G30**
  - `./gradlew.bat assembleDebug`
  - `adb install -r bin/DriFly.apk`

- [ ] **Step 4: Executar testes reais via comandos ADB**
  - Simular toque com Modo Casa ativado rumo a casa -> Verificar badge `🏠 -6.5 km` e veredito `Excelente Direção ✅`.
  - Simular toque com Modo Casa ativado afastando de casa -> Verificar badge `🏠 +7.5 km` e veredito `Péssima Direção ❌`.
  - Simular toque com Modo Casa desativado (corrida ruim) -> Verificar veredito `Recusar ❌`.
  - Simular toque com Modo Casa desativado (corrida excelente) -> Verificar veredito `Aceitar ✅` com cálculo para moto (31 km/L).
  - Verificar abertura do Histórico e interação com a última corrida.
