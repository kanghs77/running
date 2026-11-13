package app.runmate

import android.graphics.Color as AColor
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
import app.runmate.ui.TrackDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
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
        if (!RunController.state.value.running) App.instance.syncNow()
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
                val tabs = listOf("🏃" to "달리기", "📋" to "기록", "📈" to "분석", "🏆" to "순위", "👥" to "그룹")
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i && detail == null,
                        onClick = { tab = i; detail = null },
                        modifier = Modifier.testTag("tab$i"),
                        icon = {
                            if (i == 1 && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Text(icon, fontSize = 20.sp) }
                            else Text(icon, fontSize = 20.sp)
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
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
                else -> GroupsScreen()
            }
        }
    }
    finished?.let { id -> SendDialog(id) { RunController.finished.value = null } }
}
