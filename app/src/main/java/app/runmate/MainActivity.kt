package app.runmate

import android.graphics.Color as AColor
import app.runmate.core.tr
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.runmate.ui.AnalyzeScreen
import app.runmate.ui.GroupsScreen
import app.runmate.ui.HistoryScreen
import app.runmate.ui.HomeScreen
import app.runmate.ui.RankScreen
import app.runmate.ui.RunMateTheme
import app.runmate.ui.SendDialog
import app.runmate.ui.SettingsScreen
import app.runmate.ui.CoffeeFooter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import app.runmate.ui.TrackDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        App.instance.applyLanguage()      // 휴대폰 언어가 바뀌어 화면이 다시 만들어진 경우 반영
        setContent { RunMateTheme { AppRoot() } }
        // 앱을 보고 있는 동안에는 1분마다 가볍게 새 기록을 확인한다(달리는 중 제외).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { delay(60_000); if (!RunController.state.value.running) App.instance.syncNow(fast = true) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        RunController.uiVisible.value = true
        // 달리는 중에는 통신하지 않는다(전력 절약)
        if (!RunController.state.value.running) { App.instance.syncNow(); App.instance.coffee.refresh() }
    }

    override fun onStop() {
        RunController.uiVisible.value = false
        super.onStop()
    }
}

@Composable
fun AppRoot() {
    val app = App.instance
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val compare = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val metas by app.store.metas.collectAsStateWithLifecycle()
    val finished by RunController.finished.collectAsStateWithLifecycle()
    val notice by RunController.notice.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val unread = metas.count { it.unread }

    LaunchedEffect(notice) {
        notice?.let { snack.showSnackbar(it); RunController.notice.value = null }
    }
    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && tab != 0) { tab = 0 }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                val tabs = listOf("🏃" to tr("달리기", "Run"), "📋" to tr("기록", "History"), "📈" to tr("분석", "Analyze"), "🏆" to tr("순위", "Rank"), "👥" to tr("그룹", "Groups"), "⚙️" to tr("설정", "Settings"))
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i && detail == null,
                        onClick = { tab = i; detail = null },
                        modifier = Modifier.testTag("tab$i"),
                        icon = {
                            if (i == 1 && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Text(icon, fontSize = 20.sp) }
                            else Text(icon, fontSize = 20.sp)
                        },
                        label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    )
                }
            }
        },
    ) { pad ->
        val running by RunController.state.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize().padding(pad)) {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = detail
            if (d != null) {
                TrackDetailScreen(d, onBack = { detail = null }, onCompare = {
                    if (d !in compare && compare.size < 8) compare.add(d)
                    detail = null; tab = 2
                })
            } else when (tab) {
                0 -> HomeScreen(unread = unread, onOpenHistory = { tab = 1 })
                1 -> HistoryScreen(onOpen = { detail = it })
                2 -> AnalyzeScreen(compare)
                3 -> RankScreen()
                4 -> GroupsScreen()
                else -> SettingsScreen()
            }
          }
          // 달리는 중에는 잘못 누르지 않도록 숨긴다
          if (!running.running) CoffeeFooter()
        }
    }
    finished?.let { id -> SendDialog(id) { RunController.finished.value = null } }
}
