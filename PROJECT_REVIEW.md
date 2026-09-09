# VoxPen (語墨) 專案代碼審查與改進建議報告

**審查日期**：2026-09-09  
**審查目標目錄**：`/Users/tomas/Documents/Projects/voxpen`  
**應用程式包名**：`com.voxpen.app` (v1.2.12, Build 17)  
**審查性質**：全專案靜態代碼與架構審查（唯讀審查，未修改專案既有程式碼）

---

## 執行摘要 (Executive Summary)

VoxPen（語墨）是一款針對 Android 平台的 AI 語音輸入法與逐字稿應用，定位為「以台灣繁體中文（zh-TW）為核心、支援中英混合、自帶金鑰 (BYOK) 的 Typeless 替代方案」。整體架構基於現代 Android 技術棧（Kotlin 2.1、Jetpack Compose、Material 3、Hilt、Room、DataStore、Retrofit、Coroutines/Flow），並且建立了良好的單元測試基礎（55+ 個測試類別）。

然而，本次審查發現了數項**阻礙產品核心價值與營運的嚴重缺陷（Critical Bugs）**，以及多項**架構違規、記憶體洩漏、UI 主線程阻塞（ANR 風險）與代碼維護性問題**。

### 🚨 最核心的高危問題概覽：
1. **繁簡轉換嚴重反向錯誤**：本專案本應「保持繁體中文」，但代碼中誤用 OpenCC `TW2SP`，導致所有語音輸入與潤稿結果被強制轉成**大陸簡體中文**。
2. **Google Play 內購未確認（Missing Purchase Acknowledgment）**：購買成功後未呼叫 `acknowledgePurchase`，將導致所有付費用戶在 3 天後被 Google Play 自動退款並撤銷 Pro 權限。
3. **雙候選欄（原文 vs 潤稿）核心體驗缺失**：`showDualRows` 淪為死代碼，鍵盤在潤稿完成後直接強制上屏，使用者無法預覽與比對潤稿結果。
4. **大音檔轉錄非 WAV 格式分塊損毀**：長音檔非 WAV 格式時直接以 byte 截斷，且 MIME 與檔名寫死為 `audio/wav`，導致 API 400 失敗或解碼損毀。
5. **Groq 預設 LLM 模型不存在**：`LlmProvider.Groq` 的預設模型設定為不存在的 `openai/gpt-oss-120b`，切換設定後會導致潤稿全面報錯。
6. **日誌記錄在 UI 主線程同步阻塞（ANR 風險）**：`DownloadLogTree.isLoggable` 在印日誌時直接使用 `runBlocking` 同步讀取 DataStore 磁碟。

---

## 目錄
- [一、嚴重功能與業務邏輯缺陷 (Critical / High)](#一嚴重功能與業務邏輯缺陷-critical--high)
- [二、架構設計與生命週期問題 (Architecture & Lifecycle)](#二架構設計與生命週期問題-architecture--lifecycle)
- [三、效能、記憶體與 ANR 風險 (Performance & Stability)](#三效能記憶體與-anr-風險-performance--stability)
- [四、安全性與資料持久化問題 (Security & Persistence)](#四安全性與資料持久化問題-security--persistence)
- [五、語音辨識、NLP 與在地化體驗 (Voice Input & NLP Quality)](#五語音辨識nlp-與在地化體驗-voice-input--nlp-quality)
- [六、代碼規範與可維護性 (Code Quality & Maintainability)](#六代碼規範與可維護性-code-quality--maintainability)
- [七、改進優先級與行動建議 (Actionable Roadmap)](#七改進優先級與行動建議-actionable-roadmap)

---

## 一、嚴重功能與業務邏輯缺陷 (Critical / High)

### 1. 繁簡轉換嚴重反向錯誤 (Critical Chinese Script Inversion)
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/util/ChineseTextNormalizer.kt` (第 17-21 行)
  - `app/src/main/java/com/voxpen/app/ime/VoxPenIME.kt` (第 198 行)
- **問題代碼**：
  ```kotlin
  // ChineseTextNormalizer.kt
  ChineseConverter.convert(text, ConversionType.TW2SP, context) // TW2SP: 台灣繁體 -> 大陸簡體
  
  // VoxPenIME.kt
  private fun normalizeOutputText(text: String): String {
      val c = if (translationEnabled) translationTargetLanguage == SttLanguage.Chinese 
              else currentSttLanguage == SttLanguage.Auto || currentSttLanguage == SttLanguage.Chinese
      return if (c) ChineseTextNormalizer.toMainlandSimplified(text, applicationContext) else text
  }
  ```
- **影響分析**：
  專案在 `CLAUDE.md` 與 Prompt 中再三強調「以台灣繁體中文 (zh-TW) 為核心」、「保持繁體中文」、「一律使用全形標點」。然而，在輸入法提交文字的最後一哩路，`normalizeOutputText` 居然呼叫了 `TW2SP`，將所有文字轉換成大陸簡體中文並替換大陸用語！
  這會讓所有台灣使用者輸入繁體語音時，屏幕上輸出的全部變成簡體字，完全背離產品定位。
- **改進建議**：
  1. OpenCC 轉換型別應改為 `ConversionType.S2TWP`（簡體轉台灣繁體，含慣用詞轉換），以防 Whisper 預設輸出簡體。
  2. 函式名稱應重構為 `toTaiwanTraditional(text: String, context: Context)`。
  3. 在設定中增加繁簡設定開關（「繁體中文（台灣）」/「簡體中文」/「不進行轉換」），尊重不同地區使用者的偏好。

---

### 2. Google Play 內購未確認訂單（Missing In-App Purchase Acknowledgment）
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/billing/BillingManager.kt` (第 127-149 行)
- **問題代碼**：
  ```kotlin
  override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
      when (result.responseCode) {
          BillingClient.BillingResponseCode.OK -> {
              purchases?.forEach { purchase ->
                  if (purchase.products.contains(PRODUCT_ID_PRO) &&
                      purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                  ) {
                      _proStatus.value = ProStatus.Pro(ProSource.GOOGLE_PLAY)
                      Timber.d("Pro purchased successfully")
                      // ⚠️ 致命缺陷：完全沒有呼叫 acknowledgePurchase！
                  }
              }
          }
          ...
      }
  }
  ```
- **影響分析**：
  依據 Google Play Billing 規範，一次性內購（In-app non-consumable）必須在使用者購買後 3 天內呼叫 `AcknowledgePurchaseParams` 進行確認。若未確認，Google Play 將判定交易異常，**自動退款給使用者並撤銷該筆購買**。這會造成付費購買 Pro 的用戶在 3 天後全部失效且自動退費。
- **改進建議**：
  在 `onPurchasesUpdated` 及 `queryExistingPurchases` 中，當 `purchase.purchaseState == Purchase.PurchaseState.PURCHASED` 時，檢查 `!purchase.isAcknowledged`，並發起非同步確認：
  ```kotlin
  if (!purchase.isAcknowledged) {
      val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
          .setPurchaseToken(purchase.purchaseToken)
          .build()
      billingClient?.acknowledgePurchase(acknowledgeParams) { billingResult ->
          if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
              Timber.d("Purchase acknowledged successfully")
          }
      }
  }
  ```
  此外，在 `onBillingServiceDisconnected()` 應實作指數退避重連機制。

---

### 3. 雙候選欄（原文 vs 潤稿）核心體驗缺失與死代碼
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ime/VoxPenIME.kt` (第 190 行, 第 201 行)
  - `app/src/main/res/layout/keyboard_view.xml` (第 94-100 行)
- **問題代碼**：
  ```kotlin
  // VoxPenIME.kt
  is ImeUiState.Refined -> {
      timerHandler.removeCallbacks(timerRunnable)
      val o = normalizeOutputText(state.original)
      val r = normalizeOutputText(state.refined)
      showStatusRow(o, false) // ⚠️ 只顯示了狀態列（且放的是原始字串）
      copyStatusButton?.visibility = View.VISIBLE
      copyStatusButton?.setOnClickListener { copyToClipboard(o) }
      currentInputConnection?.commitText(r, 1) // ⚠️ 直接自動上屏！
  }
  
  // 第 201 行定義了 showDualRows，但全專案從未被呼叫！
  private fun showDualRows(original: String, refined: String?) { ... }
  ```
  ```xml
  <!-- keyboard_view.xml: 刻意將候選原文高度設為 0dp 隱藏 -->
  <TextView
      android:id="@+id/candidate_original"
      android:layout_width="match_parent"
      android:layout_height="0dp"
      android:visibility="gone" />
  ```
- **影響分析**：
  CLAUDE.md 與 ROADMAP 宣稱 VoxPen 具有 Typeless-like 的「兩行結果預覽（雙候選：原文 vs 潤稿）」核心優勢。但在實際運行中，潤稿文字直接被無條件注入文字框，而候選欄只留下一行原文，`showDualRows` 成為孤兒函數。使用者失去「比對 AI 改了什麼」、「一鍵選擇要原文還是潤稿」的核心能力，若 AI 出現幻覺改錯，使用者往往難以察覺且無法挽回。
- **改進建議**：
  1. 重建候選欄互動機制：在 `ImeUiState.Refined` 狀態下呼叫 `showDualRows(o, r)`。
  2. 修復 `keyboard_view.xml` 中 `candidate_original` 的 layout 佈局。
  3. 提供點擊候選詞上屏：點選潤稿行提交 `r`，點選原文行提交 `o`，或提供設定選項是否「自動上屏潤稿」。

---

### 4. 音檔轉錄非 WAV 格式分塊損毀與 MIME/檔名寫死
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/domain/usecase/TranscribeFileUseCase.kt` (第 42-63 行)
  - `app/src/main/java/com/voxpen/app/data/repository/SttRepository.kt` (第 111-118 行)
- **問題代碼**：
  ```kotlin
  // TranscribeFileUseCase.kt
  val chunks = if (AudioChunker.isWav(fileBytes)) {
      AudioChunker.chunkWav(fileBytes, maxChunkBytes)
  } else {
      AudioChunker.chunk(fileBytes, maxChunkBytes) // ⚠️ 對 MP3/M4A 直接 byte 切割
  }
  
  // SttRepository.kt
  val filePart = MultipartBody.Part.createFormData(
      "file",
      "recording.wav", // ⚠️ 無論傳入什麼格式，檔名全部寫死 recording.wav
      wavBytes.toRequestBody("audio/wav".toMediaType()) // ⚠️ MIME 寫死 audio/wav
  )
  ```
- **影響分析**：
  1. MP3、M4A、AAC 等有損壓縮音訊包含元數據標頭與音訊幀結構。當檔案超過 25MB 時，`AudioChunker.chunk` 僅依 byte 長度硬切，導致切出的片段失去標頭、幀損毀，後續片段完全無法解碼。
  2. 即使檔案小於 25MB，將 MP3/M4A 音訊以 `recording.wav` 與 `audio/wav` 提交給 Groq 或 OpenAI Whisper，雲端會因為檔案實際格式與副檔名/MIME 不符而直接回傳 `400 Bad Request`。
- **改進建議**：
  1. 在 `SttRepository.transcribe` 支援傳遞正確的 `fileName` 與 `mimeType`（例如 `audio/mpeg`、`audio/mp4`）。
  2. 大檔案切割應基於 Android 系統的 `MediaExtractor` / `MediaCodec` 解碼為 PCM 後分塊，或使用 FFmpeg/MediaMuxer 進行合法的容器層切割。

---

### 5. Groq 預設 LLM 模型不存在
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/data/model/LlmProvider.kt` (第 30-35 行)
  - `app/src/main/java/com/voxpen/app/ui/settings/SettingsViewModel.kt` (第 91 行)
- **問題代碼**：
  ```kotlin
  // LlmProvider.kt
  data object Groq : LlmProvider(
      key = "groq",
      baseUrl = "https://api.groq.com/openai/",
      models = listOf(
          LlmModelOption("openai/gpt-oss-120b", "GPT-OSS 120B", tag = "recommended", isDefault = true), // ⚠️ 不存在
          LlmModelOption("openai/gpt-oss-20b", "GPT-OSS 20B", tag = "fast"),                           // ⚠️ 不存在
          ...
      ),
  )
  
  // SettingsViewModel.kt
  fun setLlmProvider(v: LlmProvider) {
      viewModelScope.launch {
          preferencesManager.setLlmProvider(v)
          if (v.defaultModelId.isNotBlank()) preferencesManager.setLlmModel(v.defaultModelId) // ⚠️ 自動設為錯誤模型
      }
  }
  ```
- **影響分析**：
  Groq API 上並無 `openai/gpt-oss-120b` 或 `openai/gpt-oss-20b` 這兩款模型（應為誤植或預留）。當使用者在設定中切換 LLM 提供者為 Groq 時，系統自動選中 `v.defaultModelId`，導致後續所有呼叫 Groq 潤稿或 Speak-to-Edit 的請求均因模型不存在而噴出 404 或 400 錯誤。
- **改進建議**：
  將 Groq 的推薦與預設模型修正為官方現行支援的模型：
  - 預設推薦：`llama-3.3-70b-versatile`（與 `PreferencesManager.DEFAULT_LLM_MODEL` 保持一致）
  - 快速模型：`llama-3.1-8b-instant`
  - 繁體/中文優選：`qwen-2.5-32b`

---

## 二、架構設計與生命週期問題 (Architecture & Lifecycle)

### 1. Composable 繞過 ViewModel 直接執行業務邏輯與依賴注入
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ui/transcription/TranscriptionScreen.kt` (第 92-170 行)
  - `app/src/main/java/com/voxpen/app/ui/transcription/TranscriptionEntryPoint.kt`
- **問題分析**：
  `TranscriptionScreenContent` 作為一個 UI Composable，竟然透過 `EntryPointAccessors.fromApplication` 直接拉取 UseCase、Repository、PreferencesManager，並使用 `rememberCoroutineScope()` 啟動整個長耗時轉錄協程。
  `TranscriptionViewModel` 內只做了空洞的狀態轉發。
- **影響**：
  - **轉錄中斷**：音檔轉錄可能長達數分鐘，若使用者旋轉螢幕、收到來電切換 App，Composable 銷毀會直接 Cancel `rememberCoroutineScope()`，導致轉錄任務被硬生生殺死。
  - **架構違規**：嚴重違反 Android 官方推薦的 MVVM 架構與單一責任原則，UI 層強烈耦合了底層 I/O 與網路依賴。
- **改進建議**：
  1. 將 `TranscribeFileUseCase` 注入至 `TranscriptionViewModel`。
  2. 轉錄邏輯在 `viewModelScope` 中發起，或透過 Android `WorkManager` / 前台服務（Foreground Service）託管，讓轉錄能在後台穩定運行。

---

### 2. IME 輸入法生命週期記憶體洩漏與重複監聽
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ime/VoxPenIME.kt` (第 95-131 行)
- **問題分析**：
  在 Android 的 `InputMethodService` 生命週期中，`onCreateInputView()` 會隨鍵盤彈出或組態變更被多次呼叫。
  現行代碼每次進入 `onCreateInputView` 都會在長生命週期的 `serviceScope`（綁定 Service 整個生命期）上啟動 7 個 `preferencesManager.*Flow.collect` 協程，且每次都重新建立 `AudioRecorder` 與 `RecordingController`。
- **影響**：
  舊的協程與控制器從未被釋放，持有的舊 View 引用造成嚴重的記憶體洩漏；每次偏好設定變更都會觸發多次重複回呼。
- **改進建議**：
  建立綁定 InputView 生命期的 `inputViewScope`（於 `onCreateInputView` 建立，於 `onDestroyInputView` 取消），或在 Service 初始化（`onCreate`）時僅註冊一次全局監聽。

---

### 3. 首次啟動導航狀態競爭 (Onboarding Race Condition)
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ui/MainActivity.kt` (第 34-37 行)
- **問題代碼**：
  ```kotlin
  val isOnboardingCompleted by mainViewModel.onboardingCompleted.collectAsState(initial = true)
  val startDestination = if (isOnboardingCompleted) "home" else "onboarding"
  NavHost(navController = navController, startDestination = startDestination) { ... }
  ```
- **影響分析**：
  DataStore 讀取為非同步操作。當全新安裝的使用者開啟 App 時，`collectAsState` 的預設值直接給了 `true`，導致 `NavHost` 初始 `startDestination` 被鎖定為 `"home"`。即使 50ms 後 DataStore 發射出 `false`，Navigation Compose 也不會自動重設 `startDestination`。這導致新使用者直接跳過了新手設定導引。
- **改進建議**：
  將狀態改為 nullable `Boolean?`，在值為 `null` 時顯示載入或 Splash 畫面，待確定布林值後再繪製 `NavHost`。

---

### 4. 字典頁面 Pro 資格判定來源錯誤
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ui/dictionary/DictionaryViewModel.kt` (第 23-42 行)
- **問題代碼**：
  ```kotlin
  class DictionaryViewModel @Inject constructor(
      private val repository: DictionaryRepository,
      private val billingManager: BillingManager, // ⚠️ 僅注入 BillingManager
  )
  val isPro: StateFlow<Boolean> = billingManager.proStatus.map { it.isPro }...
  ```
- **影響分析**：
  專案存在兩種 Pro 來源：Google Play 內購（`BillingManager`）與 LemonSqueezy 啟動碼（`LicenseManager`），專案專門設計了 `ProStatusResolver` 來合併這兩者。
  但在 `DictionaryViewModel` 中卻直接引用了 `BillingManager`，導致透過授權碼購買的用戶在字典介面被判定為免費版，被強加 10 個字詞的上限。
- **改進建議**：
  將 `BillingManager` 替換為 `ProStatusResolver`。此外，`FREE_DICTIONARY_LIMIT` 在代碼中寫死為 `10`，但 `ROADMAP.md` 標明為 `50`，規格不一致需統一。

---

## 三、效能、記憶體與 ANR 風險 (Performance & Stability)

### 1. `DownloadLogTree` 主線程同步阻塞 DataStore (Critical ANR Risk)
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/util/DownloadLogTree.kt` (第 28-30 行, 第 42-47 行)
- **問題代碼**：
  ```kotlin
  override fun isLoggable(tag: String?, priority: Int): Boolean =
      priority >= Log.INFO && runBlocking { preferencesManager.downloadLoggingEnabledFlow.first() }
  ```
- **影響分析**：
  Timber 在每次調用 `Timber.i`、`Timber.w`、`Timber.e` 時都會先執行 `isLoggable`。
  這裡居然在調用者的當前線程直接執行 `runBlocking` 去讀取 DataStore（涉及磁碟 I/O 與檔案鎖）！如果在 UI 主線程上有任何日誌輸出，主線程會直接卡住等待磁碟讀取，造成嚴重的介面卡頓、掉幀甚至引發 Android ANR 當機。
  此外，在 `log` 方法中的 `synchronized(lock)` 包含了 MediaStore 的同步查詢與磁碟寫入，同樣是在調用線程執行。
- **改進建議**：
  1. 使用一個執行緒安全的記憶體變數（例如 `AtomicBoolean`）快取是否開啟日誌，由背景協程監聽 DataStore 更新，`isLoggable` 只讀記憶體變數。
  2. 磁碟寫入操作改以生產者-消費者模式（如 Kotlin Channel），由專門的背景單線程協程依序寫入，絕不阻塞日誌呼叫方。

---

### 2. 停止錄音同步卡死主線程 (Main Thread Blocking on Recording Stop)
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ime/AudioRecorder.kt` (第 88 行)
  - `app/src/main/java/com/voxpen/app/ime/VoxPenIME.kt` (第 172 行)
- **問題代碼**：
  ```kotlin
  // AudioRecorder.kt
  fun stopRecording(): ByteArray {
      isRecording = false
      recordingThread?.join(STOP_TIMEOUT_MS) // ⚠️ 阻塞等待最高 2000ms
      ...
  }
  
  // VoxPenIME.kt
  private fun stopRecording() {
      abandonAudioDucking()
      serviceScope.launch { // ⚠️ serviceScope 運行在 Dispatchers.Main！
          recordingController.onStopRecording({ audioRecorder.stopRecording() }, ...)
      }
  }
  ```
- **影響分析**：
  當使用者鬆開麥克風按鈕或點擊停止時，主線程會同步卡在 `Thread.join(2000L)`，導致鍵盤動畫中斷、介面無回應。
- **改進建議**：
  停止錄音與音訊緩衝區擷取操作必須派發至 `Dispatchers.IO` 執行，完成後再切回主線程更新 UI。

---

### 3. 大音檔全量載入記憶體 (Heap Memory Exhaustion / OOM)
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ui/transcription/TranscriptionScreen.kt` (第 96 行)
- **問題代碼**：
  ```kotlin
  val fileBytes = withContext(Dispatchers.IO) {
      context.contentResolver.openInputStream(uri)?.readBytes() // ⚠️ 全量讀取
  }
  ```
- **影響分析**：
  若使用者選取數十 MB 至數百 MB 的音檔（如演講、訪談錄音），`readBytes()` 會在 JVM 堆疊上分配一整塊連續的 ByteArray。Android App 預設記憶體上限通常在 192MB~512MB 之間，極易造成 `OutOfMemoryError` 崩潰閃退。
- **改進建議**：
  改為串流讀取寫入 App 私有快取目錄的暫存檔案，後續分塊轉錄透過 File / InputStream 串流處理，避免整檔載入記憶體。

---

### 4. 音訊分塊轉錄為串行網路呼叫
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/domain/usecase/TranscribeAudioUseCase.kt` (第 34-50 行)
  - `app/src/main/java/com/voxpen/app/domain/usecase/TranscribeFileUseCase.kt` (第 53-78 行)
- **問題分析**：
  當音檔被切分為多個 chunks 時，代碼使用 `for (chunk in chunks)` 逐一發起 STT API 呼叫。如果一個 5 分鐘的檔案切成 5 個 chunk，每個 chunk 耗時 2 秒，總耗時高達 10 秒。
- **改進建議**：
  改用協程並發（`async` / `awaitAll`）搭配並發限制（如 `Semaphore(3)`），並行發送轉錄請求，最後依序組裝結果，可大幅降低等待時間。

---

## 四、安全性與資料持久化問題 (Security & Persistence)

### 1. Debug 模式下 API Key 明文洩漏至系統日誌
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/di/NetworkModule.kt` (第 36-45 行)
- **問題代碼**：
  ```kotlin
  HttpLoggingInterceptor().apply {
      level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
  }
  ```
- **影響分析**：
  `HttpLoggingInterceptor.Level.HEADERS` 會將所有 HTTP 標頭完整輸出至 Logcat。而 STT 與 LLM 均在 Header 中帶有 `Authorization: Bearer <API_KEY>`。在 Debug 版本中，使用者的 API Key 會以明文暴露在 Logcat 中，若使用者回傳系統日誌或裝有日誌監控 App，金鑰將直接外洩。
- **改進建議**：
  加入標頭遮蔽：
  ```kotlin
  HttpLoggingInterceptor().apply {
      level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
      redactHeader("Authorization")
  }
  ```

---

### 2. `UsageLimiter` 使用量數據未持久化
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/billing/UsageLimiter.kt` (第 11 行)
- **問題代碼**：
  ```kotlin
  private var usage = DailyUsage(date = LocalDate.now())
  ```
- **影響分析**：
  `usage` 僅為記憶體變數，未寫入 DataStore、SharedPreferences 或資料庫。在 Android 系統中，IME Service 或 App 進程隨時可能被系統在後台殺死回收。進程重啟後，每日使用計數直接歸零，免費用戶的每日次數限制完全形同虛設。且多執行緒併發存取 `usage` 時未加鎖或使用原子操作。
- **改進建議**：
  將每日使用計數持久化至 DataStore 或 Room 資料庫，跨進程與重啟後依然能維持正確配額。

---

### 3. `EncryptedSharedPreferences` 潛在崩潰與過時 API
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/di/AppModule.kt` (第 45-52 行)
- **問題代碼**：
  ```kotlin
  val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC) // ⚠️ MasterKeys 已廢棄
  return EncryptedSharedPreferences.create(...)
  ```
- **影響分析**：
  1. `MasterKeys` 已被 AndroidX Security 標記為 Deprecated，應使用 `MasterKey.Builder`。
  2. 在許多特定品牌手機（例如部分三星、Pixel 進行系統升級後），Android KeyStore 可能發生金鑰損毀，`EncryptedSharedPreferences.create` 會直接拋出 `KeyStoreException`。因為 AppModule 中未做 try-catch 容錯，這會造成 Hilt 依賴注入圖失敗，App 一啟動便連續崩潰閃退。
- **改進建議**：
  升級至 `MasterKey.Builder`，並在外層加入捕捉 `GeneralSecurityException` / `IOException` 的降級修復邏輯（若金鑰損壞則自動備份/重置 Prefs，防止 App 永久無法開啟）。

---

### 4. 失敗錄音檔儲存無自動過期清理機制
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/data/repository/TranscriptionRepository.kt` (第 72-77 行)
- **問題分析**：
  當錄音轉錄失敗時，音訊檔案會存入內部儲存空間 `files/recordings/`。雖然 Repository 中實作了 `cleanupOrphanedRecordings()`，但經全專案檢索，該方法**除了單元測試之外，在任何正式業務流程中均未被呼叫過**。
- **改進建議**：
  在 App 啟動（`VoxPenApplication`）或資料庫維護時，定期在背景非同步執行清理孤立檔案與過期（如超過 7 天）的失敗音檔。

---

## 五、語音辨識、NLP 與在地化體驗 (Voice Input & NLP Quality)

### 1. Whisper Prompt 未明確指定繁體中文
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/data/model/SttLanguage.kt` (第 14-19 行)
- **問題代碼**：
  ```kotlin
  data object Chinese : SttLanguage(
      code = "zh",
      prompt = "將中文語音直接轉寫，不要翻譯成英文或其他語言。",
      emoji = "🇹🇼",
  )
  ```
- **影響分析**：
  Whisper 模型的語言代碼只有 `"zh"`，無法單靠 `code` 區分繁簡體。如果 `prompt` 內沒有明確出現繁體中文標記詞，Whisper 在很多場合會預設輸出簡體中文。
- **改進建議**：
  Prompt 應調整為：
  `"繁體中文轉錄，台灣用語。將語音直接轉寫為繁體中文，保留英文術語，不要翻譯。"`

---

### 2. 中文音訊分塊拼接多出半形空格
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/domain/usecase/TranscribeAudioUseCase.kt` (第 51 行)
  - `app/src/main/java/com/voxpen/app/domain/usecase/RetryTranscriptionUseCase.kt` (第 71 行)
- **問題代碼**：
  ```kotlin
  val joined = textChunks.filter { it.isNotBlank() }.joinToString(" ")
  ```
- **影響分析**：
  英文句子之間需要空格隔開，但中文詞句之間不需要空格。將中文多個 chunk 用 `" "` 拼接，會產生類似「今天 天氣很好 我們 去散步」的不自然空格。
- **改進建議**：
  依據 `language` 決定連接符：西文使用 `" "`，中文/日文若前後無西文字符則使用 `""` 連接。

---

### 3. 語音指令識別容錯率過低
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ime/VoiceCommandRecognizer.kt` (第 50 行)
- **問題代碼**：
  ```kotlin
  fun recognize(text: String): VoiceCommand? = COMMANDS[text.trim().lowercase()]
  ```
- **影響分析**：
  Whisper 輸出往往自動帶有標點符號（如「換行。」、「刪除！」），甚至可能偶發輸出簡體字（如「换行」）。因為代碼採用嚴格的 `equals` 比對，一旦帶有標點符號，語音指令識別便會徹底失效。
- **改進建議**：
  在比對前先剝除字尾的全形/半形標點符號（`replace(Regex("[.。!！?？\\s]"), "")`），並將繁簡統一轉換後再做 map lookup。

---

### 4. SRT 字幕導出對中文無效
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/util/ExportHelper.kt` (第 77 行)
- **問題代碼**：
  ```kotlin
  text.split(Regex("(?<=[.!?。！？])[\\s]+"))
  ```
- **影響分析**：
  正規表示式要求標點符號後面必須緊跟著空白字元 `[\s]+`。然而中文排版中，全形標點（如 `。`、`！`）後方通常完全沒有空格。這導致中文逐字稿完全無法被分割成句子，整篇內容被包在同一個字幕塊內。
- **改進建議**：
  將正則改為 `Regex("(?<=[.!?。！？])\\s*")`，使其在沒有空格時也能正常切句。

---

### 5. 首頁狀態無法感知生命週期重新整理
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ui/HomeScreen.kt` (第 89-98 行)
- **問題代碼**：
  ```kotlin
  @Composable
  private fun rememberKeyboardEnabled(): Boolean {
      val context = LocalContext.current
      return remember { ... } // ⚠️ 沒有 key，永遠不會重新計算
  }
  ```
- **影響分析**：
  使用者點擊「開啟鍵盤設定」前往系統設定啟用輸入法後，按返回鍵回到 `HomeScreen`。因為 `remember` 沒有綁定生命週期或 Key，它仍然回傳舊的 `false`，畫面上依然顯示「鍵盤未啟用」，造成使用者困惑。
- **改進建議**：
  使用 `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)`，在 Activity 回到前台時主動重新查詢輸入法與麥克風權限狀態。

---

## 六、代碼規範與可維護性 (Code Quality & Maintainability)

### 1. 極端壓縮單行程式碼（嚴重代碼異味）
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/ime/VoxPenIME.kt` (第 168-223 行)
- **問題實例**：
  ```kotlin
  // 第 204 行：高達 600+ 字元壓縮在一行！
  private fun showTonePopup(anchor:View){serviceScope.launch{val ct=preferencesManager.toneStyleFlow.first();val dp=resources.displayMetrics.density;val c=createQuickSettingsContainer(dp);val p=PopupWindow(c,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,true);listOf(ToneStyle.Casual to getString(R.string.tone_popup_casual),ToneStyle.Professional to getString(R.string.tone_popup_professional),ToneStyle.Email to getString(R.string.tone_popup_email),ToneStyle.Note to getString(R.string.tone_popup_note),ToneStyle.Social to getString(R.string.tone_popup_social),ToneStyle.Custom to getString(R.string.tone_popup_custom)).forEach{(t,l)->c.addView(TextView(this@VoxPenIME).apply{text=l;textSize=14f;setTextColor(if(t==ct)resources.getColor(R.color.mic_idle,null)else resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{effectiveTone=t;updateToneButton();serviceScope.launch{preferencesManager.setToneStyle(t)};p.dismiss()}})};p.showAtLocation(anchor,Gravity.BOTTOM or Gravity.END,(8*dp).toInt(),(64*dp).toInt())}}
  ```
- **影響分析**：
  專案在 `config/detekt/detekt.yml` 中明明規範了 `MaxLineLength: 120`。但 `VoxPenIME.kt` 後半段將大量的事件處理、Popup 建立、動畫、語氣設定強行壓縮為單一行，極難閱讀與維護，也無法進行有效的 Git diff 審查與除錯。
- **改進建議**：
  依照 Kotlin 官方排版規範全面格式化，將 Popup 建立等邏輯抽離為獨立的 Helper 或 View 類別，降低單一類別的行數與複雜度。

---

### 2. 硬編碼簡體字串與中英混雜
- **問題位置**：
  - `app/src/main/java/com/voxpen/app/data/repository/SttRepository.kt` (第 215-217 行)
- **問題代碼**：
  ```kotlin
  return if (networkBlocked) {
      "${provider.displayName} STT 当前网络被拒绝（403）。请切换 VPN 节点或关闭 VPN 后重试。"
  } else {
      "${provider.displayName} STT 请求被拒绝（403）：${detail ?: fallback ?: "Forbidden"}"
  }
  ```
- **影響分析**：
  在代碼中硬編碼簡體中文錯誤訊息，未抽取至 `res/values/strings.xml` 與 `res/values-zh-rTW/strings.xml`，違反專案 guidelines，且在繁體中文語系下出現大陸簡體提示。
- **改進建議**：
  將所有錯誤提示訊息抽取至 string resources，並區分英文與繁體中文翻譯。

---

## 七、改進優先級與行動建議 (Actionable Roadmap)

建議按照以下優先級進行逐步優化與修復：

| 優先級 | 類別 | 建議修改項目 | 涉及關鍵檔案 |
| :--- | :--- | :--- | :--- |
| **P0 (緊急)** | 功能修復 | 修正繁簡轉換方向（`TW2SP` 改為 `S2TWP`），確保台灣繁體中文輸出正確性 | `ChineseTextNormalizer.kt`, `VoxPenIME.kt` |
| **P0 (緊急)** | 營運與付費 | 加入 Google Play 內購確認（`acknowledgePurchase`），防止付費用戶 3 天自動退款 | `BillingManager.kt` |
| **P0 (緊急)** | 穩定性 | 移除 `DownloadLogTree.isLoggable` 中的 `runBlocking`，消除 UI 線程凍結與 ANR 隱患 | `DownloadLogTree.kt` |
| **P0 (緊急)** | 功能修復 | 修正 `LlmProvider.Groq` 的預設模型為 `llama-3.3-70b-versatile`，修復潤稿 404 | `LlmProvider.kt` |
| **P1 (高)** | 核心體驗 | 恢復候選列「原文 vs 潤稿」雙行顯示與點擊上屏機制，落實 Typeless 核心特色 | `VoxPenIME.kt`, `keyboard_view.xml` |
| **P1 (高)** | 功能修復 | 修復大音檔轉錄分塊邏輯與正確傳遞 MIME/副檔名，支援 MP3/M4A 格式 | `TranscribeFileUseCase.kt`, `SttRepository.kt` |
| **P1 (高)** | 架構改善 | 將音檔轉錄邏輯從 `TranscriptionScreenContent` 移回 `TranscriptionViewModel` | `TranscriptionScreen.kt`, `TranscriptionViewModel.kt` |
| **P1 (高)** | 記憶體防護 | 修復 `VoxPenIME.onCreateInputView` 協程洩漏；停止錄音改為非同步執行 | `VoxPenIME.kt`, `AudioRecorder.kt` |
| **P1 (高)** | 業務邏輯 | `DictionaryViewModel` 改用 `ProStatusResolver`，統一 License Key 與內購權限 | `DictionaryViewModel.kt` |
| **P2 (中)** | 語音品質 | 中文分塊拼接去除空格；語音指令去除標點符號容錯；修復 SRT 中文斷句正則 | `TranscribeAudioUseCase.kt`, `VoiceCommandRecognizer.kt`, `ExportHelper.kt` |
| **P2 (中)** | 體驗改善 | 修復 `MainActivity` Onboarding 初始狀態競爭；首頁鍵盤啟用狀態隨生命週期重整 | `MainActivity.kt`, `HomeScreen.kt` |
| **P2 (中)** | 安全性 | 在 `NetworkModule` 的 `HttpLoggingInterceptor` 中遮蔽 `Authorization` 標頭 | `NetworkModule.kt` |
| **P3 (低)** | 程式碼規範 | 格式化 `VoxPenIME.kt` 壓縮單行代碼；抽取硬編碼字串至 `strings.xml` | `VoxPenIME.kt`, `SttRepository.kt` |

---
*報告產生完成。本報告僅供專案維護者參考，未更動專案內任何原始程式碼。*

---

## Codex 重新審查與實作結果（2026-09-09）

### 總結判斷

Antigravity 的報告有一部分指出了真實的工程問題，尤其是大音檔處理、主執行緒阻塞、IME lifecycle、記錄檔寫入與輸出切句；這些已修正。另一方面，報告把本 fork 的產品決策誤判成 bug，因此不能照單全收。

### 依產品決策保留或改寫的部分

1. **Google Play Billing**：本 fork 已自行修改原始 source code，不依賴 Android 購買權限。Billing、license 與 Lemon Squeezy source code 保留作為 legacy reference，但移除 billing UI，Application 不再初始化 billing client，也不再自動驗證 license。報告中的 `acknowledgePurchase` 建議不屬於目前產品範圍。
2. **Usage limit**：原本畫面上的 30／10／2 已是過期的方案文案。首頁與音檔轉錄頁現在明確顯示 unlimited；舊的 `UsageLimiter` 保留相容性，但三項 sentinel limit 都是 9999，不再需要付費方案。
3. **簡體／繁體**：`TW2SP` 是刻意設計。簡體化只發生在 `VoxPenIME` 最終顯示／commit 的邊界；檔案轉錄、SRT 匯出、檔案翻譯、LLM 回應及既有 prompt 均沒有做全域簡體化，也沒有改動原始辨識／LLM pipeline 的文字內容。
4. **候選列雙行與 refined auto-commit**：這是目前 UX 決策，不是本次要恢復的功能。Groq 的 GPT-OSS model ID 也仍保留，沒有依報告錯誤地改成另一個模型。

### 字典 10 個詞的評估

10 個詞的使用者上限合理，原因與原作者相同：字典內容會進入辨識與潤稿 prompt。檢查後發現 Whisper prompt 原本已有約 200 token 的限制，但 LLM dictionary suffix 沒有獨立上限；因此保留 UI 的 10 個詞限制，並另外加入估算 512 token 的 LLM suffix 上限，避免日後資料量變大時 prompt 無界增長。

### 已完成的程式修正

- 音檔轉錄改由 ViewModel/use case 處理，ContentResolver 以 stream 增量讀取；保留原始檔名與 MIME type。
- WAV 只在 sample boundary 切割並為每個 chunk 建立合法 WAV header；大型 MP3/M4A 等 opaque codec 不再被任意 byte slicing 損壞，而是安全拒絕超過上限的檔案。
- 中文 chunk 合併不插入空格；SRT fallback sentence split 不再要求標點後一定有 whitespace；語音指令可容忍句末標點。
- 錄音停止先解除 `AudioRecord.read` 再 join，並由 IO dispatcher 執行；IME 的 UI collectors 也改用可取消的 input-view scope。
- onboarding 初始狀態改為等待資料載入，首頁鍵盤啟用狀態會在回到前景時刷新。
- Download log 改為非阻塞 IO 寫入，Authorization header 在 OkHttp debug log 中遮蔽；Application 啟動時清理孤立錄音檔。
- 新增／更新 AudioChunker、中文合併、SRT、voice command、字典 token budget 與 ViewModel 相關測試。

### 刻意沒有修改的部分

`ChineseTextNormalizer` 的 IME-only `TW2SP`、檔案／SRT／翻譯／LLM 的現有輸出，以及 billing source code 都依照產品決策保留。Billing UI 已移除，但 legacy billing classes 與相容性方法沒有刪除。

### 驗證結果

- 本機 `./gradlew testDebugUnitTest` 無法執行：此 Mac 環境沒有 Java runtime；不是測試失敗。
- 第一次 GitHub build 發現我新增的 callback 名稱錯誤（`onDestroyInputView`），已依編譯 log 改成 Android 提供的 `onFinishInputView(finishingInput)`。
- 第二次 GitHub Actions [Build APK run 34373556036](https://github.com/tomasEW/voxpen-android/actions/runs/34373556036) 成功完成 `assembleDebug`、APK rename 與 artifact upload；對應程式 commit 為 `35ba8b6`，前一個主要修正 commit 為 `152a398`。

目前結論是：報告適合作為工程風險清單，但付費、簡繁輸出方向、候選列與模型部分必須以本 fork 的上述設計為準；本次已修正真正會影響穩定性、檔案正確性與可維護性的項目。
