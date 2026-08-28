package com.voxpen.app.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import com.voxpen.app.R
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.model.RecordingMode
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.ToneStyle
import com.voxpen.app.data.model.VoiceCommand
import com.voxpen.app.domain.usecase.EditTextUseCase
import com.voxpen.app.ui.MainActivity
import com.voxpen.app.util.ChineseTextNormalizer
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@Suppress("TooManyFunctions")
class VoxPenIME : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var actionHandler: KeyboardActionHandler
    private lateinit var recordingController: RecordingController
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var proStatusResolver: com.voxpen.app.billing.ProStatusResolver
    private lateinit var editTextUseCase: EditTextUseCase
    private lateinit var apiKeyManager: com.voxpen.app.data.local.ApiKeyManager

    private var isEditMode: Boolean = false
    @Volatile private var effectiveTone: ToneStyle = ToneStyle.DEFAULT
    @Volatile private var autoToneEnabled: Boolean = PreferencesManager.DEFAULT_AUTO_TONE_ENABLED
    @Volatile private var customAppToneRules: Map<String, ToneStyle> = emptyMap()

    private var candidateBar: LinearLayout? = null
    private var candidateStatusRow: LinearLayout? = null
    private var candidateText: TextView? = null
    private var candidateProgress: ProgressBar? = null
    private var candidateOriginal: TextView? = null
    private var candidateRefinedRow: LinearLayout? = null
    private var candidateRefined: TextView? = null
    private var refineProgress: ProgressBar? = null
    private var copyStatusButton: ImageButton? = null
    private var copyRefinedButton: ImageButton? = null
    private var micButton: ImageButton? = null
    private var toneButton: TextView? = null
    private var translationIndicatorRow: LinearLayout? = null
    private var translationLabel: TextView? = null
    private var translationCloseButton: ImageButton? = null

    @Volatile private var translationEnabled: Boolean = PreferencesManager.DEFAULT_TRANSLATION_ENABLED
    @Volatile private var translationTargetLanguage: SttLanguage = PreferencesManager.DEFAULT_TRANSLATION_TARGET_LANGUAGE
    @Volatile private var currentSttLanguage: SttLanguage = SttLanguage.Auto

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var micPulseAnimator: android.animation.AnimatorSet? = null
    private var previousUiState: ImeUiState = ImeUiState.Idle
    private var recordingStartTime: Long = 0
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
            if (elapsed >= MAX_RECORDING_SECONDS) { stopRecording(); return }
            val remaining = MAX_RECORDING_SECONDS - elapsed
            val minutes = elapsed / 60
            val seconds = elapsed % 60
            candidateText?.text = if (remaining <= 30) "⚠️ ${getString(R.string.recording)} – 0:%02d".format(remaining)
            else getString(R.string.recording) + " $minutes:%02d".format(seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateInputView(): View {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, VoxPenIMEEntryPoint::class.java)
        audioRecorder = AudioRecorder(this)
        audioManager = getSystemService(AudioManager::class.java)
        preferencesManager = entryPoint.preferencesManager()
        proStatusResolver = entryPoint.proStatusResolver()
        editTextUseCase = entryPoint.editTextUseCase()
        apiKeyManager = entryPoint.apiKeyManager()
        recordingController = RecordingController(
            transcribeUseCase = entryPoint.transcribeAudioUseCase(), refineTextUseCase = entryPoint.refineTextUseCase(),
            apiKeyManager = entryPoint.apiKeyManager(), preferencesManager = preferencesManager,
            dictionaryRepository = entryPoint.dictionaryRepository(), transcriptionRepository = entryPoint.transcriptionRepository(),
            recordingStore = entryPoint.recordingStore(), usageLimiter = entryPoint.usageLimiter(),
            proStatusProvider = { proStatusResolver.proStatus.value }, ioDispatcher = Dispatchers.IO,
            messages = object : RecordingMessages {
                override fun apiKeyNotConfigured(): String = getString(R.string.provider_key_required)
                override fun recordingTooShort(): String = getString(R.string.recording_error_too_short)
                override fun recordingTooQuiet(): String = getString(R.string.recording_error_too_quiet)
                override fun transcriptionFailed(message: String?): String = message ?: getString(R.string.transcription_failed)
            },
        )
        actionHandler = KeyboardActionHandler(
            onSendKeyEvent = { keyCode -> sendDownUpKeyEvents(keyCode) },
            onSwitchKeyboard = { switchKeyboardOrShowPicker() },
            onOpenSettings = { launchSettings() }, onMicTap = { handleMicTap() },
        )
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        bindViews(view); bindButtons(view); observeUiState()
        serviceScope.launch { if (!preferencesManager.keyboardTooltipsShownFlow.first()) { showKeyboardTooltips(view); preferencesManager.setKeyboardTooltipsShown(true) } }
        serviceScope.launch { preferencesManager.toneStyleFlow.collect { effectiveTone = it; updateToneButton() } }
        serviceScope.launch { preferencesManager.autoToneEnabledFlow.collect { autoToneEnabled = it } }
        serviceScope.launch { preferencesManager.customAppToneRulesFlow.collect { customAppToneRules = it } }
        serviceScope.launch { preferencesManager.translationEnabledFlow.collect { translationEnabled = it; updateTranslationIndicator() } }
        serviceScope.launch { preferencesManager.translationTargetLanguageFlow.collect { translationTargetLanguage = it; updateTranslationIndicator() } }
        serviceScope.launch { preferencesManager.languageFlow.collect { currentSttLanguage = it; updateTranslationIndicator() } }
        return view
    }

    private fun bindViews(view: View) {
        candidateBar = view.findViewById(R.id.candidate_bar); candidateStatusRow = view.findViewById(R.id.candidate_status_row)
        candidateText = view.findViewById(R.id.candidate_text); candidateProgress = view.findViewById(R.id.candidate_progress)
        candidateOriginal = view.findViewById(R.id.candidate_original); candidateRefinedRow = view.findViewById(R.id.candidate_refined_row)
        candidateRefined = view.findViewById(R.id.candidate_refined); refineProgress = view.findViewById(R.id.refine_progress)
        micButton = view.findViewById(R.id.btn_mic); toneButton = view.findViewById(R.id.btn_tone)
        copyStatusButton = view.findViewById(R.id.btn_copy_status); copyRefinedButton = view.findViewById(R.id.btn_copy_refined)
        translationIndicatorRow = view.findViewById(R.id.translation_indicator_row); translationLabel = view.findViewById(R.id.translation_label)
        translationCloseButton = view.findViewById(R.id.btn_translation_close)
    }

    private fun bindButtons(view: View) {
        view.findViewById<ImageButton>(R.id.btn_backspace)?.setOnClickListener { actionHandler.handle(KeyboardAction.Backspace) }
        view.findViewById<ImageButton>(R.id.btn_enter)?.setOnClickListener { actionHandler.handle(KeyboardAction.Enter) }
        view.findViewById<ImageButton>(R.id.btn_switch)?.let { b ->
            b.setOnClickListener { actionHandler.handle(KeyboardAction.SwitchKeyboard) }
            b.setOnLongClickListener { showInputMethodPicker(); true }
        }
        view.findViewById<ImageButton>(R.id.btn_settings)?.let { b -> b.setOnClickListener { actionHandler.handle(KeyboardAction.OpenSettings) }; b.setOnLongClickListener { showQuickSettings(it); true } }
        setupMicButton(view.findViewById(R.id.btn_mic)); view.findViewById<TextView>(R.id.btn_tone)?.setOnClickListener { showTonePopup(it) }
        view.findViewById<TextView>(R.id.translation_label)?.setOnClickListener { cycleTranslationTarget() }
        view.findViewById<ImageButton>(R.id.btn_translation_close)?.setOnClickListener { serviceScope.launch { preferencesManager.setTranslationEnabled(false) } }
    }

    private fun switchKeyboardOrShowPicker(): Boolean {
        val switched = switchToPreviousInputMethod()
        if (!switched) showInputMethodPicker()
        return switched
    }

    private fun showInputMethodPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupMicButton(micBtn: ImageButton?) { micBtn ?: return; serviceScope.launch { when (preferencesManager.recordingModeFlow.first()) { RecordingMode.TAP_TO_TOGGLE -> micBtn.setOnClickListener { handleMicTap() }; RecordingMode.HOLD_TO_RECORD -> micBtn.setOnTouchListener { _, e -> when (e.action) { MotionEvent.ACTION_DOWN -> { startRecording(); true }; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopRecording(); true }; else -> false } } } } }

    private fun handleMicTap() { when (recordingController.uiState.value) { ImeUiState.Idle, is ImeUiState.Error, is ImeUiState.Result, is ImeUiState.Refined, is ImeUiState.CommandDetected, is ImeUiState.EditResult -> startRecording(); ImeUiState.Recording -> stopRecording(); ImeUiState.Processing, is ImeUiState.Refining, ImeUiState.Editing, is ImeUiState.EditInstruction -> {} } }
    private fun startRecording() { if (!audioRecorder.hasPermission()) { candidateBar?.visibility = View.VISIBLE; candidateText?.text = getString(R.string.mic_permission_required); candidateProgress?.visibility = View.GONE; return }; requestAudioDucking(); recordingController.onStartRecording { audioRecorder.startRecording() } }
    private fun stopRecording() { abandonAudioDucking(); serviceScope.launch { recordingController.onStopRecording({ audioRecorder.stopRecording() }, preferencesManager.languageFlow.first(), isEditMode, effectiveTone) } }
    private fun requestAudioDucking() { val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build(); audioFocusRequest = r; audioManager?.requestAudioFocus(r) }
    private fun abandonAudioDucking() { audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }; audioFocusRequest = null }
    private fun observeUiState() { serviceScope.launch { recordingController.uiState.collect { updateUi(it) } } }
    private fun startMicPulse(micBtn: ImageButton) { val x=android.animation.ObjectAnimator.ofFloat(micBtn,"scaleX",1f,1.15f,1f); val y=android.animation.ObjectAnimator.ofFloat(micBtn,"scaleY",1f,1.15f,1f); val a=android.animation.ObjectAnimator.ofFloat(micBtn,"alpha",1f,.7f,1f); micPulseAnimator=android.animation.AnimatorSet().apply { playTogether(x,y,a); duration=800; interpolator=android.view.animation.AccelerateDecelerateInterpolator(); addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){if(recordingController.uiState.value==ImeUiState.Recording)start()}}); start() } }
    private fun stopMicPulse(){micPulseAnimator?.cancel();micPulseAnimator=null;micButton?.apply{scaleX=1f;scaleY=1f;alpha=1f}}
    private fun performHaptic(type:Int){micButton?.performHapticFeedback(type)}
    private fun playTone(type:Int){try{val t=android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION,30);t.startTone(type,100);android.os.Handler(mainLooper).postDelayed({t.release()},200)}catch(_:Exception){}}
    private fun updateUi(state:ImeUiState){if(state==previousUiState)return;previousUiState=state;triggerStateFeedback(state);resetClickListeners();updateCandidateBar(state);updateMicAppearance(state)}
    private fun triggerStateFeedback(state:ImeUiState){when(state){ImeUiState.Recording->{performHaptic(HapticFeedbackConstants.LONG_PRESS);playTone(android.media.ToneGenerator.TONE_PROP_BEEP)};ImeUiState.Processing->{performHaptic(HapticFeedbackConstants.KEYBOARD_TAP);playTone(android.media.ToneGenerator.TONE_PROP_ACK)};is ImeUiState.Result,is ImeUiState.Refined->{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R)performHaptic(HapticFeedbackConstants.CONFIRM)else performHaptic(HapticFeedbackConstants.KEYBOARD_TAP)};else->{}}}
    private fun resetClickListeners(){candidateBar?.setOnClickListener(null);candidateOriginal?.setOnClickListener(null);candidateRefinedRow?.setOnClickListener(null);copyStatusButton?.setOnClickListener(null);copyStatusButton?.visibility=View.GONE;copyRefinedButton?.setOnClickListener(null);copyRefinedButton?.visibility=View.GONE}

    private fun updateCandidateBar(state:ImeUiState){when(state){
        ImeUiState.Idle->{timerHandler.removeCallbacks(timerRunnable);if(translationEnabled){candidateBar?.visibility=View.VISIBLE;candidateStatusRow?.visibility=View.GONE;candidateOriginal?.visibility=View.GONE;candidateRefinedRow?.visibility=View.GONE}else if(!isEditMode)candidateBar?.visibility=View.GONE}
        ImeUiState.Recording->{showStatusRow(getString(R.string.recording),false);recordingStartTime=System.currentTimeMillis();timerHandler.post(timerRunnable)}
        ImeUiState.Processing->{timerHandler.removeCallbacks(timerRunnable);showStatusRow(getString(R.string.processing),true)}
        is ImeUiState.Result->{timerHandler.removeCallbacks(timerRunnable);val t=normalizeOutputText(state.text);showStatusRow(t,false);copyStatusButton?.visibility=View.VISIBLE;copyStatusButton?.setOnClickListener{copyToClipboard(t)}}
        is ImeUiState.Refining->{timerHandler.removeCallbacks(timerRunnable);val t=normalizeOutputText(state.original);showStatusRow(t,true);copyStatusButton?.visibility=View.VISIBLE;copyStatusButton?.setOnClickListener{copyToClipboard(t)}}
        is ImeUiState.Refined->{timerHandler.removeCallbacks(timerRunnable);val o=normalizeOutputText(state.original);val r=normalizeOutputText(state.refined);showStatusRow(o,false);copyStatusButton?.visibility=View.VISIBLE;copyStatusButton?.setOnClickListener{copyToClipboard(o)};currentInputConnection?.commitText(r,1)}
        is ImeUiState.Error->{timerHandler.removeCallbacks(timerRunnable);showStatusRow(state.message,false);candidateBar?.setOnClickListener{recordingController.dismiss()}}
        is ImeUiState.CommandDetected->{timerHandler.removeCallbacks(timerRunnable);executeVoiceCommand(state.command);recordingController.dismiss()}
        is ImeUiState.EditInstruction->{timerHandler.removeCallbacks(timerRunnable);showStatusRow(getString(R.string.editing_text),true);performEditWithLlm(state.instruction)}
        ImeUiState.Editing->{}
        is ImeUiState.EditResult->{timerHandler.removeCallbacks(timerRunnable);currentInputConnection?.commitText(normalizeOutputText(state.revised),1);isEditMode=false;recordingController.dismiss()}
    }}

    private fun normalizeOutputText(text:String):String{val c=if(translationEnabled)translationTargetLanguage==SttLanguage.Chinese else currentSttLanguage==SttLanguage.Auto||currentSttLanguage==SttLanguage.Chinese;return if(c)ChineseTextNormalizer.toMainlandSimplified(text,applicationContext)else text}
    private fun updateMicAppearance(state:ImeUiState){if(state==ImeUiState.Recording){micButton?.setBackgroundColor(getColor(R.color.mic_active));micButton?.let{startMicPulse(it)}}else{stopMicPulse();micButton?.setBackgroundColor(getColor(R.color.mic_idle))}}
    private fun showStatusRow(text:String,showProgress:Boolean){candidateBar?.visibility=View.VISIBLE;candidateStatusRow?.visibility=View.VISIBLE;candidateProgress?.visibility=if(showProgress)View.VISIBLE else View.GONE;candidateText?.text=text;candidateOriginal?.visibility=View.GONE;candidateRefinedRow?.visibility=View.GONE}
    private fun showDualRows(original:String,refined:String?){candidateBar?.visibility=View.VISIBLE;candidateStatusRow?.visibility=View.GONE;candidateOriginal?.visibility=View.VISIBLE;candidateOriginal?.text=original;candidateRefinedRow?.visibility=View.VISIBLE;if(refined!=null){refineProgress?.visibility=View.GONE;candidateRefined?.text=refined}else{refineProgress?.visibility=View.VISIBLE;candidateRefined?.text=getString(R.string.refining)}}

    private fun showQuickSettings(anchor:View){serviceScope.launch{val ro=preferencesManager.refinementEnabledFlow.first();val to=preferencesManager.translationEnabledFlow.first();val cl=preferencesManager.languageFlow.first();val dp=resources.displayMetrics.density;val c=createQuickSettingsContainer(dp);val p=PopupWindow(c,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,true);addLanguageSelector(c,p,cl,dp);addRefinementToggle(c,p,ro,dp);addTranslationToggle(c,p,to,dp);addEditModeToggle(c,p,dp);p.showAtLocation(anchor,Gravity.BOTTOM or Gravity.END,(8*dp).toInt(),(64*dp).toInt())}}
    private fun showTonePopup(anchor:View){serviceScope.launch{val ct=preferencesManager.toneStyleFlow.first();val dp=resources.displayMetrics.density;val c=createQuickSettingsContainer(dp);val p=PopupWindow(c,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,true);listOf(ToneStyle.Casual to getString(R.string.tone_popup_casual),ToneStyle.Professional to getString(R.string.tone_popup_professional),ToneStyle.Email to getString(R.string.tone_popup_email),ToneStyle.Note to getString(R.string.tone_popup_note),ToneStyle.Social to getString(R.string.tone_popup_social),ToneStyle.Custom to getString(R.string.tone_popup_custom)).forEach{(t,l)->c.addView(TextView(this@VoxPenIME).apply{text=l;textSize=14f;setTextColor(if(t==ct)resources.getColor(R.color.mic_idle,null)else resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{effectiveTone=t;updateToneButton();serviceScope.launch{preferencesManager.setToneStyle(t)};p.dismiss()}})};p.showAtLocation(anchor,Gravity.BOTTOM or Gravity.END,(8*dp).toInt(),(64*dp).toInt())}}
    private fun addLanguageSelector(c:LinearLayout,p:PopupWindow,cl:SttLanguage,dp:Float){listOf(SttLanguage.Auto to "${SttLanguage.Auto.emoji} ${getString(R.string.lang_auto)}",SttLanguage.Chinese to "${SttLanguage.Chinese.emoji} ${getString(R.string.lang_zh)}",SttLanguage.English to "${SttLanguage.English.emoji} ${getString(R.string.lang_en)}",SttLanguage.Japanese to "${SttLanguage.Japanese.emoji} ${getString(R.string.lang_ja)}").forEach{(l,s)->c.addView(TextView(this).apply{text=s;textSize=14f;setTextColor(if(l==cl)resources.getColor(R.color.mic_idle,null)else resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{serviceScope.launch{preferencesManager.setLanguage(l)};p.dismiss()}})};c.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(1*dp).toInt()).apply{topMargin=(4*dp).toInt();bottomMargin=(4*dp).toInt()};setBackgroundColor(0x33FFFFFF)})}
    private fun executeVoiceCommand(c:VoiceCommand){when(c){VoiceCommand.Enter->sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER);VoiceCommand.Backspace->sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL);VoiceCommand.Newline->currentInputConnection?.commitText("\n",1);VoiceCommand.Space->currentInputConnection?.commitText(" ",1);VoiceCommand.Undo->currentInputConnection?.performContextMenuAction(android.R.id.undo);VoiceCommand.SelectAll->currentInputConnection?.performContextMenuAction(android.R.id.selectAll);VoiceCommand.Copy->currentInputConnection?.performContextMenuAction(android.R.id.copy);VoiceCommand.Paste->currentInputConnection?.performContextMenuAction(android.R.id.paste);VoiceCommand.Cut->currentInputConnection?.performContextMenuAction(android.R.id.cut);VoiceCommand.ClearAll->{currentInputConnection?.performContextMenuAction(android.R.id.selectAll);currentInputConnection?.commitText("",1)}}}
    private fun copyToClipboard(t:String){getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("VoxPen",t));android.widget.Toast.makeText(this,R.string.transcription_copied,android.widget.Toast.LENGTH_SHORT).show()}
    private fun performEditWithLlm(i:String){val s=currentInputConnection?.getSelectedText(0)?.toString();if(s.isNullOrBlank()){showStatusRow("⚠️ No text selected",false);candidateBar?.postDelayed({recordingController.dismiss()},2000);return};serviceScope.launch{val lp=preferencesManager.llmProviderFlow.first();val k=apiKeyManager.getApiKey(lp)?:apiKeyManager.getGroqApiKey();if(k.isNullOrBlank()&&lp!=com.voxpen.app.data.model.LlmProvider.Custom){showStatusRow("API key not configured",false);return@launch};val l=preferencesManager.languageFlow.first();val m=if(lp==com.voxpen.app.data.model.LlmProvider.Custom)preferencesManager.customLlmModelFlow.first().ifBlank{preferencesManager.llmModelFlow.first()}else preferencesManager.llmModelFlow.first();val b=if(lp==com.voxpen.app.data.model.LlmProvider.Custom)apiKeyManager.getCustomBaseUrl()else null;editTextUseCase(s,i,l,k.orEmpty(),m,lp,b).fold(onSuccess={r->currentInputConnection?.commitText(normalizeOutputText(r),1);isEditMode=false;recordingController.dismiss()},onFailure={e->showStatusRow("Edit failed: ${e.message}",false)})}}
    private fun addEditModeToggle(c:LinearLayout,p:PopupWindow,dp:Float){c.addView(TextView(this).apply{text=if(isEditMode)getString(R.string.quick_edit_mode_on)else getString(R.string.quick_edit_mode_off);textSize=14f;setTextColor(resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{isEditMode=!isEditMode;updateEditModeIndicator();p.dismiss()}})}
    private fun updateEditModeIndicator(){if(isEditMode){candidateBar?.visibility=View.VISIBLE;showStatusRow(getString(R.string.edit_mode_active),false)}else if(recordingController.uiState.value==ImeUiState.Idle)candidateBar?.visibility=View.GONE}
    private fun createQuickSettingsContainer(dp:Float)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(resources.getColor(R.color.key_background,null));val q=(12*dp).toInt();setPadding(q,q,q,q)}
    private fun addRefinementToggle(c:LinearLayout,p:PopupWindow,on:Boolean,dp:Float){c.addView(TextView(this).apply{text=if(on)getString(R.string.quick_refinement_on)else getString(R.string.quick_refinement_off);textSize=14f;setTextColor(resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{serviceScope.launch{preferencesManager.setRefinementEnabled(!on)};p.dismiss()}})}
    private fun addTranslationToggle(c:LinearLayout,p:PopupWindow,on:Boolean,dp:Float){c.addView(TextView(this).apply{text=if(on)getString(R.string.quick_translation_on)else getString(R.string.quick_translation_off);textSize=14f;setTextColor(resources.getColor(R.color.key_text,null));val q=(8*dp).toInt();setPadding(q,q,q,q);setOnClickListener{serviceScope.launch{preferencesManager.setTranslationEnabled(!on)};p.dismiss()}})}
    private fun showKeyboardTooltips(root:View){/* unchanged */}
    private fun getTranslationTargets():List<SttLanguage>{val a=listOf(SttLanguage.English,SttLanguage.Chinese,SttLanguage.Japanese);return if(currentSttLanguage==SttLanguage.Auto)a else a.filter{it!=currentSttLanguage}}
    private fun cycleTranslationTarget(){val t=getTranslationTargets();if(!translationEnabled){serviceScope.launch{preferencesManager.setTranslationTargetLanguage(t.first());preferencesManager.setTranslationEnabled(true)};return};val i=t.indexOf(translationTargetLanguage).let{if(it==-1)t.size-1 else it}+1;if(i>=t.size)serviceScope.launch{preferencesManager.setTranslationEnabled(false)}else serviceScope.launch{preferencesManager.setTranslationTargetLanguage(t[i])}}
    private fun updateTranslationIndicator(){if(!translationEnabled){translationIndicatorRow?.visibility=View.GONE;return};translationIndicatorRow?.visibility=View.VISIBLE;candidateBar?.visibility=View.VISIBLE;val n=when(translationTargetLanguage){SttLanguage.English->getString(R.string.lang_en);SttLanguage.Chinese->getString(R.string.lang_zh);SttLanguage.Japanese->getString(R.string.lang_ja);else->translationTargetLanguage.code?:"?"};val f=when(currentSttLanguage){SttLanguage.Chinese->R.string.translation_indicator_speak_zh;SttLanguage.English->R.string.translation_indicator_speak_en;SttLanguage.Japanese->R.string.translation_indicator_speak_ja;else->R.string.translation_indicator_speak_auto};translationLabel?.text=getString(f,n)}
    companion object{private const val MAX_RECORDING_SECONDS=360L}
    private fun launchSettings(){startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun updateToneButton(){toneButton?.text=effectiveTone.emoji}
    override fun onStartInput(info:EditorInfo,restarting:Boolean){super.onStartInput(info,restarting);if(autoToneEnabled){AppToneDetector.detect(info.packageName?:"",info.inputType,customAppToneRules)?.let{effectiveTone=it}};updateToneButton()}
    override fun onDestroy(){stopMicPulse();timerHandler.removeCallbacks(timerRunnable);abandonAudioDucking();audioRecorder.release();recordingController.destroy();serviceScope.cancel();super.onDestroy()}
}
