package bot.tafs.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import kotlinx.coroutines.launch

/**
 * The watch app (docs/watch-wear.md): the microphone first ("Ask tafs"), then Needs you and Your tafs, read from the phone each
 * time the watch shows them; it opens on what it keeps, and comes back as it was when put away. Speaking is turned into text on the watch, by the system's speech
 * recognizer, and sent when the person stops, with nothing to confirm: only the text goes to the phone.
 */
class WatchActivity : ComponentActivity() {
  private val model by lazy {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    // A screen lock set and the watch unlocked now: the confirmation for an ask, a Refuse and a Halt (docs/watch-wear.md).
    WatchModel(
      DataLayerPhone(this), unlocked = { keyguard.isDeviceSecure && !keyguard.isDeviceLocked }, secure = { keyguard.isDeviceSecure }, tile = TileCount(PreferencesTileStore(this)),
      // Only v2 keeps a message for later, an Inbox, and signs out on the watch alone.
      drafts = if (WATCH_V2) PreferencesDraftStore(this) else null,
      inboxStore = if (WATCH_V2) KeptInboxStore.of(this) else null,
      signIn = if (WATCH_V2) PreferencesWatchSignIn(this) else null,
      cache = if (WATCH_V2) WatchCache(KeystoreCacheStore(this), System::currentTimeMillis) else null,
      // Coach's conversation, kept across opens (docs/watch-wear.md, "What the watch keeps").
      // Behind its switch until the owner's yes and the Privacy page's line ([KEEP_THREADS]).
      thread = if (WATCH_V2 && KEEP_THREADS) KeptThreadStore.of(this) else null,
      // The phone's account mark: what's kept is drawn only once a reply this open says it's the same sign-in (AccountMark).
      marks = PreferencesMarkStore(this),
      // An account wiped: its answers' notifications and any recording go with it.
      wiped = {
        runCatching { getSystemService(android.app.NotificationManager::class.java)?.cancelAll() }
        runCatching { VoiceFiles.dir(this).deleteRecursively() }
      },
    )
  }

  private val listener by lazy { Listener(this, model, lifecycleScope, keepAudio = WATCH_V2 && BuildConfig.KEEP_AUDIO, phoneFallback = WATCH_V2) }
  private val playback by lazy { FilePlayback() }
  private var speaker: Speaker? = null

  /** The ask a Reply continues, kept while the microphone permission is asked. */
  private var pendingReply: String? = null
  private var pendingListen = false
  /** A one-tap start waiting on the permission: its screen shows Ready behind the dialog, and listening starts once allowed. */
  private var pendingTalk = false

  /** The microphone, and on Wear OS 4 and later the result's notification, asked once, at the first ask. */
  private val microphone = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    val mic = granted[android.Manifest.permission.RECORD_AUDIO] == true
    // A one-tap start begins listening, and its 30 s, only now: never behind the system's dialog.
    if (mic && pendingListen) { if (!pendingTalk || model.listen(pendingReply)) startListening(pendingReply) }
    else if (!mic) model.notListening(Words.Watch.micNeeded)
    pendingListen = false
    pendingTalk = false
  }

  /** v2's Type: the watch's keyboard; what's typed goes to the review. */
  private val typed = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
    val text = result.data?.let { android.app.RemoteInput.getResultsFromIntent(it) }?.getCharSequence(TYPED)?.toString()
    if (text != null) model.review(text, tafId = typingFor)
  }

  /** The taf the keyboard's words go to: set at each Type, Coach's to null. */
  private var typingFor: String? = null

  private fun type() {
    // The system's text input (what androidx.wear.input's RemoteInputIntentHelper builds): keyboard, handwriting or voice typing.
    val input = android.app.RemoteInput.Builder(TYPED).setLabel(Words.Watch.typeHint).build()
    val intent = Intent(ACTION_REMOTE_INPUT).putExtra(EXTRA_REMOTE_INPUTS, arrayOf(input))
    runCatching { typed.launch(intent) }
  }

  /**
   * The system's speech input, where the watch has no recognizer for apps: its text comes back here, through [WatchModel.heard], so
   * v1 sends it at once and v2 shows it to check first.
   */
  private val speechInput = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
    val heard = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    lifecycleScope.launch { if (heard != null) model.heard(heard) else model.heardNothing() }
  }

  private fun listen(replyTo: String? = null) {
    if (!model.listen(replyTo)) return
    withMicrophone(replyTo)
  }

  /** Listening, once the microphone is allowed: asked for at the first ask, then started. */
  private fun withMicrophone(replyTo: String?) {
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      pendingReply = replyTo
      pendingListen = true
      val asked = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
      if (android.os.Build.VERSION.SDK_INT >= 33) asked += android.Manifest.permission.POST_NOTIFICATIONS
      microphone.launch(asked.toTypedArray())
      return
    }
    startListening(replyTo)
  }

  private fun startListening(replyTo: String?) {
    if (!listener.start(replyTo)) {
      val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Words.Watch.ask)
      runCatching { speechInput.launch(intent) }.onFailure { model.heardNothing() }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      WatchTheme {
        val shown by model.shown.collectAsState()
        val outcome by model.outcome.collectAsState()
        val confirmingHaltAll by model.confirmingHaltAll.collectAsState()
        val voice by model.voice.collectAsState()
        val result by model.result.collectAsState()
        val asks by model.asks.collectAsState()
        val said by model.said.collectAsState()
        val kept by model.kept.collectAsState()
        val taf by model.taf.collectAsState()
        val haltPick by model.haltPick.collectAsState()
        val signedOutHere by model.signedOutHere.collectAsState()
        val calendar by model.calendar.collectAsState()
        val account by model.account.collectAsState()
        val tafChatOff by model.tafChatOff.collectAsState()
        val wholes by model.wholes.collectAsState()
        // Words being said, typed or checked keep the screen on, so the moment isn't lost to the screen going off (row 13); if it
        // goes off anyway, the words come back as a review (WatchModel.putAway).
        val inputUp = voice is WatchModel.Voice.Listening || voice is WatchModel.Voice.Review || voice is WatchModel.Voice.Transcribing ||
          voice is WatchModel.Voice.Sending || voice is WatchModel.Voice.Ready
        androidx.compose.runtime.DisposableEffect(inputUp) {
          if (inputUp) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        // v2: past 30 s, listening stops by itself and the words go to the review.
        val listeningSince = (voice as? WatchModel.Voice.Listening)?.since
        androidx.compose.runtime.LaunchedEffect(listeningSince) {
          if (WATCH_V2 && listeningSince != null) {
            kotlinx.coroutines.delay(LISTEN_LIMIT_MS)
            listener.stop()
          }
        }
        val actions = Actions(
          openOnPhone = ::openOnPhone,
          tryAgain = { lifecycleScope.launch { model.refresh() } },
          refuse = { hold -> lifecycleScope.launch { model.refuse(hold) } },
          halt = { taf -> lifecycleScope.launch { model.halt(taf) } },
          askHaltAll = model::askHaltAll,
          confirmHaltAll = { lifecycleScope.launch { model.confirmHaltAll() } },
          cancelHaltAll = model::cancelHaltAll,
          // v2 opens the microphone's screen first, and listens on Start.
          listen = { if (WATCH_V2) talk() else listen() },
          closeVoice = { listener.cancel(); model.closeVoice() },
          retry = { lifecycleScope.launch { model.retry() } },
          open = model::open,
          // The whole answer once it's read (Read more), never only its first line.
          hear = { ask -> speaker().say(model.heard(ask)) },
          readWhole = { id -> lifecycleScope.launch { model.readWhole(id) } },
          reply = { ask -> if (WATCH_V2) talk(ask.id) else listen(ask.id) },
          closeResult = model::closeResult,
          dismiss = model::dismissOutcome,
          start = { replyTo -> listen(replyTo) },
          stop = listener::stop,
          send = { lifecycleScope.launch { model.sendReviewed() } },
          rerecord = model::rerecord,
          delete = model::deleteReviewed,
          type = { typingFor = null; type() },
          quick = { model.review(it) },
          messageTaf = { taf -> talk(tafId = taf) },
          typeTaf = { taf -> typingFor = taf; type() },
          quickTaf = { taf, text -> model.review(text, tafId = taf) },
          readMore = { id -> lifecycleScope.launch { model.readMore(id) } },
          followAnswer = { id -> model.followAnswer(id) },
          keepTrying = { model.keepTrying() },
          rereadTaf = { model.rereadTaf() },
          keep = if (WATCH_V2) model::keepForLater else null,
          deleteKept = model::deleteKept,
          playback = if (WATCH_V2) playback else null,
          openItem = { item ->
            if (item.kind == InboxItem.Kind.ASK) model.open(model.askOf(item)) else model.markRead(item.id)
            lifecycleScope.launch { model.sendMarks() }
          },
          dismissItem = { id -> model.dismiss(id); lifecycleScope.launch { model.sendMarks() } },
          clearRead = { model.clearRead(); lifecycleScope.launch { model.sendMarks() } },
          openTaf = { taf -> lifecycleScope.launch { model.openTaf(taf) } },
          closeTaf = model::closeTaf,
          pickToHalt = model::pickToHalt,
          togglePick = model::togglePick,
          togglePickAll = model::togglePickAll,
          askPicked = model::askPicked,
          cancelPick = model::cancelPick,
          haltPicked = { lifecycleScope.launch { model.haltPicked() } },
          readCalendar = { day -> lifecycleScope.launch { model.readCalendar(day) } },
          closeCalendar = model::closeCalendar,
          readAccount = { lifecycleScope.launch { model.readAccount() } },
          signOut = ::signOutHere,
          signIn = {
            model.signInHere()
            lifecycleScope.launch {
              model.start()
              NeedsYouTileService.update(this@WatchActivity)
            }
          },
        )
        val inbox by model.inbox.collectAsState()
        val inboxKept by model.inboxKept.collectAsState()
        // Play stops the moment the review is left: its recording is erased then (WatchModel).
        androidx.compose.runtime.LaunchedEffect(voice is WatchModel.Voice.Review) {
          if (voice !is WatchModel.Voice.Review) playback.release()
        }
        val probe by listener.probe.collectAsState()
        val linkTrace by LinkTrace.latest.collectAsState()
        val working = asks.values.let { model.working() }
        val ready = asks.values.let { model.ready() }
        val thread = said.let { asks.let { model.thread() } }
        val answered = asks.let { model.answered() }
        // Nothing kept to show while the phone is reached: the linking screen (a first link, a first open).
        val linking = shown.let { said.let { inbox.let { model.linking() } } }
        val timing = result?.let { r -> if (BuildConfig.TIMING) model.timing(r.clientId)?.let(::readout) else null }
        // v2's pages once every step is in (WatchPages.kt); v1 until then.
        if (WATCH_V2) {
          WatchPages(shown, outcome, confirmingHaltAll, voice, result, working, ready, actions, kept = kept, timing = timing,
            // The Inbox kept on the watch, or, when it can't be (locked, no screen lock), what the phone answered now.
            probe = if (BuildConfig.TIMING) probe else null, linkTrace = if (BuildConfig.TIMING) linkTrace?.line else null, inbox = inbox.takeIf { inboxKept }, taf = taf, haltPick = haltPick,
            signedOutHere = signedOutHere, calendar = calendar, account = account, thread = thread, answered = answered, linking = linking, tafChatOff = tafChatOff, wholes = wholes)
        }
        else WatchScreen(shown, outcome, confirmingHaltAll, voice, result, working, ready, actions, timing = timing, whole = result?.let { wholes[it.id] })
        // Open on phone, said on the watch (row 28): the platform's own confirmation, in tafs's words, then back where it was.
        val openedNow by opened.collectAsState()
        OpenedOnPhone(openedNow) { opened.value = null }
      }
    }
    handle(intent)
    // A recording left by a crash or a kill: nothing holds it.
    VoiceFiles.sweep(VoiceFiles.dir(this))
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
  }

  /** A result's notification opens its card; the tile's "Ask tafs" starts listening. */
  private fun handle(intent: Intent?) {
    intent?.getByteArrayExtra(AskResults.EXTRA_ASK)?.let(WatchLink::decodeResult)?.let { model.took(it); model.open(it) }
    if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
      intent.removeExtra(EXTRA_LISTEN)
      if (WATCH_V2) talk() else listen()
    }
  }

  /**
   * One tap to talk (row 44, the owner's Galaxy Watch review): Ask Coach, a taf's microphone, Reply, the tile and the complication
   * open their screen already listening, with the dictation shown as it comes; no second tap on Start. The screen's own rules
   * stand: a watch without a screen lock, or locked, gets its line instead (`readyToRecord` → `NeedsLock`), and only a start
   * that is its own names its taf ([tafId]).
   */
  private fun talk(replyTo: String? = null, tafId: String? = null) {
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      if (model.talk(replyTo, tafId)) startListening(replyTo)
      return
    }
    // The first time: the screen shows Ready while the system asks for the microphone; listening starts once it's allowed.
    model.readyToRecord(replyTo, tafId)
    if (model.voice.value is WatchModel.Voice.Ready) {
      pendingTalk = true
      withMicrophone(replyTo)
    }
  }

  /** The open's reads and their tries, while the app is on screen. */
  private var opening: kotlinx.coroutines.Job? = null

  override fun onStart() {
    super.onStart()
    // What the phone kept for the watch while the app was put away (KeptResults) is taken in first.
    model.reload()
    // While the app is open, an ask's new state from the phone, and the Inbox's marks, are drawn here at once instead of notified.
    AskResults.shown = { ask -> runOnUiThread { model.took(ask) } }
    KeptResults.marks = { marks -> runOnUiThread { model.takeMarks(marks) } }
    KeptResults.chats = { tafId, lines -> runOnUiThread { model.tafChatChanged(tafId, lines) } }
    KeptResults.tafs = { kept -> runOnUiThread { model.tafsChanged(kept.tafs, kept.allHalted, kept.zone) } }
    KeptResults.needsYou = { kept -> runOnUiThread { model.needsYouPushed(kept.count, kept.words, kept.calls) } }
    // The phone signed out while the app is open: everything goes now, then the phone is read again.
    KeptResults.signedOut = { runOnUiThread { model.phoneSignedOut(); lifecycleScope.launch { model.refresh() } } }
    opening = lifecycleScope.launch {
      // What's kept shows at once; the phone is read behind it, tried again by itself when it's slow (docs/watch-wear.md).
      model.start()
      // The tile shows the count just read, or forgets it if the phone signed out.
      NeedsYouTileService.update(this@WatchActivity)
    }
  }

  override fun onStop() {
    super.onStop()
    AskResults.shown = null
    KeptResults.marks = null
    KeptResults.chats = null
    KeptResults.tafs = null
    KeptResults.needsYou = null
    KeptResults.signedOut = null
    opening?.cancel()
    opening = null
    listener.cancel()
    speaker?.close()
    speaker = null
    playback.release()
    // Put away: what's kept stays (the conversation, a taf's chat, a review's words), so the app comes back as it was; a review's
    // recording is erased, and any recording left over is swept. A locked watch keeps no Inbox.
    model.putAway()
    if (WATCH_V2) model.clearInboxIfLocked()
    VoiceFiles.sweep(VoiceFiles.dir(this))
  }

  override fun onDestroy() {
    listener.close()
    super.onDestroy()
  }

  private fun speaker(): Speaker = speaker ?: Speaker(this).also { speaker = it }

  /**
   * v2's Sign out, on this watch alone: listening and playing stop, the model forgets all it holds (the message kept for later and
   * the tile's count included), every recording is swept, the watch's own result notifications are taken away, and the tile is
   * drawn again with no count. The phone stays signed in.
   */
  private fun signOutHere() {
    listener.cancel()
    playback.release()
    model.signOutHere()
    VoiceFiles.sweep(VoiceFiles.dir(this))
    runCatching { getSystemService(android.app.NotificationManager::class.java)?.cancelAll() }
    NeedsYouTileService.update(this)
  }

  /** Opens tafs on the phone, at [where] (`needs-you`): approvals, and anything longer than the watch draws, are the phone's. */
  /**
   * Open on phone's outcome, as the watch's own confirmation over whatever screen it was pressed on, which stays: true, the phone
   * opened tafs there ("Opened on your phone"); false, it wasn't reached ("Check your phone"); null, nothing to say.
   */
  private val opened = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

  private fun openOnPhone(where: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WatchLink.OPEN_ON_PHONE + where)).addCategory(Intent.CATEGORY_BROWSABLE)
    val started = runCatching { RemoteActivityHelper(this).startRemoteActivity(intent) }.getOrNull()
    if (started == null) {
      opened.value = false
      return
    }
    started.addListener({ opened.value = runCatching { started.get() }.isSuccess }, mainExecutor)
  }

  companion object {
    /** The tile's "Ask tafs": open listening. */
    const val EXTRA_LISTEN = "bot.tafs.wear.LISTEN"

    private const val TYPED = "typed"
    private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
    private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"

    /** How long v1's "Sent" stays before the microphone comes back (v2's stays until the answer). */
    const val SENT_SHOWN_MS = 2_500L

    /** The internal builds' timing line: listen, receipt and result, in milliseconds as the watch measured them. */
    fun readout(t: WatchModel.Timing): String = Words.Watch.timing(ms(t.listenMs), ms(t.receiptMs), ms(t.resultMs))

    private fun ms(v: Long?): String = v?.toString() ?: "–"
  }
}

/**
 * What Open on phone did, said on the watch (row 28): opened ([opened] true), the platform's Open on phone confirmation with
 * "Opened on your phone"; not reached (false), its failure confirmation with "Check your phone". Each goes by itself.
 */
@Composable
internal fun OpenedOnPhone(opened: Boolean?, done: () -> Unit) {
  val openedStyle = androidx.wear.compose.material3.OpenOnPhoneDialogDefaults.curvedTextStyle
  val failedStyle = androidx.wear.compose.material3.ConfirmationDialogDefaults.curvedTextStyle
  val openedWords = openedLine(true).orEmpty()
  val failedWords = openedLine(false).orEmpty()
  androidx.wear.compose.material3.OpenOnPhoneDialog(
    visible = opened == true, onDismissRequest = done,
    curvedText = { openOnPhoneDialogCurvedText(openedWords, openedStyle) },
    modifier = Modifier.testTag(OPENED_ON_PHONE),
  )
  androidx.wear.compose.material3.FailureConfirmationDialog(
    visible = opened == false, onDismissRequest = done,
    curvedText = { confirmationDialogCurvedText(failedWords, failedStyle) },
    modifier = Modifier.testTag(OPEN_ON_PHONE_FAILED),
  )
}

/** What Open on phone's confirmation says: opened, "Opened on your phone"; not reached, "Check your phone"; nothing to say, null. */
internal fun openedLine(opened: Boolean?): String? = when (opened) {
  true -> Words.Watch.openedOnPhone
  false -> Words.Watch.checkPhoneTitle
  null -> null
}

/** The Open on phone confirmations, for the screen tests. */
internal const val OPENED_ON_PHONE = "opened-on-phone"
internal const val OPEN_ON_PHONE_FAILED = "open-on-phone-failed"

/** What the screen can do: everything that reaches the phone goes through [WatchModel]. */
internal class Actions(
  val openOnPhone: (String) -> Unit,
  val tryAgain: () -> Unit,
  val refuse: (String) -> Unit,
  /** One taf, by its id. */
  val halt: (String) -> Unit,
  /** Halt all: asked first, then confirmed or cancelled. */
  val askHaltAll: () -> Unit,
  val confirmHaltAll: () -> Unit,
  val cancelHaltAll: () -> Unit,
  /** Ask tafs: listen, and send when the person stops. */
  val listen: () -> Unit,
  val closeVoice: () -> Unit,
  /** "Not sure it arrived": the same words, the same id. */
  val retry: () -> Unit,
  /** Opens an ask's result card. */
  val open: (AskLine) -> Unit,
  /** Reads the result aloud, on the watch. */
  val hear: (AskLine) -> Unit,
  /** Listens again, continuing this ask. */
  val reply: (AskLine) -> Unit,
  val closeResult: () -> Unit,
  val dismiss: () -> Unit,
  // v2's microphone (ReviewScreens.kt): v1 never calls these.
  /** Start, on the microphone's screen. */
  val start: (String?) -> Unit = {},
  /** Stop: listening ends and the words go to the review. */
  val stop: () -> Unit = {},
  val send: () -> Unit = {},
  val rerecord: () -> Unit = {},
  val delete: () -> Unit = {},
  /** Type: the watch's keyboard, then the review. */
  val type: () -> Unit = {},
  /** A quick reply picked: to the review. */
  val quick: (String) -> Unit = {},
  /** v2's Keep for later, on "Not sure it arrived"; none in v1, which doesn't offer it. */
  val keep: (() -> Unit)? = null,
  /** Deletes the message kept for later. */
  val deleteKept: () -> Unit = {},
  /** v2's Play and Pause for the review's recording, on the watch. */
  val playback: Playback? = null,
  /** v2's Inbox: opens a line (read), dismisses one, clears the read ones. */
  val openItem: (InboxItem) -> Unit = {},
  val dismissItem: (String) -> Unit = {},
  val clearRead: () -> Unit = {},
  // v2's step (v) (TafScreens.kt): a taf's page, and choosing which to halt.
  /** A taf tapped on the Tafs page: its page, read from the phone. */
  val openTaf: (String) -> Unit = {},
  val closeTaf: () -> Unit = {},
  /** The menu's Halt: Choose which to halt. */
  val pickToHalt: () -> Unit = {},
  val togglePick: (String) -> Unit = {},
  val togglePickAll: () -> Unit = {},
  /** Halt now · N: asked once more. */
  val askPicked: () -> Unit = {},
  val cancelPick: () -> Unit = {},
  /** Halt now, confirmed. */
  val haltPicked: () -> Unit = {},
  // v2's step (vi) (AccountScreens.kt): v1 never calls these.
  /** Reads a calendar day (`YYYY-MM-DD`), or today with none. */
  val readCalendar: (String?) -> Unit = {},
  val closeCalendar: () -> Unit = {},
  /** Reads Account's lines from the phone. */
  val readAccount: () -> Unit = {},
  /** Sign out, on this watch alone, confirmed. */
  val signOut: () -> Unit = {},
  /** Sign in on this watch again. */
  val signIn: () -> Unit = {},
  // Chatting with a taf (TafScreens.kt): each start names its taf, so words never go to one by another's start.
  /** Speak to the taf open now: the microphone's screen, for that taf. */
  val messageTaf: (String) -> Unit = {},
  /** Type to the taf open now: the watch's keyboard, for that taf. */
  val typeTaf: (String) -> Unit = {},
  /** A quick reply picked for the taf open now: to the review, for that taf. */
  val quickTaf: (String, String) -> Unit = { _, _ -> },
  /** Read more on a taf's answer, by its request's id. */
  val readMore: (String) -> Unit = {},
  /** Read more on an answer's card (Coach's or a taf's) that tafs cloud says goes on, by its request's id. */
  val readWhole: (String) -> Unit = {},
  /** While a taf's chat on screen is answering: its answer followed through the phone, bounded ([WatchModel.followAnswer]). */
  val followAnswer: suspend (String) -> Unit = {},
  /** While a send's linking screen shows: the same words sent again by itself ([WatchModel.keepTrying]). */
  val keepTrying: suspend () -> Unit = {},
  /** While a taf's page can't be read: read again by itself, behind its calm linking line. */
  val rereadTaf: suspend () -> Unit = {},
) {
  companion object {
    /** Nothing happens: for a screen drawn on its own (the store screenshots). */
    val none = Actions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
  }
}

/**
 * The watch's screen. The microphone's screens and a result's card take the whole face; otherwise the microphone is first, with
 * the asks at work and Needs you's count under it, then Needs you and Your tafs below. [list] is the screen's own scroll unless a
 * caller (the store screenshots) opens it on another item.
 */
@Composable
internal fun WatchScreen(
  shown: WatchModel.Shown,
  outcome: WatchModel.Outcome?,
  confirmingHaltAll: Boolean,
  voice: WatchModel.Voice?,
  result: AskLine?,
  working: List<AskLine>,
  ready: List<AskLine>,
  act: Actions,
  // Opened on the microphone.
  list: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0),
  timing: String? = null,
  whole: WatchModel.Whole? = null,
) {
  when {
    voice != null -> return VoiceScreen(voice, act)
    result != null -> return ResultScreen(result, act, timing, whole)
    outcome is WatchModel.Outcome.Halting -> return HaltingScreen(outcome.taf)
  }
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { AskHome(status(shown, working, ready), act.listen, opens(working, ready, act)) }
    outcome?.let { item { OutcomeCard(it, act) } }
    when (shown) {
      WatchModel.Shown.Asking -> line(Words.Watch.askingPhone)
      WatchModel.Shown.SignedOut -> line(Words.Watch.signInOnPhone)
      is WatchModel.Shown.PhoneUnreachable -> {
        line(shown.why.line)
        item { Button(onClick = act.tryAgain, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.tryAgain) } }
      }
      WatchModel.Shown.OpenOnPhone -> item {
        Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
      }
      is WatchModel.Shown.Read -> {
        val brief = shown.brief
        if (brief != null) {
          // Your brief, in Needs you's place (the switch on, and the account has one).
          briefItems(brief, shown, act)
        } else {
          item { ListHeader { Text(Words.NeedsYou.title, color = Look.held) } }
          // Nothing waits: the owner's all-clear (board 12) above Needs you's own words.
          if (shown.needsYou.allClear) item { AllClearIllustration() }
          line(shown.needsYou.words)
          for (call in shown.needsYou.calls) item { HeldCallCard(call, act) }
        }
        shown.tafs?.let { read ->
          item { ListHeader { Text(Words.Home.yourTafs) } }
          for (taf in read.tafs) item { TafRow(taf, act) }
          if (!read.allHalted && read.tafs.isNotEmpty()) item {
            if (confirmingHaltAll) {
              // Asked once more on the wrist: every taf stops until resumed on the phone.
              Card(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(Words.HaltAll.title, style = MaterialTheme.typography.titleSmall)
                  Button(onClick = act.confirmHaltAll, modifier = Modifier.fillMaxWidth(), colors = dangerButton()) { Text(Words.Halt.allNow) }
                  Button(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth(), colors = neutralButton(onCard = true)) { Text(Words.Common.cancel) }
                }
              }
            } else {
              Button(onClick = act.askHaltAll, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton()) { Text(Words.Halt.allNow) }
            }
          }
        }
        item { Text(Words.Watch.askTextOnly, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
      }
    }
  }
}

/**
 * The lines under the microphone: each ask at work or ready, by its taf and tafs cloud's word, when there are one or two; past
 * that, how many, at work with Coach's blue dot and ready with the green one; then Needs you's count, in amber, when something
 * waits.
 */
internal fun status(shown: WatchModel.Shown, working: List<AskLine>, ready: List<AskLine>): List<Pair<String, androidx.compose.ui.graphics.Color?>> {
  val lines = mutableListOf<Pair<String, androidx.compose.ui.graphics.Color?>>()
  if (working.size + ready.size <= 2) {
    working.forEach { lines += stateLine(it) to Look.coach }
    ready.forEach { lines += stateLine(it) to Look.done }
  } else {
    lines += listOfNotNull(
      working.size.takeIf { it > 0 }?.let(Words.Watch::workingCount),
      ready.size.takeIf { it > 0 }?.let(Words.Watch::readyCount),
    ).joinToString(" · ") to null
  }
  val count = (shown as? WatchModel.Shown.Read)?.needsYou?.count ?: 0
  if (count > 0) lines += Words.Watch.needsYouCount(count) to Look.held
  return lines
}

/** What tapping each status line opens: a ready ask's card; the others open nothing. */
private fun opens(working: List<AskLine>, ready: List<AskLine>, act: Actions): List<() -> Unit> =
  if (working.size + ready.size > 2) emptyList() else working.map { {} } + ready.map { ask -> { act.open(ask) } }

/**
 * Your brief, in tafs cloud's words, where Needs you would be: its title and `when` line, each decision on its own card (answered on
 * the phone, so Open on phone; Refuse only where Needs you offers it), its line for more, what got done, and the rest.
 */
internal fun ScalingLazyListScope.briefItems(brief: WatchReply.Brief, shown: WatchModel.Shown.Read, act: Actions) {
  item { ListHeader { Text(brief.title) } }
  brief.whenLine?.let { line(it) }
  for (decision in brief.decisions) item { DecisionCard(decision, brief.urgent, shown.refusable(decision), act) }
  brief.more?.let { line(it) }
  brief.done?.let { done ->
    item {
      Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(done, style = MaterialTheme.typography.titleSmall)
        for (case in brief.doneCases) Text(case, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
  if (brief.rest != null || brief.needsYou != null) item {
    // What waits and needs nothing now, and what Needs you holds beyond the brief: Needs you's list, on the phone.
    Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        brief.rest?.let { Text(it) }
        brief.needsYou?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}

/**
 * One of the brief's decisions: its taf, the question, its deadline, and why it can't wait. It's answered on the phone, never here:
 * Open on phone, and Refuse only for a held call Needs you would let the wrist refuse ([refuseHold]).
 */
@Composable
private fun DecisionCard(decision: DecisionLine, urgentWord: String?, refuseHold: String?, act: Actions) {
  Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      decision.taf?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
      if (decision.urgent) urgentWord?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
      Text(decision.question)
      decision.deadline?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      decision.cantWait?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      if (refuseHold != null) {
        Button(onClick = { act.refuse(refuseHold) }, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton(onCard = true)) { Text(Words.NeedsYou.refuse) }
      }
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton(onCard = true)) { Text(Words.Watch.openOnPhone) }
    }
  }
}

internal fun ScalingLazyListScope.line(words: String) = item {
  Text(words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** What came of a Refuse or a Halt, in the phone's words; anything that didn't go through offers Open on phone. */
@Composable
internal fun OutcomeCard(outcome: WatchModel.Outcome, act: Actions) {
  Card(onClick = act.dismiss, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      when (outcome) {
        is WatchModel.Outcome.Done -> Text(outcome.words, style = MaterialTheme.typography.titleSmall)
        is WatchModel.Outcome.Halting -> Text(if (outcome.taf.isNullOrEmpty()) Words.Watch.haltingAll else Words.Watch.halting(outcome.taf))
        WatchModel.Outcome.AlreadyAnswered -> Text(Words.Watch.alreadyAnswered)
        WatchModel.Outcome.NeedsLock -> Text(Words.Watch.needsLock)
        is WatchModel.Outcome.PhoneUnreachable -> Text(outcome.why.line)
        is WatchModel.Outcome.NotDone -> outcome.words?.let { Text(it) }
        // v2: each taf picked, with what came of its Halt (TafScreens.kt).
        is WatchModel.Outcome.Several -> for ((taf, each) in outcome.each) Text(listOfNotNull(taf, wordsOf(each)).joinToString(" · "))
      }
      if (outcome !is WatchModel.Outcome.Done && outcome !is WatchModel.Outcome.AlreadyAnswered && (outcome as? WatchModel.Outcome.Several)?.done != true) {
        Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton(onCard = true)) { Text(Words.Watch.openOnPhone) }
      }
    }
  }
}

/**
 * A held call as the phone's Needs you words it: its taf, what it wants, KIFF's amount and the reason. Refuse, where the phone
 * can answer it; approving is always the phone's.
 */
@Composable
internal fun HeldCallCard(call: HeldLine, act: Actions) {
  Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(call.taf, style = MaterialTheme.typography.titleSmall)
      Text(call.wants)
      call.amount?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
      call.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      if (call.refusable) {
        Button(onClick = { act.refuse(call.holdId) }, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton(onCard = true)) { Text(Words.NeedsYou.refuse) }
      }
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = heldButton()) { Text(Words.Watch.approveOnPhone) }
    }
  }
}

@Composable
private fun TafRow(taf: TafLine, act: Actions) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(taf.name, style = MaterialTheme.typography.titleSmall)
    Text(taf.state, style = MaterialTheme.typography.bodySmall)
    if (!taf.halted) Button(onClick = { act.halt(taf.tafId) }, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton()) { Text(Words.Halt.now) }
  }
}
